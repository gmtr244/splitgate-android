// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/** Planlanmis ilk veriyi parca parca gonderir (motor ve sinama ayni yolu kullanir). */
final class Sender {
    /** Ilk router'da olur; DPI ilk parcayi gormez, cekirdek onu yeniden iletince sunucuya sirasiz ulasir. */
    static final int DISORDER_TTL = 1;

    private Sender() {}

    /** Dondurulen metin plan.kind'a eklenecek not; yoksa bos. */
    static String send(Socket s, OutputStream out, Strategy.Plan plan, SocketTtl ttl)
            throws IOException, InterruptedException {
        String note = "";
        int n = plan.pieces.size();
        for (int i = 0; i < n; i++) {
            boolean lowered = false;
            if (i == 0 && plan.disorder && n > 1) {
                lowered = ttl != null && ttl.set(s, DISORDER_TTL);
                if (!lowered) note = " (disorder unavailable)";
            }
            try {
                out.write(plan.pieces.get(i));
                out.flush();
            } finally {
                if (lowered && !ttl.set(s, -1)) throw new IOException("could not restore TTL");
            }
            if (i == 0 && plan.oob && n > 1) {
                try {
                    s.sendUrgentData(0);
                } catch (IOException e) {
                    note = " (OOB failed: " + e.getMessage() + ")";
                }
            }
            if (i + 1 < n && plan.delayMs > 0) Thread.sleep(plan.delayMs);
        }
        return note;
    }
}
