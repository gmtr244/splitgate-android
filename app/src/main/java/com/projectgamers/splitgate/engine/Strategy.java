// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate.engine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Baglantinin ilk veri parcasini (TLS ClientHello / HTTP istegi) DPI'in kolayca
 * okuyamayacagi sekilde parcalara boler. Her parca ayri bir TCP yazmasi olarak gonderilir.
 */
public final class Strategy {
    public enum Mode {
        /** Hicbir sey bolme, sadece DNS (DoH) kullan. */
        OFF,
        /** Konum belirteclerine (positions) gore bol; oob ise ilk parcadan sonra 1 bayt urgent gonder. */
        POSITIONS,
        /**
         * TLS + TCP: ClientHello ilk konum belirtecinden iki TLS kaydina bolunur, kayit siniri ayni zamanda
         * TCP siniridir. Kayit bolunemezse (eksik kayit vb.) ayni noktadan yalnizca TCP bolmesi yapilir.
         */
        TLS_RECORD
    }

    public volatile Mode mode = Mode.POSITIONS;
    /** 1-4 haneli sayi (bayt) ya da: method, host, sni, hostmid, midsld, sniend, sniext. */
    public volatile String[] positions = {"1", "midsld"};
    public volatile boolean oob = false;
    /** Ilk parca dusuk TTL ile gider ve yeniden iletilir; sunucuya parcalar sirasiz ulasir. */
    public volatile boolean disorder = false;
    /** UDP 443 (QUIC) paketlerini dusur; tarayicilar TCP+TLS'e duser ve orada bolebiliriz. */
    public volatile boolean blockQuic = true;
    /** Parcalar arasi bekleme (ms); parcalarin ayri paket olmasina yardimci olur. */
    public volatile int splitDelayMs = 1;

    public static final class Plan {
        public final List<byte[]> pieces;
        public final String kind;
        public final String host;
        public final boolean oob;
        public final boolean disorder;
        public final int delayMs;

        Plan(List<byte[]> pieces, String kind, String host, boolean oob, boolean disorder, int delayMs) {
            this.pieces = pieces;
            this.kind = kind;
            this.host = host;
            this.oob = oob;
            this.disorder = disorder;
            this.delayMs = delayMs;
        }

        static Plan single(byte[] d, String kind, String host) {
            return new Plan(Collections.singletonList(d), kind, host, false, false, 0);
        }
    }

    /** Bolme noktasini bulmak icin ilk veriden cikarilan bilgi. */
    private static final class Target {
        boolean tls;
        String name;
        int start = -1;
        int end = -1;
        int extStart = -1;
        int methodEnd = -1;
    }

    /** Ilk parcayi planlamadan once kac bayta ihtiyac var (tam TLS kaydi). 0 = bekleme. */
    int bytesWanted(byte[] first) {
        if (mode == Mode.OFF) return 0;
        if (TlsSni.looksLikeTlsRecord(first, first.length)) return 5 + Pkt.u16(first, 3);
        return 0;
    }

    private static Target targetOf(byte[] d) {
        TlsSni.Hello h = TlsSni.parse(d, d.length);
        if (h != null) {
            Target t = new Target();
            t.tls = true;
            t.extStart = h.extOff;
            if (h.sniOff >= 0) {
                t.start = h.sniOff;
                t.end = h.sniOff + h.sniLen;
                t.name = new String(d, h.sniOff, h.sniLen, StandardCharsets.ISO_8859_1);
            }
            return t;
        }
        int[] hh = findHttpHost(d);
        if (hh != null) {
            Target t = new Target();
            t.start = hh[0];
            t.end = hh[0] + hh[1];
            t.name = new String(d, hh[0], hh[1], StandardCharsets.ISO_8859_1);
            for (int i = 0; i < d.length && i < 12; i++) {
                if (d[i] == ' ') {
                    t.methodEnd = i;
                    break;
                }
            }
            return t;
        }
        return null;
    }

    private static final String[] SLD_PREFIXES = {"com", "net", "org", "edu", "gov", "co", "gen", "web", "info", "biz", "k12", "av", "bel"};

    /** Ikinci seviye alan adinin (ornegin "discord") orta noktasinin ofseti; yoksa -1. */
    private static int sldMid(Target t) {
        if (t.start < 0 || t.name == null || t.name.isEmpty()) return -1;
        String name = t.name;
        int colon = name.lastIndexOf(':');
        if (colon > 0 && name.indexOf(']') < 0) name = name.substring(0, colon);
        String[] labels = name.split("\\.");
        int idx = labels.length - 2;
        if (labels.length >= 3 && labels[labels.length - 1].length() == 2) {
            String prev = labels[labels.length - 2];
            for (String x : SLD_PREFIXES) {
                if (x.equals(prev)) {
                    idx = labels.length - 3;
                    break;
                }
            }
        }
        if (idx < 0) idx = 0;
        int labelStart = t.start;
        for (int i = 0; i < idx; i++) labelStart += labels[i].length() + 1;
        return labelStart + Math.max(1, labels[idx].length() / 2);
    }

