// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate.engine;

import java.io.IOException;
import java.net.DatagramSocket;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TUN'dan gelen IPv4 paketlerini isler:
 *  - UDP 53  -> DNS-over-HTTPS ile cevaplanir (AAAA bos doner)
 *  - UDP 443 -> (isteğe bagli) dusurulur, QUIC yerine TCP+TLS kullanilsin
 *  - diger UDP -> gercek soket uzerinden iletilir
 *  - TCP     -> {@link TcpSession} ile sonlandirilip gercek soketle iletilir; ilk veri bolunur
 *
 * Android'e bagimli degildir; Android tarafinda soketler uygulama VPN disinda tutuldugu icin
 * dogrudan cikar.
 */
public final class TunEngine {
    private static final int MAX_TCP = 1500;
    private static final int MAX_UDP = 1000;

    final PacketIo io;
    final Strategy strategy;
    final DnsResolver dns;
    final Connector connector;
    private final EngineLog logger;
    volatile SocketTtl socketTtl;

    final AtomicLong tcpOpened = new AtomicLong();
    final AtomicLong tcpFailed = new AtomicLong();
    final AtomicLong splits = new AtomicLong();
    final AtomicLong dnsQueries = new AtomicLong();
    final AtomicLong quicDropped = new AtomicLong();
    /** Uygulamalardan hedefe giden / hedeften uygulamalara gelen yuk (payload) bayt sayisi. */
    final AtomicLong bytesUp = new AtomicLong();
    final AtomicLong bytesDown = new AtomicLong();

    private final ConcurrentHashMap<FlowKey, TcpSession> tcp = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<FlowKey, UdpFlow> udp = new ConcurrentHashMap<>();
    private final ExecutorService pool;
    private volatile boolean running = true;

    public TunEngine(PacketIo io, Strategy strategy, DnsResolver dns, Connector connector, EngineLog logger) {
        this.io = io;
        this.strategy = strategy;
        this.dns = dns;
        this.connector = connector;
        this.logger = logger;
        this.pool = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicLong n = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "dpi-worker-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    // ------------------------------------------------------------------ yasam dongusu

    /** Engellenerek calisir; {@link #stop()} cagrilana veya TUN kapanana kadar. */
    public void run() {
        Thread timer = new Thread(this::timerLoop, "dpi-timer");
        timer.setDaemon(true);
        timer.start();
        log("Engine started (mode: " + strategy.mode + ", block QUIC: " + strategy.blockQuic + ")");
        byte[] buf = new byte[65536];
        try {
            while (running) {
                int n = io.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                try {
                    handlePacket(buf, n);
                } catch (RuntimeException e) {
                    log("packet error: " + e);
                }
            }
        } catch (IOException e) {
            if (running) log("TUN read error: " + e.getMessage());
        } finally {
            shutdown();
        }
    }

    public void stop() {
        running = false;
        io.close();
    }

    private void shutdown() {
        running = false;
        for (TcpSession s : tcp.values()) s.abort(false);
        for (UdpFlow f : udp.values()) f.close();
        pool.shutdownNow();
        log("Engine stopped");
    }

    private void timerLoop() {
        int tick = 0;
        while (running) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            for (TcpSession s : tcp.values()) s.tick(now);
            if (++tick % 20 == 0) {
                for (UdpFlow f : udp.values()) if (f.idle(now)) f.close();
            }
        }
    }

    // ------------------------------------------------------------------ disaridan kullanilan yardimcilar

    void log(String msg) {
        if (logger != null) logger.log(msg);
    }

    void execute(Runnable r) {
        try {
            pool.execute(r);
        } catch (RejectedExecutionException ignore) {
            // kapanıyor
        }
    }

    void emit(byte[] pkt) {
        synchronized (io) {
            try {
                io.write(pkt, 0, pkt.length);
            } catch (IOException e) {
                if (running) log("TUN write error: " + e.getMessage());
            }
        }
    }

