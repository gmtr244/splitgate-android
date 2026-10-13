// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate.engine;

import java.util.Locale;

/**
 * Bankacilik ve devlet uygulamalarini tahmin eder; bunlar genellikle "VPN acik" diye girisi reddeder. Yalnizca ONERI icindir:
 * kullanici listeyi gorup onaylar, hicbir uygulama kendiliginden haric tutulmaz.
 */
public final class AppHints {
    private AppHints() {}

    /** Paket adinda ya da etikette gecmesi yeterli olan uzun anahtar kelimeler. */
    private static final String[] LONG = {
            "bank", "banka", "edevlet", "e-devlet", "gov.tr", "turkiye.gov", "finans", "ziraat", "garanti", "akbank",
            "isbank", "iscep", "halkbank", "vakifbank", "denizbank", "enpara", "papara", "ininal", "tosla", "fibabanka",
            "kuveytturk", "albaraka", "odeabank", "sekerbank", "anadolubank", "paycell", "uyap", "mebbis", "tcmb",
            "yapikredi", "yapı kredi", "yapi kredi", "cepsubesi", "mobilsube", "mobil şube", "mobil sube",
    };

    /** Yanlis eslesmeyi onlemek icin yalnizca etiketteki TAM kelime olarak aranan kisa kelimeler. */
    private static final String[] SHORT_WORDS = {"teb", "ing", "qnb", "sgk", "hes", "ptt", "kredi"};

    public static boolean looksLikeBankOrGov(String packageName, String label) {
        String p = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        String l = label == null ? "" : label.toLowerCase(Locale.forLanguageTag("tr-TR"));
        String l2 = label == null ? "" : label.toLowerCase(Locale.ROOT);
        for (String k : LONG) {
            if (p.contains(k) || l.contains(k) || l2.contains(k)) return true;
        }
        // Turkce kuralla "ING" -> "ing" degil "\u0131ng" olur; bu yuzden kelimeleri iki yazim bicimiyle de karsilastir.
        for (String text : new String[]{l, l2}) {
            for (String w : text.split("[^\\p{L}\\p{N}]+")) {
                for (String k : SHORT_WORDS) {
                    if (w.equals(k)) return true;
                }
            }
        }
        return false;
    }
}
