package com.projectgamers.splitgate.engine;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

/** IPv4 / TCP / UDP paket okuma-yazma yardimcilari. Android'e bagimli degil. */
final class Pkt {
    private Pkt() {}

    static final int PROTO_TCP = 6;
    static final int PROTO_UDP = 17;

    private static final AtomicInteger IP_ID = new AtomicInteger((int) (System.nanoTime() & 0xffff));

    static int u8(byte[] b, int i) { return b[i] & 0xff; }

    static int u16(byte[] b, int i) { return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff); }

    static int i32(byte[] b, int i) {
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    static void p16(byte[] b, int i, int v) {
        b[i] = (byte) (v >> 8);
        b[i + 1] = (byte) v;
    }

    static void p32(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 24);
        b[i + 1] = (byte) (v >>> 16);
        b[i + 2] = (byte) (v >>> 8);
        b[i + 3] = (byte) v;
    }

    private static long sum(byte[] b, int off, int len, long acc) {
        while (len > 1) {
            acc += ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
            off += 2;
            len -= 2;
        }
        if (len > 0) acc += (b[off] & 0xff) << 8;
        return acc;
    }

    private static int fold(long acc) {
        while ((acc >>> 16) != 0) acc = (acc & 0xffff) + (acc >>> 16);
        return (int) acc;
    }

    static int ipChecksum(byte[] b, int off, int len) {
        return ~fold(sum(b, off, len, 0)) & 0xffff;
    }

    /** TCP/UDP checksum'i (pseudo-header dahil). Checksum alani sifirlanmis olmali. */
    static int l4Checksum(byte[] b, int off, int len, int srcIp, int dstIp, int proto) {
        long acc = (srcIp >>> 16) + (srcIp & 0xffff) + (dstIp >>> 16) + (dstIp & 0xffff) + proto + len;
        acc = sum(b, off, len, acc);
        return ~fold(acc) & 0xffff;
    }

    private static void ipHeader(byte[] p, int total, int proto, int srcIp, int dstIp) {
        p[0] = 0x45;
        p16(p, 2, total);
        p16(p, 4, IP_ID.incrementAndGet() & 0xffff);
        p16(p, 6, 0x4000); // DF
        p[8] = 64;
        p[9] = (byte) proto;
        p32(p, 12, srcIp);
        p32(p, 16, dstIp);
        p16(p, 10, ipChecksum(p, 0, 20));
    }

    static byte[] tcp(int srcIp, int dstIp, int srcPort, int dstPort, int seq, int ack, int flags, int wnd,
                      byte[] opts, byte[] data, int dOff, int dLen) {
        int optLen = opts == null ? 0 : ((opts.length + 3) & ~3);
        int tcpLen = 20 + optLen + dLen;
        byte[] p = new byte[20 + tcpLen];
        ipHeader(p, p.length, PROTO_TCP, srcIp, dstIp);
        int t = 20;
        p16(p, t, srcPort);
        p16(p, t + 2, dstPort);
        p32(p, t + 4, seq);
        p32(p, t + 8, ack);
        p[t + 12] = (byte) (((20 + optLen) / 4) << 4);
        p[t + 13] = (byte) flags;
        p16(p, t + 14, wnd);
        if (opts != null) System.arraycopy(opts, 0, p, t + 20, opts.length);
        if (dLen > 0) System.arraycopy(data, dOff, p, t + 20 + optLen, dLen);
        p16(p, t + 16, l4Checksum(p, t, tcpLen, srcIp, dstIp, PROTO_TCP));
        return p;
    }

    static byte[] udp(int srcIp, int dstIp, int srcPort, int dstPort, byte[] data, int dOff, int dLen) {
        int udpLen = 8 + dLen;
        byte[] p = new byte[20 + udpLen];
        ipHeader(p, p.length, PROTO_UDP, srcIp, dstIp);
        int t = 20;
        p16(p, t, srcPort);
        p16(p, t + 2, dstPort);
        p16(p, t + 4, udpLen);
        if (dLen > 0) System.arraycopy(data, dOff, p, t + 8, dLen);
        int c = l4Checksum(p, t, udpLen, srcIp, dstIp, PROTO_UDP);
        p16(p, t + 6, c == 0 ? 0xffff : c);
        return p;
    }

    static int ip(String s) {
        try {
            return i32(InetAddress.getByName(s).getAddress(), 0);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(s);
        }
    }

    static InetAddress inet(int ip) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) (ip >>> 24), (byte) (ip >>> 16), (byte) (ip >>> 8), (byte) ip});
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static String ipStr(int ip) {
        return (ip >>> 24) + "." + ((ip >>> 16) & 0xff) + "." + ((ip >>> 8) & 0xff) + "." + (ip & 0xff);
    }
}
