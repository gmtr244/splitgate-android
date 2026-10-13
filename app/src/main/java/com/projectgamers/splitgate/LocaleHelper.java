// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/** Uygulama ici dil secimi: varsayilan Ingilizce, secim SharedPreferences'ta saklanir. */
final class LocaleHelper {
    private LocaleHelper() {}

    static final String KEY_LANG = "lang";
    static final String[] CODES = {"en", "tr"};
    static final String[] NAMES = {"English", "Türkçe"};

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(BypassVpnService.PREFS, Context.MODE_PRIVATE);
    }

    private static int indexOf(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return i;
        return -1;
    }

    /** Kayitli dil kodu; hic secilmediyse (ilk acilis) ya da gecersizse null. */
    static String saved(Context c) {
        String s = prefs(c).getString(KEY_LANG, null);
        return indexOf(s) >= 0 ? s : null;
    }

    static String code(Context c) {
        String s = saved(c);
        return s != null ? s : CODES[0];
    }

    static String nameOf(String code) {
        int i = indexOf(code);
        return NAMES[i >= 0 ? i : 0];
    }

    static void save(Context c, String code) {
        if (indexOf(code) >= 0) prefs(c).edit().putString(KEY_LANG, code).apply();
    }

    static Context wrap(Context base) {
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        Locale.setDefault(new Locale(code(base)));
        cfg.setLocale(new Locale(code(base)));
        return base.createConfigurationContext(cfg);
    }
}