    void tcpRemove(FlowKey k, TcpSession s) {
        tcp.remove(k, s);
    }

    void removeUdp(FlowKey k, UdpFlow f) {
        udp.remove(k, f);
    }

    /**
     * Alttaki ag degistiginde cagrilir. Eski agdaki gercek soketler olu kalabilir ama uygulamalar tunel
     * ucunda hala bagli sanir; RST ile kapatilinca hemen yeni agdan yeniden baglanirlar.
     */
    public void resetConnections(String reason) {
        int t = 0, u = 0;
        for (TcpSession s : tcp.values()) {
            s.abort(true);
            t++;
        }
        for (UdpFlow f : udp.values()) {
            f.close();
            u++;
        }
        log("Network changed (" + reason + "): reset " + t + " TCP and " + u + " UDP connections");
    }

    public void setSocketTtl(SocketTtl t) {
        socketTtl = t;
    }

    public Strategy strategy() {
        return strategy;
    }

    public int activeTcp() {
        return tcp.size();
    }

    public long bytesUp() {
        return bytesUp.get();
    }

    public long bytesDown() {
        return bytesDown.get();
    }

    public long splitCount() {
        return splits.get();
    }

    public long dnsCount() {
        return dnsQueries.get();
    }

    public long tcpOpenedCount() {
        return tcpOpened.get();
    }

    public String stats() {
        return "TCP opened " + tcpOpened.get() + " | failed " + tcpFailed.get() + " | split " + splits.get()
                + " | DNS " + dnsQueries.get() + " | QUIC dropped " + quicDropped.get() + " | active " + tcp.size();
    }

    // ------------------------------------------------------------------ paket isleme

    private void handlePacket(byte[] b, int len) {
        if (len < 20 || (b[0] >> 4) != 4) return; // sadece IPv4
        int ihl = (b[0] & 0x0f) * 4;
        if (ihl < 20 || len < ihl) return;
        int total = Pkt.u16(b, 2);
        if (total < ihl || total > len) return;
        len = total;
        if ((Pkt.u16(b, 6) & 0x3fff) != 0) return; // parcalanmis IP paketlerini desteklemiyoruz
        int proto = b[9] & 0xff;
        int src = Pkt.i32(b, 12);
        int dst = Pkt.i32(b, 16);
        if (proto == Pkt.PROTO_TCP) handleTcp(b, ihl, len, src, dst);
        else if (proto == Pkt.PROTO_UDP) handleUdp(b, ihl, len, src, dst);
    }

    private void handleTcp(byte[] b, int t, int len, int src, int dst) {
        if (len - t < 20) return;
        int sp = Pkt.u16(b, t), dp = Pkt.u16(b, t + 2);
        int seq = Pkt.i32(b, t + 4), ack = Pkt.i32(b, t + 8);
        int doff = ((b[t + 12] & 0xff) >>> 4) * 4;
        if (doff < 20 || t + doff > len) return;
        int flags = b[t + 13] & 0x3f;
        int wnd = Pkt.u16(b, t + 14);
        int pOff = t + doff, pLen = len - pOff;

        FlowKey k = new FlowKey(src, sp, dst, dp);
        TcpSession s = tcp.get(k);
        if (s != null) {
            s.onSegment(b, flags, seq, ack, wnd, pOff, pLen);
            return;
        }
        if ((flags & TcpSession.RST) != 0) return;
        if ((flags & TcpSession.SYN) != 0 && (flags & TcpSession.ACK) == 0) {
            if (tcp.size() >= MAX_TCP) {
                sendRst(src, sp, dst, dp, flags, seq, ack, pLen);
                return;
            }
            s = new TcpSession(this, k, seq, parseMss(b, t + 20, doff - 20));
            tcp.put(k, s);
            s.start();
            return;
        }
        sendRst(src, sp, dst, dp, flags, seq, ack, pLen); // bilinmeyen akis
    }