    /** Belirteclerin (0, len) araliginda sirali, tekil ofsetlere cevrilmis hali. */
    private static int[] resolvePositions(String[] specs, int len, Target t) {
        TreeSet<Integer> out = new TreeSet<>();
        for (String spec : specs) {
            int pos = -1;
            if (!spec.isEmpty() && spec.length() <= 4 && Character.isDigit(spec.charAt(0))) {
                try {
                    pos = Integer.parseInt(spec);
                } catch (NumberFormatException e) {
                    pos = -1;
                }
            } else if (t != null) {
                switch (spec) {
                    case "sni":
                    case "host":
                        if (t.start >= 0) pos = t.start;
                        break;
                    case "hostmid":
                        if (t.start >= 0) pos = t.start + Math.max(1, (t.end - t.start) / 2);
                        break;
                    case "sniend":
                        if (t.end >= 0) pos = t.end;
                        break;
                    case "sniext":
                        if (t.extStart >= 0) pos = t.extStart;
                        break;
                    case "midsld":
                        pos = sldMid(t);
                        break;
                    case "method":
                        if (t.methodEnd >= 0) pos = t.methodEnd;
                        break;
                    default:
                        break;
                }
            }
            if (pos > 0 && pos < len) out.add(pos);
        }
        int[] r = new int[out.size()];
        int i = 0;
        for (int v : out) r[i++] = v;
        return r;
    }

    private static List<byte[]> splitAt(byte[] d, int[] pos) {
        List<byte[]> out = new ArrayList<>(pos.length + 1);
        int prev = 0;
        for (int p : pos) {
            if (p > prev) {
                out.add(Arrays.copyOfRange(d, prev, p));
                prev = p;
            }
        }
        out.add(Arrays.copyOfRange(d, prev, d.length));
        return out;
    }

    private static String join(int[] pos) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pos.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(pos[i]);
        }
        return sb.toString();
    }

    synchronized Plan plan(byte[] d) {
        Target t = targetOf(d);
        String host = t != null ? t.name : null;
        Mode m = mode;
        if (m == Mode.OFF) return Plan.single(d, "splitting off", host);
        if (t == null) return Plan.single(d, "not split", null);
        String proto = t.tls ? "TLS" : "HTTP";
        int delay = splitDelayMs;
        boolean dis = disorder;
        String disTag = dis ? " disorder" : "";

        if (m == Mode.TLS_RECORD) {
            String[] spec = positions.length > 0 ? new String[]{positions[0]} : new String[]{"hostmid"};
            int[] cut = resolvePositions(spec, d.length, t);
            if (cut.length == 0) return Plan.single(d, proto + " (no split point found)", host);
            TlsSni.Hello h = t.tls ? TlsSni.parse(d, d.length) : null;
            int recEnd = h != null ? 5 + h.recLen : -1;
            if (h == null || cut[0] <= 5 || cut[0] >= recEnd || d.length < recEnd) {
                return new Plan(splitAt(d, cut), proto + disTag + " split @" + cut[0] + " (TLS record not split)", host, false, dis, delay);
            }
            List<byte[]> out = new ArrayList<>(2);
            out.add(record(d, 5, cut[0]));
            byte[] second = record(d, cut[0], recEnd);
            if (recEnd < d.length) {
                byte[] merged = new byte[second.length + (d.length - recEnd)];
                System.arraycopy(second, 0, merged, 0, second.length);
                System.arraycopy(d, recEnd, merged, second.length, d.length - recEnd);
                second = merged;
            }
            out.add(second);
            return new Plan(out, "TLS record + TCP" + disTag + " split @" + cut[0], host, false, dis, delay);
        }

        int[] pos = resolvePositions(positions, d.length, t);
        if (pos.length == 0) return Plan.single(d, proto + " (no split point found)", host);
        List<byte[]> pieces = splitAt(d, pos);
        boolean useOob = oob && pieces.size() > 1;
        return new Plan(pieces, proto + (useOob ? " OOB" : "") + disTag + " split @" + join(pos), host, useOob, dis, delay);
    }

    /** d[from, to) govdesini, d'nin TLS basligi (tip+surum) ile yeni bir TLS record olarak paketler. */
    private static byte[] record(byte[] d, int from, int to) {
        int n = to - from;
        byte[] r = new byte[5 + n];
        r[0] = d[0];
        r[1] = d[1];
        r[2] = d[2];
        Pkt.p16(r, 3, n);
        System.arraycopy(d, from, r, 5, n);
        return r;
    }

    private static final String[] METHODS = {"GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH "};

    /** HTTP istegindeki Host degerinin {baslangic, uzunluk} bilgisi; yoksa null. */
    static int[] findHttpHost(byte[] d) {
        boolean http = false;
        for (String m : METHODS) {
            if (startsWith(d, m)) {
                http = true;
                break;
            }
        }
        if (!http) return null;
        int lim = Math.min(d.length, 4096);
        for (int i = 0; i + 7 < lim; i++) {
            if (d[i] == '\r' && d[i + 1] == '\n' && lower(d[i + 2]) == 'h' && lower(d[i + 3]) == 'o'
                    && lower(d[i + 4]) == 's' && lower(d[i + 5]) == 't' && d[i + 6] == ':') {
                int s = i + 7;
                while (s < lim && (d[s] == ' ' || d[s] == '\t')) s++;
                int e = s;
                while (e < lim && d[e] != '\r' && d[e] != '\n') e++;
                if (e > s) return new int[]{s, e - s};
                return null;
            }
        }
        return null;
    }

    private static int lower(byte b) {
        return (b >= 'A' && b <= 'Z') ? b + 32 : b;
    }

    private static boolean startsWith(byte[] d, String s) {
        if (d.length < s.length()) return false;
        for (int i = 0; i < s.length(); i++) if (d[i] != (byte) s.charAt(i)) return false;
        return true;
    }
}
