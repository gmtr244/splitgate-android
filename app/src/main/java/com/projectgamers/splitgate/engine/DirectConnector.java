package com.projectgamers.splitgate.engine;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class DirectConnector implements Connector {
    @Override
    public Socket tcp(InetAddress addr, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(addr, port), timeoutMs);
            return s;
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignore) {
            }
            throw e;
        }
    }

    @Override
    public DatagramSocket udp(InetAddress addr, int port) throws IOException {
        DatagramSocket ds = new DatagramSocket();
        ds.connect(addr, port);
        return ds;
    }
}
