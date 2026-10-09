package com.projectgamers.splitgate;

import java.util.Locale;

/** Bayt / hiz / sure bicimlendirme: B, KB, MB, GB. */
final class Fmt {
    private Fmt() {}

    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    static String bytes(long b) {
        if (b < 1024) return b + " B";
        double v = b;
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < UNITS.length - 1);
        String f = v < 10 ? "%.2f %s" : (v < 100 ? "%.1f %s" : "%.0f %s");
        return String.format(Locale.getDefault(), f, v, UNITS[i]);
    }

    static String rate(long bytesPerSec, String perSecond) {
        return bytes(Math.max(0, bytesPerSec)) + perSecond;
    }

    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
