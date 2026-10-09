package com.projectgamers.splitgate.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.security.SecureRandom;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Tunel icindeki bir TCP baglantisini sonlandirir (uygulamaya karsi "sunucu" gibi davranir) ve
 * gercek bir soket uzerinden hedefe iletir. Boylece ilk veri parcasini (ClientHello) istedigimiz
 * gibi bolebiliriz.
 *
 * Not: istemci = telefondaki uygulama (c*), sunucu = gercek hedef (s*).
 */
final class TcpSession {
    private static final SecureRandom ISN_RNG = new SecureRandom();

    static final int FIN = 0x01, SYN = 0x02, RST = 0x04, PSH = 0x08, ACK = 0x10;

    private static final int IDLE_MS = 5 * 60 * 1000;
    private static final int INIT_RTO = 400, MAX_RTO = 3000, MAX_RETRIES = 8;
    private static final int RCV_WND = 65535;

    private static final class Chunk {
        final byte[] data;
        final int endSeq;
        final boolean fin;

        Chunk(byte[] data, int endSeq, boolean fin) {
            this.data = data;
            this.endSeq = endSeq;
            this.fin = fin;
        }
    }

    private static final Chunk STOP = new Chunk(null, 0, false);

    private static final class Seg {
        final int seq;
        final byte[] data;
        final int flags;

        Seg(int seq, byte[] data, int flags) {
            this.seq = seq;
            this.data = data;
            this.flags = flags;
        }

        int end() {
            return seq + data.length + ((flags & FIN) != 0 ? 1 : 0);
        }
    }

    private static final byte[] EMPTY = new byte[0];

    private final TunEngine eng;
    private final FlowKey key;
    private final int cIp, cPort, sIp, sPort;
    private final int clientIsn;
    private final int serverIsn;
    private final int mss;
    private final Object lock = new Object();
    private final LinkedBlockingQueue<Chunk> wq = new LinkedBlockingQueue<>();
    private final ArrayDeque<Seg> unacked = new ArrayDeque<>();

    private volatile Socket sock;
    private boolean closed;
    private boolean synAckSent;
    private int rcvNxt;      // istemciden beklenen sonraki sira no (kuyruga alinanlar dahil)
    private int ackedSeq;    // istemciye ACK'ledigimiz (hedefe yazilmis) son sira no
    private int sndUna;      // istemcinin onayladigi en eski sira no
    private int sndNxt;      // istemciye gonderecegimiz sonraki sira no
    private int clientWnd = 65535;
    private boolean clientFin, finSent, finAcked, writerDone;
    private long lastActive = System.currentTimeMillis();
    private long lastTx = lastActive;
    private int retries;
    private int rto = INIT_RTO;

    TcpSession(TunEngine eng, FlowKey key, int clientIsn, int clientMss) {
        this.eng = eng;
        this.key = key;
        this.cIp = key.srcIp;
        this.cPort = key.srcPort;
        this.sIp = key.dstIp;
        this.sPort = key.dstPort;
        this.clientIsn = clientIsn;
        this.rcvNxt = clientIsn + 1;
        this.ackedSeq = rcvNxt;
        this.serverIsn = ISN_RNG.nextInt();
        this.sndUna = serverIsn;
        this.sndNxt = serverIsn;
        this.mss = Math.max(100, Math.min(clientMss > 0 ? clientMss : 536, 1400));
    }

    void start() {
        eng.execute(this::connectAndRead);
    }

    // ------------------------------------------------------------------ baglanti kurulumu

    private void connectAndRead() {
        Socket s;
        try {
            s = eng.connector.tcp(Pkt.inet(sIp), sPort, 8000);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
        } catch (IOException e) {
            eng.tcpFailed.incrementAndGet();
            eng.log("✗ " + Pkt.ipStr(sIp) + ":" + sPort + " could not connect (" + e.getMessage() + ")");
            abort(true);
            return;
        }
        synchronized (lock) {
            if (closed) {
                closeQuiet(s);
                return;
            }
            sock = s;
            sendSynAck();
        }
        eng.tcpOpened.incrementAndGet();
        eng.execute(this::writerLoop);
        readerLoop(s);
    }

