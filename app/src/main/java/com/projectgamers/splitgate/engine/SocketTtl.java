// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate.engine;

import java.net.Socket;

/** Bir TCP soketinin giden paketlerindeki TTL'i degistirir. -1 sistem varsayilanina dondurur. */
public interface SocketTtl {
    boolean set(Socket s, int ttl);
}
