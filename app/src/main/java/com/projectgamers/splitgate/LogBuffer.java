package com.projectgamers.splitgate;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Arayuzde gosterilen, bellekte tutulan son log satirlari. */
final class LogBuffer {
    private LogBuffer() {}

    private static final int MAX = 400;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static int version;

    static synchronized void add(String msg) {
        if (LINES.size() >= MAX) LINES.pollFirst();
        LINES.addLast(FMT.format(new Date()) + "  " + msg);
        version++;
    }

    static synchronized int version() {
        return version;
    }

    static synchronized String dump() {
        return dump(Integer.MAX_VALUE);
    }

    /** Son maxLines satiri dondurur. */
    static synchronized String dump(int maxLines) {
        StringBuilder sb = new StringBuilder();
        int skip = Math.max(0, LINES.size() - maxLines);
        int i = 0;
        for (String l : LINES) {
            if (i++ < skip) continue;
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    static synchronized void clear() {
        LINES.clear();
        version++;
    }
}
