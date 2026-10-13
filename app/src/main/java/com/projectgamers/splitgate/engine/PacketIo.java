// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate.engine;

import java.io.IOException;

/** TUN arayuzunun soyutlamasi: her read/write tam bir IP paketi tasir. */
public interface PacketIo {
    /** Bir paket okur; akis bittiyse -1. */
    int read(byte[] buf) throws IOException;

    void write(byte[] buf, int off, int len) throws IOException;

    /** Bekleyen read()'i de sonlandirmali. */
    void close();
}
