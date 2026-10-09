package com.projectgamers.splitgate.engine;

import java.io.IOException;

/** Ham DNS sorgusunu (UDP govdesi) alip ham cevap dondurur. */
public interface DnsResolver {
    byte[] resolve(byte[] query) throws IOException;
}