    private static int parseMss(byte[] b, int off, int len) {
        int end = off + len;
        int p = off;
        while (p < end) {
            int kind = b[p] & 0xff;
            if (kind == 0) break;
            if (kind == 1) {
                p++;
                continue;
            }
            if (p + 1 >= end) break;
            int l = b[p + 1] & 0xff;
            if (l < 2) break;
            if (kind == 2 && l == 4 && p + 4 <= end) return Pkt.u16(b, p + 2);
            p += l;
        }
        return 0;
    }

    private void sendRst(int src, int sp, int dst, int dp, int flags, int seq, int ack, int pLen) {
        if ((flags & TcpSession.ACK) != 0) {
            emit(Pkt.tcp(dst, src, dp, sp, ack, 0, TcpSession.RST, 0, null, null, 0, 0));
        } else {
            int a = seq + pLen + ((flags & TcpSession.SYN) != 0 ? 1 : 0) + ((flags & TcpSession.FIN) != 0 ? 1 : 0);
            emit(Pkt.tcp(dst, src, dp, sp, 0, a, TcpSession.RST | TcpSession.ACK, 0, null, null, 0, 0));
        }
    }

    private void handleUdp(byte[] b, int t, int len, int src, int dst) {
        if (len - t < 8) return;
        int sp = Pkt.u16(b, t), dp = Pkt.u16(b, t + 2);
        int ulen = Pkt.u16(b, t + 4);
        int pOff = t + 8;
        int pLen = Math.min(ulen - 8, len - pOff);
        if (pLen < 0) return;

        if (dp == 53) {
            final byte[] q = Arrays.copyOfRange(b, pOff, pOff + pLen);
            final int fs = src, fsp = sp, fd = dst, fdp = dp;
            execute(new Runnable() {
                @Override
                public void run() {
                    handleDns(fs, fsp, fd, fdp, q);
                }
            });
            return;
        }
        if (dp == 443 && strategy.blockQuic) {
            quicDropped.incrementAndGet();
            return;
        }
        if ((dst >>> 28) == 0xE || dst == -1) return; // multicast / broadcast

        FlowKey k = new FlowKey(src, sp, dst, dp);
        UdpFlow f = udp.get(k);
        if (f == null) {
            if (udp.size() >= MAX_UDP) return;
            try {
                DatagramSocket ds = connector.udp(Pkt.inet(dst), dp);
                UdpFlow nf = new UdpFlow(this, k, ds);
                UdpFlow prev = udp.putIfAbsent(k, nf);
                if (prev != null) {
                    ds.close();
                    f = prev;
                } else {
                    f = nf;
                    f.start();
                }
            } catch (IOException e) {
                return;
            }
        }
        f.send(b, pOff, pLen);
    }

    private void handleDns(int src, int sp, int dst, int dp, byte[] q) {
        long t0 = System.nanoTime();
        int qEnd = Dns.questionEnd(q, q.length);
        String name = qEnd > 0 ? Dns.qname(q, qEnd) : "?";
        int qt = qEnd > 0 ? Dns.qtype(q, qEnd) : 0;
        byte[] resp;
        String status = "ok";
        try {
            resp = dns.resolve(q);
        } catch (IOException | RuntimeException e) {
            status = "ERROR " + e.getMessage();
            resp = qEnd > 0 ? Dns.emptyResponse(q, qEnd, 2) : null; // SERVFAIL
        }
        dnsQueries.incrementAndGet();
        if (qt != Dns.TYPE_AAAA) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log("DNS " + name + " (" + (qt == Dns.TYPE_A ? "A" : "type " + qt) + ") " + status + " " + ms + "ms");
        }
        if (resp == null) return;
        if (resp.length > 1472 && qEnd > 0) { // tunel MTU'suna sigmaz: TC bitiyle kes
            resp = Dns.emptyResponse(q, qEnd, 0);
            resp[2] |= 0x02;
        }
        emit(Pkt.udp(dst, src, dp, sp, resp, 0, resp.length));
    }
}
