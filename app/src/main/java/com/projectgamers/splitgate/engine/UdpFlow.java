package com.projectgamers.splitgate.engine;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;

/** Tek bir UDP akisi: istemci -> gercek hedef, cevaplar tunele geri yazilir. */
final class UdpFlow {
    private static final int IDLE_MS = 60_000;

    private final TunEngine eng;
    private final FlowKey key;
    private final DatagramSocket sock;
    private volatile long lastActive = System.currentTimeMillis();
    private volatile boolean closed;

    UdpFlow(TunEngine eng, FlowKey key, DatagramSocket sock) {
        this.eng = eng;
        this.key = key;
        this.sock = sock;
    }

    void start() {
        eng.execute(this::readLoop);
    }

    void send(byte[] b, int off, int len) {
        lastActive = System.currentTimeMillis();
        try {
            sock.send(new DatagramPacket(b, off, len));
            eng.bytesUp.addAndGet(len);
        } catch (IOException e) {
            close();
        }
    }

    private void readLoop() {
        byte[] buf = new byte[65535];
        try {
            sock.setSoTimeout(5000);
            while (!closed) {
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(dp);
                } catch (SocketTimeoutException e) {
                    if (System.currentTimeMillis() - lastActive > IDLE_MS) break;
                    continue;
                }
                lastActive = System.currentTimeMillis();
                int n = dp.getLength();
                if (n > 1472) continue; // tunel MTU'suna sigmayan paketi atla (parcalama yok)
                eng.bytesDown.addAndGet(n);
                eng.emit(Pkt.udp(key.dstIp, key.srcIp, key.dstPort, key.srcPort, buf, 0, n));
            }
        } catch (IOException ignore) {
            // soket kapandi
        } finally {
            close();
        }
    }

    boolean idle(long now) {
        return now - lastActive > IDLE_MS;
    }

    void close() {
        if (closed) return;
        closed = true;
        sock.close();
        eng.removeUdp(key, this);
    }
}
