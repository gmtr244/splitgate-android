package com.projectgamers.splitgate.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.security.SecureRandom;

/** Minik DNS mesaj yardimcilari. */
final class Dns {
    private Dns() {}

    private static final SecureRandom RNG = new SecureRandom();

    static final int TYPE_A = 1;
    static final int TYPE_AAAA = 28;

    /** Soru bolumunun bitis ofseti (qtype+qclass dahil); gecersizse -1. */
    static int questionEnd(byte[] q, int len) {
        if (len < 17) return -1;
        if (Pkt.u16(q, 4) != 1) return -1; // QDCOUNT
        int p = 12;
        while (p < len) {
            int l = q[p] & 0xff;
            if (l == 0) {
                p++;
                break;
            }
            if ((l & 0xc0) != 0) return -1; // sikistirma sorularda beklenmez
            p += 1 + l;
        }
        if (p + 4 > len) return -1;
        return p + 4;
    }

    static int qtype(byte[] q, int qEnd) {
        return Pkt.u16(q, qEnd - 4);
    }

    static String qname(byte[] q, int qEnd) {
        StringBuilder sb = new StringBuilder();
        int p = 12;
        while (p < qEnd - 4) {
            int l = q[p] & 0xff;
            if (l == 0) break;
            if (sb.length() > 0) sb.append('.');
            for (int i = 1; i <= l && p + i < qEnd; i++) sb.append((char) (q[p + i] & 0xff));
            p += 1 + l;
        }
        return sb.toString();
    }

    /** Sorunun kopyasini cevap olarak dondurur: cevap kaydi yok, verilen rcode ile. */
    static byte[] emptyResponse(byte[] q, int qEnd, int rcode) {
        byte[] r = Arrays.copyOf(q, qEnd);
        int rd = q[2] & 0x01;
        r[2] = (byte) (0x80 | rd);       // QR=1, opcode=0, RD kopyalanir
        r[3] = (byte) (0x80 | rcode);    // RA=1 + rcode
        Pkt.p16(r, 4, 1);                // QDCOUNT
        Pkt.p16(r, 6, 0);                // ANCOUNT
        Pkt.p16(r, 8, 0);                // NSCOUNT
        Pkt.p16(r, 10, 0);               // ARCOUNT
        return r;
    }

    /** Tek soruluk standart sorgu (RD=1) olusturur. */
    static byte[] buildQuery(String host, int type) {
        byte[] name = host.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] q = new byte[12 + name.length + 2 + 4];
        int id = RNG.nextInt(0x10000);
        Pkt.p16(q, 0, id);
        Pkt.p16(q, 2, 0x0100);
        Pkt.p16(q, 4, 1);
        int p = 12;
        int labelStart = 0;
        for (int i = 0; i <= name.length; i++) {
            if (i == name.length || name[i] == '.') {
                int len = i - labelStart;
                q[p++] = (byte) len;
                System.arraycopy(name, labelStart, q, p, len);
                p += len;
                labelStart = i + 1;
            }
        }
        q[p++] = 0;
        Pkt.p16(q, p, type);
        Pkt.p16(q, p + 2, 1);
        return Arrays.copyOf(q, p + 4);
    }

    private static int skipName(byte[] r, int p) {
        while (p < r.length) {
            int l = r[p] & 0xff;
            if (l == 0) return p + 1;
            if ((l & 0xc0) == 0xc0) return p + 2;
            p += 1 + l;
        }
        return -1;
    }

    /** Cevaptaki A kayitlarinin IPv4 adresleri (noktali). Hata varsa bos liste. */
    static List<String> answersA(byte[] r) {
        List<String> out = new ArrayList<>();
        if (r.length < 12 || (r[3] & 0x0f) != 0) return out;
        int qd = Pkt.u16(r, 4);
        int an = Pkt.u16(r, 6);
        int p = 12;
        for (int i = 0; i < qd; i++) {
            p = skipName(r, p);
            if (p < 0) return out;
            p += 4;
        }
        for (int i = 0; i < an; i++) {
            p = skipName(r, p);
            if (p < 0 || p + 10 > r.length) return out;
            int type = Pkt.u16(r, p);
            int rdlen = Pkt.u16(r, p + 8);
            p += 10;
            if (p + rdlen > r.length) return out;
            if (type == TYPE_A && rdlen == 4) {
                out.add((r[p] & 0xff) + "." + (r[p + 1] & 0xff) + "." + (r[p + 2] & 0xff) + "." + (r[p + 3] & 0xff));
            }
            p += rdlen;
        }
        return out;
    }
}
