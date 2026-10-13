// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Bolmenin hangi sitelere uygulanacagini secer (Linux surumundeki domain_filter ile ayni anlam). */
public final class DomainFilter {
    public enum Mode {
        /** Her siteye uygula. */
        ALL,
        /** Yalnizca listedeki sitelere (ve alt alan adlarina) uygula. */
        INCLUDE,
        /** Listedeki siteler (ve alt alan adlari) haric her siteye uygula. */
        EXCLUDE
    }

    public static final int MAX_DOMAINS = 200;
    public static final DomainFilter ALL = new DomainFilter(Mode.ALL, new String[0]);

    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{1,253}$)[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?)+$");

    public final Mode mode;
    private final String[] domains;

    public DomainFilter(Mode mode, String[] domains) {
        this.mode = mode;
        this.domains = domains.clone();
    }

    public int size() {
        return domains.length;
    }

    /**
     * Kullanicinin yazdigi listeyi temizler: virgul/bosluk/satir ile ayrilmis adlar; http(s):// onegi, yol, port ve
     * bastaki "*." ya da "." atilir. Gecersiz bir ad varsa o ad mesaj olarak IllegalArgumentException firlatir.
     */
    public static String[] parse(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            for (String raw : text.trim().split("[,;\\s]+")) {
                String d = raw.trim().toLowerCase(Locale.ROOT);
                if (d.isEmpty()) continue;
                if (d.startsWith("https://")) d = d.substring(8);
                else if (d.startsWith("http://")) d = d.substring(7);
                for (char stop : new char[]{'/', '?', '#'}) {
                    int i = d.indexOf(stop);
                    if (i >= 0) d = d.substring(0, i);
                }
                int colon = d.indexOf(':');
                if (colon >= 0) {
                    if (!d.substring(colon + 1).matches("[0-9]{1,5}")) throw new IllegalArgumentException(raw);
                    d = d.substring(0, colon);
                }
                while (d.startsWith("*.") || d.startsWith(".")) d = d.substring(d.startsWith("*.") ? 2 : 1);
                while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
                try {
                    d = java.net.IDN.toASCII(d); // "bankası.com.tr" -> "xn--bankas-...": SNI de bu bicimde gelir
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(raw);
                }
                if (!DOMAIN.matcher(d).matches()) throw new IllegalArgumentException(raw);
                if (!out.contains(d)) out.add(d);
                if (out.size() > MAX_DOMAINS) throw new IllegalArgumentException("more than " + MAX_DOMAINS);
            }
        }
        return out.toArray(new String[0]);
    }

    /** Bu ada bolme uygulanacak mi? Ad bilinmiyorsa (SNI yok) yalnizca "haric" modunda uygulanir. */
    public boolean applies(String name) {
        if (mode == Mode.ALL) return true;
        String host = normalize(name);
        if (host.isEmpty()) return mode == Mode.EXCLUDE;
        boolean hit = matches(host);
        return mode == Mode.INCLUDE ? hit : !hit;
    }

    private boolean matches(String host) {
        for (String d : domains) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    private static String normalize(String name) {
        if (name == null) return "";
        String h = name.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) return ""; // IPv6 adresi: alan adi degil
        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }
}
