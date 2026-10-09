package com.projectgamers.splitgate.engine;

/** TLS ClientHello icinden SNI (server_name) konumunu bulur. */
final class TlsSni {
    private TlsSni() {}

    static final class Hello {
        int recLen;       // TLS kaydinin (record) payload uzunlugu
        int sniOff = -1;  // SNI host adinin d[] icindeki baslangici
        int sniLen;       // host adi uzunlugu
        int extOff = -1;  // server_name uzantisinin (tip alani) baslangici
    }

    static boolean looksLikeTlsRecord(byte[] d, int len) {
        return len >= 5 && d[0] == 0x16 && d[1] == 3 && (d[2] & 0xff) <= 4;
    }

    /** ClientHello degilse null; ClientHello ise SNI bulunamasa bile Hello doner (sniOff = -1). */
    static Hello parse(byte[] d, int len) {
        if (len < 6 || !looksLikeTlsRecord(d, len)) return null;
        if (d[5] != 1) return null; // handshake tipi: ClientHello
        Hello h = new Hello();
        h.recLen = Pkt.u16(d, 3);
        int p = 5 + 4;      // record basligi (5) + handshake basligi (4)
        p += 2 + 32;        // surum + random
        if (p + 1 > len) return h;
        p += 1 + (d[p] & 0xff);                 // session id
        if (p + 2 > len) return h;
        p += 2 + Pkt.u16(d, p);                 // cipher suites
        if (p + 1 > len) return h;
        p += 1 + (d[p] & 0xff);                 // compression methods
        if (p + 2 > len) return h;
        int extLen = Pkt.u16(d, p);
        p += 2;
        int end = Math.min(len, p + extLen);
        while (p + 4 <= end) {
            int type = Pkt.u16(d, p);
            int el = Pkt.u16(d, p + 2);
            p += 4;
            if (type == 0) { // server_name
                h.extOff = p - 4;
                if (p + 5 <= end && d[p + 2] == 0) {
                    int nl = Pkt.u16(d, p + 3);
                    int off = p + 5;
                    if (off + nl <= end) {
                        h.sniOff = off;
                        h.sniLen = nl;
                    }
                }
                return h;
            }
            p += el;
        }
        return h;
    }
}
