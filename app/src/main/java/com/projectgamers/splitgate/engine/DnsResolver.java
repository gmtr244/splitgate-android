// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate.engine;

import java.io.IOException;

/** Ham DNS sorgusunu (UDP govdesi) alip ham cevap dondurur. */
public interface DnsResolver {
    byte[] resolve(byte[] query) throws IOException;
}