    private void sendSynAck() {
        byte[] opts = {2, 4, (byte) (mss >> 8), (byte) mss};
        synAckSent = true;
        sndNxt = serverIsn + 1;
        eng.emit(Pkt.tcp(sIp, cIp, sPort, cPort, serverIsn, clientIsn + 1, SYN | ACK, advWnd(), opts, null, 0, 0));
    }

    // ------------------------------------------------------------------ istemciden gelen paketler (TUN thread'i)

    void onSegment(byte[] b, int flags, int seq, int ack, int wnd, int pOff, int pLen) {
        synchronized (lock) {
            if (closed) return;
            lastActive = System.currentTimeMillis();
            if ((flags & RST) != 0) {
                abort(false);
                return;
            }
            if (!synAckSent) return; // hala baglaniyor; tekrarlanan SYN'leri yoksay
            if ((flags & SYN) != 0) {
                if (sndUna == serverIsn) sendSynAck(); // SYN-ACK kaybolmus olabilir
                return;
            }
            if ((flags & ACK) != 0) processAck(ack, wnd);

            boolean fin = (flags & FIN) != 0;
            if (pLen == 0 && !fin) return; // saf ACK

            int d = seq - rcvNxt;
            int off = pOff, len = pLen;
            if (d > 0) { // sirasi bozuk: bosluk var, yeniden iletim bekle
                sendAck();
                return;
            }
            if (d < 0) { // kismen/tamamen tekrar
                int skip = -d;
                if (skip >= len) {
                    if (!(fin && skip == len && !clientFin)) {
                        sendAck();
                        return;
                    }
                    len = 0;
                } else {
                    off += skip;
                    len -= skip;
                }
            }
            boolean any = false;
            if (len > 0) {
                eng.bytesUp.addAndGet(len);
                rcvNxt += len;
                wq.offer(new Chunk(Arrays.copyOfRange(b, off, off + len), rcvNxt, false));
                any = true;
            }
            if (fin && !clientFin) {
                clientFin = true;
                rcvNxt += 1;
                wq.offer(new Chunk(null, rcvNxt, true));
                any = true;
            }
            if (!any) sendAck();
        }
    }

    private void processAck(int ack, int wnd) {
        if (ack - sndUna > 0 && ack - sndNxt <= 0) {
            sndUna = ack;
            retries = 0;
            rto = INIT_RTO;
            lastTx = System.currentTimeMillis();
            while (!unacked.isEmpty() && unacked.peekFirst().end() - ack <= 0) unacked.pollFirst();
            if (finSent && ack == sndNxt) finAcked = true;
        }
        clientWnd = wnd;
        lock.notifyAll();
        maybeFinish();
    }

    // ------------------------------------------------------------------ hedefe yazma (writer thread'i)

    private void writerLoop() {
        Socket s = sock;
        try {
            OutputStream out = s.getOutputStream();
            boolean first = true;
            Chunk pending = null;
            while (true) {
                Chunk c = pending != null ? pending : wq.take();
                pending = null;
                if (c == STOP) return;
                if (c.fin) {
                    try {
                        s.shutdownOutput();
                    } catch (IOException ignore) {
                    }
                    markWritten(c.endSeq);
                    synchronized (lock) {
                        writerDone = true;
                        maybeFinish();
                    }
                    return;
                }
                if (!first) {
                    out.write(c.data);
                    markWritten(c.endSeq);
                    continue;
                }
                first = false;
                byte[] data = c.data;
                int end = c.endSeq;

                int need = eng.strategy.bytesWanted(data);
                if (need > data.length) { // ClientHello birden fazla segmente yayilmis olabilir; tamamini bekle
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(need);
                    bo.write(data, 0, data.length);
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
                    while (bo.size() < need) {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) break;
                        Chunk n = wq.poll(left, TimeUnit.NANOSECONDS);
                        if (n == null) break;
                        if (n == STOP) return;
                        if (n.fin) {
                            pending = n;
                            break;
                        }
                        bo.write(n.data, 0, n.data.length);
                        end = n.endSeq;
                    }
                    data = bo.toByteArray();
                }

                Strategy.Plan plan = eng.strategy.plan(data);
                eng.log((plan.host != null ? plan.host : Pkt.ipStr(sIp)) + ":" + sPort + "  " + plan.kind);
                if (plan.pieces.size() > 1) eng.splits.incrementAndGet();
                String note = Sender.send(s, out, plan, eng.socketTtl);
                if (!note.isEmpty()) eng.log((plan.host != null ? plan.host : Pkt.ipStr(sIp)) + ":" + sPort + " " + note.trim());
                markWritten(end);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            abort(true);
        }
    }

