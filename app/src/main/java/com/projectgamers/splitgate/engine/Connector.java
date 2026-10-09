package com.projectgamers.splitgate.engine;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;

/** Gercek dunyaya cikis. Android'de uygulama VPN disinda tutuldugu icin duz soket yeterli. */
public interface Connector {
    Socket tcp(InetAddress addr, int port, int timeoutMs) throws IOException;

    /** addr:port'a baglanmis (connect) bir UDP soketi. */
    DatagramSocket udp(InetAddress addr, int port) throws IOException;
}