    private void markWritten(int endSeq) {
        synchronized (lock) {
            if (closed) return;
            if (endSeq - ackedSeq > 0) ackedSeq = endSeq;
            sendAck();
        }
    }

    // ------------------------------------------------------------------ hedeften istemciye (reader thread'i)

    private void readerLoop(Socket s) {
        try {
            InputStream in = s.getInputStream();
            byte[] buf = new byte[mss];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                if (!sendData(buf, n)) return;
            }
            sendFin();
        } catch (IOException e) {
            abort(true);
        }
    }

    private boolean sendData(byte[] b, int n) {
        synchronized (lock) {
            while (!closed) {
                int inflight = sndNxt - sndUna;
                if (inflight == 0 || clientWnd - inflight >= n) break;
                try {
                    lock.wait(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (closed) return false;
            Seg g = new Seg(sndNxt, Arrays.copyOf(b, n), PSH | ACK);
            eng.bytesDown.addAndGet(n);
            sndNxt += n;
            long now = System.currentTimeMillis();
            if (unacked.isEmpty()) lastTx = now;
            lastActive = now;
            unacked.addLast(g);
            emitSeg(g);
            return true;
        }
    }

    private void sendFin() {
        synchronized (lock) {
            if (closed || finSent) return;
            finSent = true;
            Seg g = new Seg(sndNxt, EMPTY, FIN | ACK);
            sndNxt += 1;
            long now = System.currentTimeMillis();
            if (unacked.isEmpty()) lastTx = now;
            unacked.addLast(g);
            emitSeg(g);
            maybeFinish();
        }
    }

    // ------------------------------------------------------------------ yardimcilar

    private int advWnd() {
        int used = rcvNxt - ackedSeq;
        return Math.max(0, Math.min(RCV_WND, RCV_WND - used));
    }

    private void emitSeg(Seg g) {
        eng.emit(Pkt.tcp(sIp, cIp, sPort, cPort, g.seq, ackedSeq, g.flags | ACK, advWnd(), null, g.data, 0, g.data.length));
    }

    private void sendAck() {
        eng.emit(Pkt.tcp(sIp, cIp, sPort, cPort, sndNxt, ackedSeq, ACK, advWnd(), null, null, 0, 0));
    }

    /** Yeniden iletim ve bosta kalma zamanlayicisi (motorun zamanlayicisindan). */
    void tick(long now) {
        synchronized (lock) {
            if (closed) return;
            if (now - lastActive > IDLE_MS) {
                abort(true);
                return;
            }
            if (!unacked.isEmpty() && now - lastTx > rto) {
                if (++retries > MAX_RETRIES) {
                    abort(true);
                    return;
                }
                emitSeg(unacked.peekFirst());
                lastTx = now;
                rto = Math.min(rto * 2, MAX_RTO);
            }
        }
    }

    private void maybeFinish() {
        if (!closed && clientFin && writerDone && finSent && finAcked) teardown();
    }

    void abort(boolean sendRst) {
        synchronized (lock) {
            if (closed) return;
            if (sendRst) {
                if (synAckSent) {
                    eng.emit(Pkt.tcp(sIp, cIp, sPort, cPort, sndNxt, ackedSeq, RST | ACK, 0, null, null, 0, 0));
                } else {
                    eng.emit(Pkt.tcp(sIp, cIp, sPort, cPort, 0, clientIsn + 1, RST | ACK, 0, null, null, 0, 0));
                }
            }
            teardown();
        }
    }

    private void teardown() {
        closed = true;
        Socket s = sock;
        if (s != null) closeQuiet(s);
        wq.offer(STOP);
        lock.notifyAll();
        eng.tcpRemove(key, this);
    }

    private static void closeQuiet(Socket s) {
        try {
            s.close();
        } catch (IOException ignore) {
        }
    }
}
