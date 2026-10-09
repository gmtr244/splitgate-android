package com.projectgamers.splitgate.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

/**
 * Motorun JVM uzerinde kendi kendine testi: sahte bir "telefon" TCP/UDP istemcisi TUN'a paket
 * yazar, motor gercek loopback soketlerine cikar. Android gerektirmez.
 */
public class EngineSelfTest {
    static int fails = 0;
    static int passes = 0;

    static void check(boolean ok, String msg) {
        if (ok) {
            passes++;
            System.out.println("ok    " + msg);
        } else {
            fails++;
            System.out.println("FAIL  " + msg);
        }
    }

    // ------------------------------------------------------------------ sahte TUN

    static final byte[] POISON = new byte[0];

    static class FakeTun implements PacketIo {
        final BlockingQueue<byte[]> toEngine = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> fromEngine = new LinkedBlockingQueue<>();

        @Override
        public int read(byte[] buf) throws IOException {
            try {
                byte[] p = toEngine.take();
                if (p == POISON) return -1;
                System.arraycopy(p, 0, buf, 0, p.length);
                return p.length;
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            fromEngine.add(Arrays.copyOfRange(buf, off, off + len));
        }

        @Override
        public void close() {
            toEngine.add(POISON);
        }

        void send(byte[] p) {
            toEngine.add(p);
        }

        P next(long ms) throws InterruptedException {
            byte[] b = fromEngine.poll(ms, TimeUnit.MILLISECONDS);
            return b == null ? null : P.parse(b);
        }
    }

    // ------------------------------------------------------------------ bagimsiz paket cozucu + checksum dogrulayici

    static class P {
        int proto, src, dst, sp, dp, seq, ack, flags, wnd;
        byte[] payload;
        boolean ipOk, l4Ok;

        static int u16(byte[] b, int i) {
            return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
        }

        // Bagimsiz (motordan farkli yazilmis) bir "one's complement" toplami
        static boolean verify(byte[] b, int off, int len, int pseudo) {
            long s = pseudo;
            for (int i = 0; i + 1 < len; i += 2) s += u16(b, off + i);
            if ((len & 1) == 1) s += (b[off + len - 1] & 0xff) << 8;
            while (s > 0xffff) s = (s & 0xffff) + (s >> 16);
            return s == 0xffff;
        }

        static P parse(byte[] b) {
            P p = new P();
            int ihl = (b[0] & 0x0f) * 4;
            p.ipOk = verify(b, 0, ihl, 0);
            p.proto = b[9] & 0xff;
            p.src = ByteBuffer.wrap(b, 12, 4).getInt();
            p.dst = ByteBuffer.wrap(b, 16, 4).getInt();
            int total = u16(b, 2);
            int l4len = total - ihl;
            int pseudo = (p.src >>> 16) + (p.src & 0xffff) + (p.dst >>> 16) + (p.dst & 0xffff) + p.proto + l4len;
            p.l4Ok = verify(b, ihl, l4len, pseudo);
            p.sp = u16(b, ihl);
            p.dp = u16(b, ihl + 2);
            if (p.proto == 6) {
                p.seq = ByteBuffer.wrap(b, ihl + 4, 4).getInt();
                p.ack = ByteBuffer.wrap(b, ihl + 8, 4).getInt();
                int doff = ((b[ihl + 12] & 0xff) >>> 4) * 4;
                p.flags = b[ihl + 13] & 0x3f;
                p.wnd = u16(b, ihl + 14);
                p.payload = Arrays.copyOfRange(b, ihl + doff, total);
            } else {
                p.payload = Arrays.copyOfRange(b, ihl + 8, total);
            }
            return p;
        }
    }

    static final int FIN = 1, SYN = 2, RST = 4, PSH = 8, ACK = 16;

    static byte[] tcpPkt(int sIp, int dIp, int sp, int dp, int seq, int ack, int flags, byte[] data, boolean mss) {
        byte[] opts = mss ? new byte[]{2, 4, (byte) 0x05, (byte) 0xb4} : null;
        return Pkt.tcp(sIp, dIp, sp, dp, seq, ack, flags, 65535, opts, data, 0, data == null ? 0 : data.length);
    }

    // ------------------------------------------------------------------ gercek ClientHello

    static byte[] realClientHello(String host) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, null, null);
        SSLEngine e = ctx.createSSLEngine(host, 443);
        e.setUseClientMode(true);
        SSLParameters sp = e.getSSLParameters();
        sp.setServerNames(Collections.singletonList(new SNIHostName(host)));
        e.setSSLParameters(sp);
        e.beginHandshake();
        ByteBuffer out = ByteBuffer.allocate(20000);
        e.wrap(ByteBuffer.allocate(0), out);
        out.flip();
        byte[] b = new byte[out.remaining()];
        out.get(b);
        return b;
    }

    // ------------------------------------------------------------------ testler

    static void testChecksums() {
        byte[] t = tcpPkt(Pkt.ip("1.2.3.4"), Pkt.ip("5.6.7.8"), 1111, 2222, 123456, 654321, SYN | ACK, "merhaba dunya!".getBytes(), true);
        P p = P.parse(t);
        check(p.ipOk && p.l4Ok, "TCP packet IP + TCP checksums are correct (including an odd number of bytes)");
        byte[] u = Pkt.udp(Pkt.ip("1.2.3.4"), Pkt.ip("5.6.7.8"), 5353, 53, new byte[]{1, 2, 3}, 0, 3);
        P q = P.parse(u);
        check(q.ipOk && q.l4Ok, "UDP packet IP + UDP checksums are correct");
    }

    static void testSni() throws Exception {
        byte[] h = realClientHello("discord.com");
        TlsSni.Hello x = TlsSni.parse(h, h.length);
        check(x != null && x.sniOff > 0, "real ClientHello (" + h.length + " bytes) parsed");
        check(x != null && new String(h, x.sniOff, x.sniLen).equals("discord.com"), "SNI = discord.com bulundu");
        check(x != null && x.recLen + 5 == h.length, "TLS record length is correct");
        check(TlsSni.parse("GET / HTTP/1.1\r\n".getBytes(), 16) == null, "HTTP request was not mistaken for a ClientHello");
        TlsSni.Hello trunc = TlsSni.parse(h, 40);
        check(trunc != null && trunc.sniOff < 0, "a truncated ClientHello was handled without blowing up");
    }

    static void testPlans() throws Exception {
        byte[] h = realClientHello("discord.com");
        TlsSni.Hello x = TlsSni.parse(h, h.length);
        Strategy s = new Strategy();

        Presets.apply(s, Presets.byId("hafif"));
        Strategy.Plan a = s.plan(h);
        check(a.pieces.size() == 2 && !a.oob && Arrays.equals(concat(a.pieces), h),
                "hafif: 2 pieces, no OOB, original ClientHello when joined");
        check(a.pieces.get(0).length == x.sniOff + 3, "hafif: cut is in the middle of 'discord' (dis|cord)");

        s.mode = Strategy.Mode.POSITIONS;
        s.positions = new String[]{"1", "midsld"};
        s.oob = false;
        Strategy.Plan t = s.plan(h);
        check(t.pieces.size() == 3 && !t.oob && t.pieces.get(0).length == 1
                        && t.pieces.get(0).length + t.pieces.get(1).length == x.sniOff + 3
                        && Arrays.equals(concat(t.pieces), h),
                "positions 1,midsld: 1 byte + middle of the domain, 3 pieces");

        Presets.apply(s, Presets.byId("oob"));
        Strategy.Plan o = s.plan(h);
        check(o.oob && o.pieces.size() == 3 && o.pieces.get(0).length == 1 && s.splitDelayMs == 2
                        && Arrays.equals(concat(o.pieces), h),
                "oob: 3 pieces (1 byte + middle of the domain), OOB flagged, 2 ms delay");

        Presets.apply(s, Presets.byId("sni"));
        Strategy.Plan q = s.plan(h);
        check(q.pieces.size() == 3 && q.pieces.get(0).length + q.pieces.get(1).length == x.sniOff,
                "sni: second cut is at the SNI start");

        Presets.apply(s, Presets.byId("agresif"));
        Strategy.Plan g = s.plan(h);
        check(g.pieces.size() == 6 && Arrays.equals(concat(g.pieces), h), "agresif: 6 pieces, original when joined");

        s.mode = Strategy.Mode.POSITIONS;
        s.positions = new String[]{"sniext"};
        Strategy.Plan e = s.plan(h);
        check(e.pieces.size() == 2 && e.pieces.get(0).length == x.extOff && x.extOff > 0, "sniext: at the start of the SNI extension");

        Presets.apply(s, Presets.byId("tlskayit"));
        Strategy.Plan b = s.plan(h);
        check(b.pieces.size() == 2 && !b.oob, "TLS_RECORD: 2 pieces");
        byte[] p1 = b.pieces.get(0), p2 = b.pieces.get(1);
        check(p1[0] == 0x16 && p2[0] == 0x16 && P.u16(p1, 3) == p1.length - 5 && P.u16(p2, 3) == p2.length - 5,
                "TLS_RECORD: every piece is a valid TLS record (header + length consistent)");
        byte[] body = new byte[(p1.length - 5) + (p2.length - 5)];
        System.arraycopy(p1, 5, body, 0, p1.length - 5);
        System.arraycopy(p2, 5, body, p1.length - 5, p2.length - 5);
        check(Arrays.equals(body, Arrays.copyOfRange(h, 5, h.length)), "TLS_RECORD: record bodies joined give the original handshake message");
        check(p1.length == x.sniOff + 5 && b.delayMs == 3,
                "TLS + TCP: record and TCP boundary in the middle of the whole SNI (disco|rd.com), 3 ms delay");
        byte[] partial = Arrays.copyOf(h, x.sniOff + 20);
        Strategy.Plan bp = s.plan(partial);
        check(bp.pieces.size() == 2 && bp.pieces.get(0).length == x.sniOff + 5 && Arrays.equals(concat(bp.pieces), partial),
                "TLS + TCP: incomplete ClientHello is still TCP-split at the same point instead of sent whole");
        byte[] httpReq = "GET / HTTP/1.1\r\nHost: example.org\r\n\r\n".getBytes();
        Strategy.Plan bh = s.plan(httpReq);
        check(bh.pieces.size() == 2 && bh.pieces.get(0).length == Strategy.findHttpHost(httpReq)[0] + 5
                        && Arrays.equals(concat(bh.pieces), httpReq),
                "TLS + TCP: plain HTTP Host is split in the middle");
        check(Presets.byId("tlskayit").label.equals("TLS + TCP split"), "TLS + TCP: preset is named 'TLS + TCP split' as in the old app");

        String badDelay = null;
        for (Presets.Preset pr : Presets.ALL) {
            Presets.apply(s, pr);
            Strategy.Plan pl = s.plan(h);
            if (pl.pieces.size() > 1 && pl.delayMs != pr.delayMs) badDelay = pr.id;
        }
        testPlanUnderLiveSwitch(h);
        check(badDelay == null, "every plan carries the delay of the preset it was made with" + (badDelay != null ? " (not " + badDelay + ")" : ""));

        Presets.apply(s, Presets.NONE);
        check(s.plan(h).pieces.size() == 1, "OFF: no splitting");

        Presets.apply(s, Presets.byId("hafif"));
        byte[] http = "GET /x HTTP/1.1\r\nUser-Agent: t\r\nHost: example.org\r\nAccept: */*\r\n\r\n".getBytes();
        Strategy.Plan c = s.plan(http);
        check(c.pieces.size() == 2 && Arrays.equals(concat(c.pieces), http) && "example.org".equals(c.host),
                "HTTP: Host value found and split in the middle");
        int[] hh = Strategy.findHttpHost(http);
        check(c.pieces.get(0).length == hh[0] + 3, "HTTP: cut is in the middle of 'example'");

        byte[] tr = "GET / HTTP/1.1\r\nHost: www.example.com.tr\r\n\r\n".getBytes();
        int[] th = Strategy.findHttpHost(tr);
        check(s.plan(tr).pieces.get(0).length == th[0] + 4 + 3, "midsld: the .com.tr suffix was skipped and the middle of 'example' found");

        s.mode = Strategy.Mode.POSITIONS;
        s.positions = new String[]{"method"};
        check(s.plan(http).pieces.get(0).length == 3, "method: split right after 'GET'");

        Presets.apply(s, Presets.byId("sni"));
        check(s.plan("random bytes".getBytes()).pieces.size() == 1, "unrecognized data was not split");
        check(Presets.byId("turkiye").id.equals("sni") && Presets.byId(null).id.equals("sni"),
                "removed or unknown saved preset falls back to sni");
    }

    /** Calisirken yontem degisirken her plan tek bir on ayarin plani olmali (iki ayarin karisimi degil). */
    static void testPlanUnderLiveSwitch(final byte[] h) throws Exception {
        final Strategy live = new Strategy();
        final Presets.Preset[] ps = Presets.ALL;
        final java.util.Set<String> valid = new java.util.HashSet<>();
        for (Presets.Preset p : ps) {
            Strategy one = new Strategy();
            Presets.apply(one, p);
            Strategy.Plan pl = one.plan(h);
            valid.add(Arrays.deepToString(pl.pieces.toArray()) + "|" + pl.oob + "|" + pl.delayMs);
        }
        Presets.apply(live, ps[ps.length - 1]);
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread sw = new Thread(new Runnable() {
            @Override
            public void run() {
                int i = 0;
                while (!stop.get()) Presets.apply(live, ps[i++ % ps.length]);
            }
        });
        sw.setDaemon(true);
        sw.start();
        int mixed = 0, n = 0;
        try {
            long end = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < end) {
                try {
                    Strategy.Plan pl = live.plan(h);
                    String k = pl.pieces.size() == 1 ? null : Arrays.deepToString(pl.pieces.toArray()) + "|" + pl.oob + "|" + pl.delayMs;
                    if (k != null && !valid.contains(k)) mixed++;
                } catch (RuntimeException e) {
                    mixed++;
                }
                n++;
            }
        } finally {
            stop.set(true);
            sw.join(2000);
        }
        check(mixed == 0 && n > 100, "live switching: " + n + " plans made while presets changed, none mixed two presets (" + mixed + ")");
    }

    /** Disorder: ilk parcadan once TTL dusurulmeli, ikinci parcadan once geri alinmali. */
    static void testDisorderSend() throws Exception {
        byte[] h = realClientHello("discord.com");
        final List<String> ev = Collections.synchronizedList(new ArrayList<String>());
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket c = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort());
        Socket srv = ss.accept();
        OutputStream rec = new OutputStream() {
            @Override
            public void write(int b) {
                ev.add("write1");
            }

            @Override
            public void write(byte[] b, int off, int len) {
                ev.add("write " + len);
            }
        };
        SocketTtl ttl = new SocketTtl() {
            @Override
            public boolean set(Socket s, int t) {
                ev.add("ttl " + t);
                return true;
            }
        };
        for (String id : new String[]{"disorder", "disordersni", "tlsdisorder"}) {
            ev.clear();
            Strategy st = new Strategy();
            Presets.apply(st, Presets.byId(id));
            Strategy.Plan pl = st.plan(h);
            String note = Sender.send(c, rec, pl, ttl);
            List<String> want = new ArrayList<>();
            want.add("ttl 1");
            want.add("write " + pl.pieces.get(0).length);
            want.add("ttl -1");
            for (int i = 1; i < pl.pieces.size(); i++) want.add("write " + pl.pieces.get(i).length);
            check(pl.disorder && pl.pieces.size() >= 2 && ev.equals(want) && note.isEmpty(),
                    "disorder [" + id + "]: TTL 1 only for the first piece, restored before the rest " + ev);
        }
        final List<String> ev2 = new ArrayList<>();
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int x) throws IOException {
                throw new IOException("broken");
            }

            @Override
            public void write(byte[] x, int off, int len) throws IOException {
                throw new IOException("broken");
            }
        };
        Strategy sb = new Strategy();
        Presets.apply(sb, Presets.byId("disorder"));
        try {
            Sender.send(c, broken, sb.plan(h), new SocketTtl() {
                @Override
                public boolean set(Socket s, int t) {
                    ev2.add("ttl " + t);
                    return true;
                }
            });
        } catch (IOException expected) {
        }
        check(ev2.equals(Arrays.asList("ttl 1", "ttl -1")), "disorder: the TTL is restored even when writing fails " + ev2);

        ev.clear();
        Strategy st = new Strategy();
        Presets.apply(st, Presets.byId("disorder"));
        Strategy.Plan pl = st.plan(h);
        String note = Sender.send(c, rec, pl, null);
        check(note.contains("disorder unavailable") && ev.size() == pl.pieces.size(),
                "disorder: without TTL control the pieces are still sent and the log says so");
        Presets.apply(st, Presets.byId("sni"));
        ev.clear();
        Sender.send(c, rec, st.plan(h), ttl);
        boolean touched = false;
        for (String e : ev) if (e.startsWith("ttl")) touched = true;
        check(!touched, "non-disorder methods never change the TTL");
        c.close();
        srv.close();
        ss.close();
    }

    static Probe.Result res(Presets.Preset p, String... errs) {
        Map<String, Probe.DomainResult> m = new java.util.LinkedHashMap<>();
        int ok = 0;
        for (int i = 0; i < errs.length; i++) {
            boolean good = errs[i] == null;
            if (good) ok++;
            m.put("d" + i + ".example", new Probe.DomainResult(good, good ? 50 : 0, errs[i]));
        }
        return new Probe.Result(p, ok, errs.length, ok > 0 ? 50 : -1, m);
    }

    static void testDiagnose() {
        Presets.Preset n = Presets.NONE, a = Presets.byId("sni"), b = Presets.byId("disorder");
        String R = Probe.ERR_RESET, T = Probe.ERR_TIMEOUT, C = Probe.ERR_CONNECT_TIMEOUT, D = Probe.ERR_DNS_PREFIX + "timeout";
        check(Probe.diagnose(Arrays.asList(res(n, R, R), res(a, null, R), res(b, R, R))) == Probe.Verdict.WORKS,
                "diagnose: one method opening a site means it works");
        check(Probe.diagnose(Arrays.asList(res(n, null, null), res(a, null, null))) == Probe.Verdict.NOT_BLOCKED,
                "diagnose: sites open without splitting means they are not blocked here");
        check(Probe.diagnose(Arrays.asList(res(n, D, D), res(a, D, D))) == Probe.Verdict.DNS_FAILED,
                "diagnose: every lookup failed means DNS (DoH) is the problem");
        check(Probe.diagnose(Arrays.asList(res(n, C, C), res(a, C, C), res(b, C, C))) == Probe.Verdict.IP_BLOCK,
                "diagnose: TCP never connects means an IP block");
        check(Probe.diagnose(Arrays.asList(res(n, R, T), res(a, R, T), res(b, T, R))) == Probe.Verdict.DPI_UNBEATEN,
                "diagnose: connects but resets/timeouts after the hello means the DPI is not beaten");
        check(Probe.diagnose(Arrays.asList(res(n, Probe.ERR_CERTIFICATE, Probe.ERR_CERTIFICATE), res(a, Probe.ERR_HOSTNAME, Probe.ERR_CERTIFICATE)))
                        == Probe.Verdict.INTERCEPTED, "diagnose: certificate errors everywhere means the connection is intercepted");
        check(Probe.diagnose(Arrays.asList(res(n, Probe.ERR_UNREACHABLE, Probe.ERR_UNREACHABLE))) == Probe.Verdict.NO_CONNECTION,
                "diagnose: network unreachable means no connection");
        check(Probe.diagnose(new ArrayList<Probe.Result>()) == Probe.Verdict.DNS_FAILED, "diagnose: no results at all is not reported as working");

        check(!Probe.bypasses(Arrays.asList(res(n, null, R, R), res(a, null, R, R)), res(a, null, R, R))
                        && Probe.best(Arrays.asList(res(n, null, R, R), res(a, null, R, R), res(b, null, T, R))) == null
                        && Probe.diagnose(Arrays.asList(res(n, null, R, R), res(a, null, R, R))) == Probe.Verdict.DPI_UNBEATEN,
                "a method that only opens a site that is not blocked anyway is NOT reported as working");
        check(Probe.bypasses(Arrays.asList(res(n, null, R, R), res(a, null, null, R)), res(a, null, null, R)),
                "a method that opens a site the plain connection cannot is reported as working");
        check(Probe.diagnose(Arrays.asList(res(n, C, C, R, R), res(a, C, C, R, T))) == Probe.Verdict.DPI_UNBEATEN,
                "diagnose: a tie between IP-block and DPI symptoms is not reported as an IP block");
        check(Probe.notBlocked(Arrays.asList(res(n, null, null, D))) && Probe.diagnose(Arrays.asList(res(n, null, null, D))) == Probe.Verdict.NOT_BLOCKED,
                "diagnose: a site whose DNS failed does not hide that the others are not blocked");

        check(Probe.describe(new java.net.SocketException("Connection reset")).equals(Probe.ERR_RESET)
                        && Probe.describe(new java.net.ConnectException("failed to connect: ECONNREFUSED (Connection refused)")).equals(Probe.ERR_REFUSED)
                        && Probe.describe(new java.net.ConnectException("Bağlantı reddedildi")).equals(Probe.ERR_REFUSED)
                        && Probe.describe(new java.net.ConnectException("connect failed: ENETUNREACH (Network is unreachable)")).equals(Probe.ERR_UNREACHABLE)
                        && Probe.describe(new java.net.SocketTimeoutException("Read timed out")).equals(Probe.ERR_TIMEOUT),
                "describe: reset / refused (also localized) / unreachable / timeout are classified");
    }

    static byte[] concat(List<byte[]> l) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (byte[] b : l) bo.write(b, 0, b.length);
        return bo.toByteArray();
    }

    /** Hedefi her zaman yerel test sunucusuna yonlendirir. */
    static class MapConnector implements Connector {
        final int tcpPort, udpPort;
        volatile InetAddress lastTcpDst;
        volatile int lastTcpPort;

        MapConnector(int tcpPort, int udpPort) {
            this.tcpPort = tcpPort;
            this.udpPort = udpPort;
        }

        @Override
        public Socket tcp(InetAddress addr, int port, int timeoutMs) throws IOException {
            lastTcpDst = addr;
            lastTcpPort = port;
            return new Socket(InetAddress.getLoopbackAddress(), tcpPort);
        }

        @Override
        public DatagramSocket udp(InetAddress addr, int port) throws IOException {
            DatagramSocket ds = new DatagramSocket();
            ds.connect(InetAddress.getLoopbackAddress(), udpPort);
            return ds;
        }
    }

    static class Env {
        FakeTun tun = new FakeTun();
        Strategy strategy = new Strategy();
        TunEngine engine;
        Thread thread;
        List<String> logs = Collections.synchronizedList(new ArrayList<String>());

        void start(DnsResolver dns, Connector c) {
            engine = new TunEngine(tun, strategy, dns, c, new EngineLog() {
                @Override
                public void log(String m) {
                    logs.add(m);
                }
            });
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    engine.run();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        void stop() throws InterruptedException {
            engine.stop();
            thread.join(3000);
        }
    }

    /** A whole TCP session: handshake, split ClientHello, 200 KB download, FIN, (optional) packet loss. */
    static void testTcpFlow(Presets.Preset mode, boolean loss) throws Exception {
        String tag = "TCP flow [" + mode.id + (loss ? ", with packet loss" : "") + "]: ";
        final byte[] hello = realClientHello("discord.com");
        final byte[] payload = new byte[200_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) ((i * 31) + (i >> 8));

        final ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final ByteArrayOutputStream got = new ByteArrayOutputStream();
        final List<Integer> reads = Collections.synchronizedList(new ArrayList<Integer>());
        final int expectedLen[] = new int[1];

        Env env = new Env();
        Presets.apply(env.strategy, mode);
        env.strategy.splitDelayMs = Math.max(env.strategy.splitDelayMs, 100);
        MapConnector mc = new MapConnector(ss.getLocalPort(), 0);

        // Beklenen: motorun yazacagi ilk veri (plan)
        final byte[] expected = concat(env.strategy.plan(hello).pieces);
        expectedLen[0] = expected.length;

        Thread srv = new Thread(new Runnable() {
            @Override
            public void run() {
                try (Socket s = ss.accept()) {
                    s.setTcpNoDelay(true);
                    InputStream in = s.getInputStream();
                    byte[] b = new byte[65536];
                    while (got.size() < expectedLen[0]) {
                        int n = in.read(b);
                        if (n < 0) break;
                        got.write(b, 0, n);
                        reads.add(n);
                    }
                    OutputStream o = s.getOutputStream();
                    o.write(payload);
                    o.flush();
                } catch (IOException ignore) {
                }
            }
        });
        srv.setDaemon(true);
        srv.start();

        env.start(new DohResolver(), mc);
        FakeTun tun = env.tun;

        int cIp = Pkt.ip("10.7.0.1"), sIp = Pkt.ip("162.159.135.232");
        int cp = 40001, sp = 443;
        int cseq = 1000;

        tun.send(tcpPkt(cIp, sIp, cp, sp, cseq, 0, SYN, null, true));
        P sa = tun.next(3000);
        check(sa != null && sa.flags == (SYN | ACK) && sa.ack == cseq + 1 && sa.l4Ok && sa.ipOk, tag + "SYN-ACK arrived (ack correct, checksum correct)");
        if (sa == null) {
            env.stop();
            return;
        }
        check(sa.payload.length == 0, tag + "SYN-ACK'te veri yok");
        int rcvNxt = sa.seq + 1;
        cseq++;
        tun.send(tcpPkt(cIp, sIp, cp, sp, cseq, rcvNxt, ACK, null, false));

        // ClientHello'yu 3 farkli segmentte gonder (motor bekleyip birlestirmeli)
        int[] cuts = {37, 150, hello.length};
        int prev = 0;
        for (int c : cuts) {
            byte[] seg = Arrays.copyOfRange(hello, prev, c);
            tun.send(tcpPkt(cIp, sIp, cp, sp, cseq, rcvNxt, PSH | ACK, seg, false));
            cseq += seg.length;
            prev = c;
        }
        final int helloEnd = cseq;

        // Alma dongusu
        ByteArrayOutputStream rx = new ByteArrayOutputStream();
        TreeMap<Integer, byte[]> ooo = new TreeMap<>();
        java.util.Set<Integer> droppedSeqs = new java.util.HashSet<>();
        boolean gotFin = false, allSums = true, helloAcked = false;
        int dataPkts = 0, dropped = 0;
        long deadline = System.currentTimeMillis() + 40_000;
        while (!gotFin && System.currentTimeMillis() < deadline) {
            P p = tun.next(1000);
            if (p == null) continue;
            allSums &= p.ipOk && p.l4Ok;
            if (p.proto != 6) continue;
            if ((p.flags & ACK) != 0 && p.ack == helloEnd) helloAcked = true;
            boolean hasFin = (p.flags & FIN) != 0;
            if (p.payload.length == 0 && !hasFin) continue;
            if (p.payload.length > 0) {
                dataPkts++;
                boolean inOrder = p.seq - rcvNxt == 0;
                boolean wantDrop = (dropped == 0 && dataPkts >= 5) || (dropped == 1 && dataPkts >= 60);
                if (loss && inOrder && wantDrop && !droppedSeqs.contains(p.seq)) {
                    dropped++; // bu paketi "kaybet" (yeniden iletimi kabul edilecek)
                    droppedSeqs.add(p.seq);
                    continue;
                }
            }
            if (p.seq - rcvNxt > 0) { // bosluk: tamponla, tekrarlanan ACK gonder
                if (p.payload.length > 0) ooo.put(p.seq, p.payload);
                tun.send(tcpPkt(cIp, sIp, cp, sp, cseq, rcvNxt, ACK, null, false));
                continue;
            }
            if (p.seq - rcvNxt < 0 && p.payload.length > 0 && p.seq + p.payload.length - rcvNxt <= 0) {
                tun.send(tcpPkt(cIp, sIp, cp, sp, cseq, rcvNxt, ACK, null, false)); // tekrar
                continue;
            }
            if (p.payload.length > 0) {
                rx.write(p.payload, 0, p.payload.length);
                rcvNxt = p.seq + p.payload.length;
                while (!ooo.isEmpty() && ooo.firstKey() - rcvNxt <= 0) {
                    Map.Entry<Integer, byte[]> e = ooo.pollFirstEntry();
                    int skip = rcvNxt - e.getKey();
                    if (skip < e.getValue().length) {
                        rx.write(e.getValue(), skip, e.getValue().length - skip);
                        rcvNxt = e.getKey() + e.getValue().length;
                    }
                }
            }
            if (hasFin && p.seq + p.payload.length == rcvNxt) {
                rcvNxt++;
                gotFin = true;
            }
            tun.send(tcpPkt(cIp, sIp, cp, sp, cseq, rcvNxt, ACK, null, false));
        }
        check(gotFin, tag + "FIN arrived when the target closed");
        check(rx.size() == payload.length && Arrays.equals(rx.toByteArray(), payload),
                tag + "200 KB response arrived complete and in order (" + rx.size() + " bytes, " + dataPkts + " packets)");
        check(allSums, tag + "checksums are correct in every packet the engine produced");
        check(helloAcked, tag + "ClientHello was ACKed to the client after being written to the target");
        if (loss) check(dropped == 2, tag + "two packets were really dropped and recovered by retransmission");

        // Istemci tarafi kapanis
        tun.send(tcpPkt(cIp, sIp, cp, sp, cseq, rcvNxt, FIN | ACK, null, false));
        cseq++;
        boolean finAcked = false;
        long d2 = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < d2 && !finAcked) {
            P p = tun.next(300);
            if (p != null && p.proto == 6 && (p.flags & ACK) != 0 && p.ack == cseq) finAcked = true;
        }
        check(finAcked, tag + "client FIN was ACKed");
        check(env.engine.bytesDown() == payload.length, tag + "downloaded byte counter is correct (" + env.engine.bytesDown() + ")");
        check(env.engine.bytesUp() == hello.length, tag + "uploaded byte counter is correct (" + env.engine.bytesUp() + ")");
        long d3 = System.currentTimeMillis() + 3000;
        while (env.engine.activeTcp() > 0 && System.currentTimeMillis() < d3) Thread.sleep(50);
        check(env.engine.activeTcp() == 0, tag + "session was fully cleaned up (no leak)");

        srv.join(2000);
        check(Arrays.equals(got.toByteArray(), expected), tag + "target server received the planned bytes exactly (" + got.size() + " bytes)");
        List<Integer> r = new ArrayList<>(reads);
        if (mode != Presets.NONE) {
            int first = env.strategy.plan(hello).pieces.get(0).length;
            check(!r.isEmpty() && r.get(0) == first, tag + "server saw exactly the first piece on its first read (" + (r.isEmpty() ? -1 : r.get(0)) + " == " + first + ")");
        }
        check(mc.lastTcpDst != null && mc.lastTcpDst.getHostAddress().equals("162.159.135.232") && mc.lastTcpPort == 443,
                tag + "connection was opened to the right target (162.159.135.232:443)");
        boolean logged = false;
        synchronized (env.logs) {
            for (String l : env.logs) if (l.contains("discord.com")) logged = true;
        }
        // In OFF mode the first piece is not awaited and reassembled, so the SNI not being readable is expected.
        check(logged || mode == Presets.NONE, tag + "SNI appears in the log");
        env.stop();
        ss.close();
    }

    static void testRefusedConnection() throws Exception {
        Env env = new Env();
        env.start(new DohResolver(), new Connector() {
            @Override
            public Socket tcp(InetAddress a, int p, int t) throws IOException {
                throw new IOException("Connection refused");
            }

            @Override
            public DatagramSocket udp(InetAddress a, int p) throws IOException {
                throw new IOException("x");
            }
        });
        int cIp = Pkt.ip("10.7.0.1"), sIp = Pkt.ip("203.0.113.9");
        env.tun.send(tcpPkt(cIp, sIp, 41000, 443, 5000, 0, SYN, null, true));
        P p = env.tun.next(3000);
        check(p != null && (p.flags & RST) != 0 && (p.flags & ACK) != 0 && p.ack == 5001 && p.l4Ok,
                "RST|ACK (ack = ISN+1) returned to the client when the connection was refused");

        // Bilinmeyen akisa gelen veri -> RST
        env.tun.send(tcpPkt(cIp, sIp, 41001, 443, 77, 99, ACK, "x".getBytes(), false));
        P r = env.tun.next(2000);
        check(r != null && (r.flags & RST) != 0 && r.seq == 99, "RST returned for a packet on an unknown flow");
        env.stop();
    }

    /** Ag degisiminde acik TCP oturumlari RST ile, UDP akislari kapatilarak sifirlanmali. */
    static void testNetworkReset() throws Exception {
        final ServerSocket ss = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        final List<Socket> held = Collections.synchronizedList(new ArrayList<Socket>());
        Thread acc = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) held.add(ss.accept());
                } catch (IOException ignore) {
                }
            }
        });
        acc.setDaemon(true);
        acc.start();
        DatagramSocket us = new DatagramSocket(0, InetAddress.getLoopbackAddress());

        Env env = new Env();
        env.start(new DohResolver(), new MapConnector(ss.getLocalPort(), us.getLocalPort()));
        int cIp = Pkt.ip("10.7.0.1"), sIp = Pkt.ip("198.51.100.7");
        env.tun.send(tcpPkt(cIp, sIp, 42000, 443, 9000, 0, SYN, null, true));
        P sa = env.tun.next(3000);
        check(sa != null && sa.flags == (SYN | ACK), "Network reset: session opened before the change");
        env.tun.send(tcpPkt(cIp, sIp, 42000, 443, 9001, sa == null ? 0 : sa.seq + 1, ACK, null, false));
        env.tun.send(Pkt.udp(cIp, sIp, 42001, 5000, "ping".getBytes(), 0, 4));
        us.setSoTimeout(2000);
        byte[] ub = new byte[16];
        java.net.DatagramPacket dp = new java.net.DatagramPacket(ub, ub.length);
        us.receive(dp);
        check(env.engine.activeTcp() == 1, "Network reset: one active TCP session before the change");

        env.engine.resetConnections("Wi-Fi -> Mobile");
        P r = null;
        long d2 = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < d2) {
            P p = env.tun.next(300);
            if (p != null && p.proto == 6 && (p.flags & RST) != 0) {
                r = p;
                break;
            }
        }
        check(r != null && r.sp == 443 && r.dp == 42000 && r.l4Ok,
                "Network reset: the app got a RST so it reconnects at once instead of hanging");
        check(env.engine.activeTcp() == 0, "Network reset: no TCP session is left");
        boolean logged = false;
        synchronized (env.logs) {
            for (String l : env.logs) if (l.contains("Wi-Fi -> Mobile") && l.contains("1 TCP and 1 UDP")) logged = true;
        }
        check(logged, "Network reset: logged with the number of reset connections");
        env.tun.send(tcpPkt(cIp, sIp, 42000, 443, 9001, 0, ACK, "x".getBytes(), false));
        P after = env.tun.next(2000);
        check(after != null && (after.flags & RST) != 0, "Network reset: later packets on the old flow get a RST");
        env.stop();
        ss.close();
        us.close();
        synchronized (held) {
            for (Socket h : held) h.close();
        }
    }

    static byte[] dnsQuery(int id, String name, int type) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(id >> 8);
        bo.write(id);
        bo.write(0x01);
        bo.write(0x00); // RD
        bo.write(0);
        bo.write(1);
        bo.write(0);
        bo.write(0);
        bo.write(0);
        bo.write(0);
        bo.write(0);
        bo.write(0);
        for (String l : name.split("\\.")) {
            bo.write(l.length());
            bo.write(l.getBytes(), 0, l.length());
        }
        bo.write(0);
        bo.write(type >> 8);
        bo.write(type);
        bo.write(0);
        bo.write(1);
        return bo.toByteArray();
    }

    static void testDns() throws Exception {
        Env env = new Env();
        final int[] calls = {0};
        env.start(new DnsResolver() {
            @Override
            public byte[] resolve(byte[] q) throws IOException {
                calls[0]++;
                int qEnd = Dns.questionEnd(q, q.length);
                if (Dns.qname(q, qEnd).equals("bozuk.example")) throw new IOException("no server");
                byte[] r = Arrays.copyOf(q, qEnd + 16);
                r[2] = (byte) 0x81;
                r[3] = (byte) 0x80;
                Pkt.p16(r, 6, 1);
                int p = qEnd;
                r[p++] = (byte) 0xc0;
                r[p++] = 12;
                Pkt.p16(r, p, 1);
                p += 2;
                Pkt.p16(r, p, 1);
                p += 2;
                Pkt.p32(r, p, 60);
                p += 4;
                Pkt.p16(r, p, 4);
                p += 2;
                r[p++] = 1;
                r[p++] = 2;
                r[p++] = 3;
                r[p++] = 4;
                return r;
            }
        }, new MapConnector(1, 1));

        int cIp = Pkt.ip("10.7.0.1"), dns = Pkt.ip("10.7.0.2");
        byte[] q = dnsQuery(0xBEEF, "discord.com", 1);
        env.tun.send(Pkt.udp(cIp, dns, 50000, 53, q, 0, q.length));
        P p = env.tun.next(3000);
        check(p != null && p.proto == 17 && p.sp == 53 && p.dp == 50000 && p.src == dns && p.dst == cIp && p.ipOk && p.l4Ok,
                "DNS answer returned to the client with correct address/port and checksum");
        check(p != null && P.u16(p.payload, 0) == 0xBEEF && P.u16(p.payload, 6) == 1 && (p.payload[p.payload.length - 1] == 4),
                "DNS answer: same ID, 1 answer record, A=1.2.3.4");

        // Hardcoded DNS (8.8.8.8:53) de yakalanmali
        byte[] q2 = dnsQuery(0x1234, "discord.com", 1);
        env.tun.send(Pkt.udp(cIp, Pkt.ip("8.8.8.8"), 50001, 53, q2, 0, q2.length));
        P p2 = env.tun.next(3000);
        check(p2 != null && p2.src == Pkt.ip("8.8.8.8") && P.u16(p2.payload, 0) == 0x1234, "DNS sent to 8.8.8.8 was also captured and answered");

        // Cozucu hata verirse SERVFAIL
        byte[] q3 = dnsQuery(0x4321, "bozuk.example", 1);
        env.tun.send(Pkt.udp(cIp, dns, 50002, 53, q3, 0, q3.length));
        P p3 = env.tun.next(3000);
        check(p3 != null && (p3.payload[3] & 0x0f) == 2, "SERVFAIL returned when the resolver failed");
        env.stop();

        // AAAA: empty answer without touching the network
        DohResolver doh = new DohResolver();
        byte[] aaaa = dnsQuery(0x7777, "discord.com", 28);
        byte[] resp = doh.resolve(aaaa);
        check(P.u16(resp, 0) == 0x7777 && P.u16(resp, 6) == 0 && (resp[3] & 0x0f) == 0 && (resp[2] & 0x80) != 0,
                "AAAA query answered empty (NOERROR) without leaving the device → forces IPv4");
    }

    static void testUdpAndQuic() throws Exception {
        final DatagramSocket echo = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] b = new byte[2000];
                    while (true) {
                        DatagramPacket dp = new DatagramPacket(b, b.length);
                        echo.receive(dp);
                        echo.send(new DatagramPacket(dp.getData(), dp.getLength(), dp.getSocketAddress()));
                    }
                } catch (IOException ignore) {
                }
            }
        });
        t.setDaemon(true);
        t.start();

        Env env = new Env();
        env.start(new DohResolver(), new MapConnector(1, echo.getLocalPort()));
        int cIp = Pkt.ip("10.7.0.1"), sIp = Pkt.ip("162.159.130.233");
        byte[] data = "ses paketi".getBytes();
        env.tun.send(Pkt.udp(cIp, sIp, 50010, 50005, data, 0, data.length));
        P p = env.tun.next(3000);
        check(p != null && p.proto == 17 && p.src == sIp && p.sp == 50005 && p.dp == 50010 && Arrays.equals(p.payload, data) && p.l4Ok,
                "generic UDP (e.g. Discord voice) was forwarded and the reply came back from the right source");
        check(env.engine.bytesUp() == data.length && env.engine.bytesDown() == data.length,
                "UDP byte counters are correct (↑" + env.engine.bytesUp() + " ↓" + env.engine.bytesDown() + ")");

        env.tun.send(Pkt.udp(cIp, sIp, 50011, 443, data, 0, data.length));
        P q = env.tun.next(600);
        check(q == null && env.engine.quicDropped.get() == 1, "UDP 443 (QUIC) was dropped");

        env.strategy.blockQuic = false;
        env.tun.send(Pkt.udp(cIp, sIp, 50012, 443, data, 0, data.length));
        P q2 = env.tun.next(3000);
        check(q2 != null && Arrays.equals(q2.payload, data), "UDP 443 was forwarded when the QUIC block is off");
        env.stop();
        echo.close();
    }

    // ------------------------------------------------------------------ strateji sinamasi (gercek TLS)

    static void testProbe() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("probe").toFile();
        java.io.File ksFile = new java.io.File(dir, "ks.p12");
        Process pr = new ProcessBuilder("keytool", "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "san=dns:localhost", "-storetype", "PKCS12",
                "-keystore", ksFile.getPath(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        byte[] sink = new byte[4096];
        while (pr.getInputStream().read(sink) >= 0) { }
        check(pr.waitFor() == 0, "Probe: test certificate generated");

        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        try (java.io.FileInputStream fi = new java.io.FileInputStream(ksFile)) {
            ks.load(fi, "changeit".toCharArray());
        }
        javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, "changeit".toCharArray());
        final SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, null);

        java.security.KeyStore trust = java.security.KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("t", ks.getCertificate("t"));
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance("PKIX");
        tmf.init(trust);
        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, tmf.getTrustManagers(), null);

        for (final Presets.Preset pst : Presets.ALL) {
            final ServerSocket ss = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            final int[] firstRead = {-1};
            final boolean[] srvOk = {false};
            Thread srv = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket raw = ss.accept()) {
                        byte[] buf = new byte[65536];
                        int n = raw.getInputStream().read(buf);
                        firstRead[0] = n;
                        javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) serverCtx.getSocketFactory()
                                .createSocket(raw, new java.io.ByteArrayInputStream(buf, 0, n), true);
                        ssl.setUseClientMode(false);
                        ssl.startHandshake();
                        srvOk[0] = true;
                    } catch (Exception ex) {
                        // istemci tarafi sonucu belirler
                    }
                }
            });
            srv.start();
            Strategy st = new Strategy();
            Presets.apply(st, pst);
            st.splitDelayMs = Math.max(st.splitDelayMs, 100);
            String tag = "Sinama [" + pst.id + "]: ";
            try {
                int ms = Probe.handshake("127.0.0.1", ss.getLocalPort(), "localhost", st, clientCtx, 5000);
                check(ms >= 0, tag + "TLS handshake completed and the certificate was verified (" + ms + " ms)");
            } catch (Exception ex) {
                check(false, tag + "handshake failed: " + ex);
            }
            srv.join(3000);
            check(srvOk[0], tag + "the server also completed the handshake (split ClientHello was reassembled)");
            int fr = firstRead[0];
            if (pst == Presets.NONE) {
                check(fr > 100, tag + "not split: the first read is the whole ClientHello (" + fr + ")");
            } else if (pst.id.equals("oob") || pst.id.equals("agresif") || pst.id.equals("sni") || pst.id.equals("disorder")) {
                check(fr == 1, tag + "server saw exactly 1 byte on its first read (" + fr + ")");
            } else {
                check(fr > 5, tag + "the first read is a split piece, not the whole ClientHello (" + fr + ")");
            }
            ss.close();
        }

        // yanlis guven deposu: ozel imzali sertifika reddedilmeli
        final ServerSocket ss2 = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread srv2 = new Thread(new Runnable() {
            @Override
            public void run() {
                try (Socket raw = ss2.accept()) {
                    javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) serverCtx.getSocketFactory()
                            .createSocket(raw, null, raw.getPort(), false);
                    ssl.setUseClientMode(false);
                    ssl.startHandshake();
                } catch (Exception ex) {
                    // beklenen
                }
            }
        });
        srv2.start();
        try {
            Probe.handshake("127.0.0.1", ss2.getLocalPort(), "localhost", new Strategy(), SSLContext.getDefault(), 5000);
            check(false, "Probe: untrusted certificate was not rejected");
        } catch (Exception ex) {
            check(Probe.ERR_CERTIFICATE.equals(Probe.describe(ex)), "Probe: untrusted certificate was rejected (" + Probe.describe(ex) + ")");
        }
        ss2.close();

        // kapali port
        ServerSocket tmp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int closedPort = tmp.getLocalPort();
        tmp.close();
        try {
            Probe.handshake("127.0.0.1", closedPort, "localhost", new Strategy(), clientCtx, 2000);
            check(false, "Probe: connecting to a closed port did not raise an error");
        } catch (Exception ex) {
            check(true, "Probe: closed port raised an error (" + Probe.describe(ex) + ")");
        }

        testDoh(serverCtx, clientCtx);

        // DNS yardimcilari
        byte[] qq = Dns.buildQuery("discord.com", Dns.TYPE_A);
        check(Dns.questionEnd(qq, qq.length) == qq.length && "discord.com".equals(Dns.qname(qq, qq.length))
                && Dns.qtype(qq, qq.length) == Dns.TYPE_A, "Probe: DNS query built correctly");
        byte[] resp = Arrays.copyOf(qq, qq.length + 16);
        resp[2] = (byte) 0x81;
        resp[3] = (byte) 0x80;
        Pkt.p16(resp, 6, 1);
        int o = qq.length;
        resp[o] = (byte) 0xc0; resp[o + 1] = 12;
        Pkt.p16(resp, o + 2, 1); Pkt.p16(resp, o + 4, 1);
        resp[o + 9] = 60;
        Pkt.p16(resp, o + 10, 4);
        resp[o + 12] = (byte) 162; resp[o + 13] = (byte) 159; resp[o + 14] = (byte) 135; resp[o + 15] = (byte) 232;
        check(Dns.answersA(resp).equals(Collections.singletonList("162.159.135.232")), "Probe: A record read from the DNS answer");
        check(DohResolver.endpointsFor("quad9")[0].contains("9.9.9.9") && DohResolver.endpointsFor("cloudflare")[0].contains("1.1.1.1")
                && DohResolver.endpointsFor("google")[0].contains("8.8.8.8") && DohResolver.endpointsFor("quad9").length == 6,
                "Probe: DoH provider order follows the primary");
    }

    static byte[] fakeDnsAnswer(byte[] q) {
        int qEnd = Dns.questionEnd(q, q.length);
        byte[] r = Arrays.copyOf(q, qEnd + 16);
        r[2] = (byte) 0x81;
        r[3] = (byte) 0x80;
        Pkt.p16(r, 6, 1);
        r[qEnd] = (byte) 0xc0;
        r[qEnd + 1] = 12;
        Pkt.p16(r, qEnd + 2, 1);
        Pkt.p16(r, qEnd + 4, 1);
        r[qEnd + 9] = 60;
        Pkt.p16(r, qEnd + 10, 4);
        r[qEnd + 12] = 1; r[qEnd + 13] = 2; r[qEnd + 14] = 3; r[qEnd + 15] = 4;
        return r;
    }

    static String rawLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') sb.setLength(sb.length() - 1);
                return sb.toString();
            }
            sb.append((char) b);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static void testDoh(final SSLContext serverCtx, SSLContext clientCtx) throws Exception {
        final ServerSocket ss = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        final java.util.concurrent.atomic.AtomicInteger conns = new java.util.concurrent.atomic.AtomicInteger();
        final List<String> sni = Collections.synchronizedList(new ArrayList<String>());
        final List<String> hosts = Collections.synchronizedList(new ArrayList<String>());
        Thread srv = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        final Socket raw = ss.accept();
                        conns.incrementAndGet();
                        new Thread(new Runnable() { public void run() {
                        try {
                            javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) serverCtx.getSocketFactory()
                                    .createSocket(raw, null, raw.getPort(), true);
                            ssl.setUseClientMode(false);
                            ssl.startHandshake();
                            javax.net.ssl.ExtendedSSLSession es = (javax.net.ssl.ExtendedSSLSession) ssl.getSession();
                            for (javax.net.ssl.SNIServerName n : es.getRequestedServerNames()) {
                                sni.add(((SNIHostName) n).getAsciiName());
                            }
                            InputStream in = ssl.getInputStream();
                            OutputStream out = ssl.getOutputStream();
                            int served = 0;
                            while (true) {
                                String line = rawLine(in);
                                if (line == null) break;
                                int len = 0;
                                String h;
                                while ((h = rawLine(in)) != null && !h.isEmpty()) {
                                    String lo = h.toLowerCase();
                                    if (lo.startsWith("host:")) hosts.add(h.substring(5).trim());
                                    if (lo.startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
                                }
                                byte[] q = new byte[len];
                                int got = 0;
                                while (got < len) {
                                    int r = in.read(q, got, len - got);
                                    if (r < 0) break;
                                    got += r;
                                }
                                byte[] ans = fakeDnsAnswer(q);
                                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                                if (served++ % 2 == 0) {
                                    bo.write(("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: "
                                            + ans.length + "\r\n\r\n").getBytes());
                                    bo.write(ans);
                                } else {
                                    int cut = 10;
                                    bo.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes());
                                    bo.write((Integer.toHexString(cut) + "\r\n").getBytes());
                                    bo.write(ans, 0, cut);
                                    bo.write("\r\n".getBytes());
                                    bo.write((Integer.toHexString(ans.length - cut) + "\r\n").getBytes());
                                    bo.write(ans, cut, ans.length - cut);
                                    bo.write("\r\n0\r\n\r\n".getBytes());
                                }
                                out.write(bo.toByteArray());
                                out.flush();
                            }
                        } catch (Exception ex) {
                            // istemci kapatti
                        } finally {
                            try { raw.close(); } catch (IOException ignored) { }
                        }
                        } }).start();
                    }
                } catch (IOException e) {
                    // sunucu kapatildi
                }
            }
        });
        srv.start();

        String ep = "127.0.0.1:" + ss.getLocalPort() + "=localhost";
        DohResolver r = new DohResolver(new String[]{ep}, clientCtx);
        List<String> a = r.lookupA("a.example.com");
        List<String> b = r.lookupA("b.example.com");
        List<String> c = r.lookupA("c.example.com");
        check(a.equals(Collections.singletonList("1.2.3.4")) && b.equals(a) && c.equals(a),
                "DoH: three queries (Content-Length and chunked replies) read correctly");
        check(conns.get() == 1, "DoH: a single TLS connection was reused (" + conns.get() + ")");
        check(!sni.isEmpty() && sni.get(0).equals("localhost"), "DoH: provider name was sent as the SNI in TLS " + sni);
        check(hosts.size() == 3 && hosts.get(0).equals("localhost"), "DoH: Host header is the provider name " + hosts);
        check(r.resolve(Dns.buildQuery("x.example.com", Dns.TYPE_AAAA)).length > 12 && conns.get() == 1,
                "DoH: AAAA query answered empty without touching the network");

        // sertifika baska bir ana makine adi icin: DoH ve sinama reddetmeli
        try {
            new DohResolver(new String[]{"127.0.0.1:" + ss.getLocalPort() + "=wrong.example"}, clientCtx)
                    .lookupA("h.example.com");
            check(false, "DoH: certificate for another host name was accepted");
        } catch (IOException ex) {
            check(true, "DoH: certificate for another host name was rejected (" + ex.getClass().getSimpleName() + ")");
        }
        try {
            Probe.handshake("127.0.0.1", ss.getLocalPort(), "wrong.example", new Strategy(), clientCtx, 3000);
            check(false, "Probe: certificate for another host name was accepted");
        } catch (Exception ex) {
            check(Probe.ERR_HOSTNAME.equals(Probe.describe(ex)),
                    "Probe: certificate for another host name was rejected as a host name mismatch (" + Probe.describe(ex) + ")");
        }
        int connsBefore = conns.get();
        r.close();
        check(r.lookupA("k.example.com").equals(Collections.singletonList("1.2.3.4")) && conns.get() == connsBefore + 1,
                "DoH: close() dropped the idle connection and the next query reconnected");
        int connsBeforeFlush = conns.get();
        int hostsBeforeFlush = hosts.size();
        r.flush();
        check(r.lookupA("a.example.com").equals(Collections.singletonList("1.2.3.4"))
                        && conns.get() == connsBeforeFlush + 1 && hosts.size() == hostsBeforeFlush + 1,
                "DoH: flush() after a network change dropped both the idle connection and the cached answer");
        for (String badSize : new String[]{"7fffffff", "-1"}) {
            final String size = badSize;
            final ServerSocket one = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket raw = one.accept()) {
                        javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) serverCtx.getSocketFactory()
                                .createSocket(raw, null, raw.getPort(), true);
                        ssl.setUseClientMode(false);
                        ssl.startHandshake();
                        byte[] buf = new byte[4096];
                        ssl.getInputStream().read(buf);
                        String resp = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nabcd\r\n"
                                + size + "\r\n";
                        ssl.getOutputStream().write(resp.getBytes());
                        ssl.getOutputStream().flush();
                        Thread.sleep(500);
                    } catch (Exception ex) {
                        // istemci kapatir
                    }
                }
            });
            t.start();
            try {
                new DohResolver(new String[]{"127.0.0.1:" + one.getLocalPort() + "=localhost"}, clientCtx)
                        .lookupA("o.example.com");
                check(false, "DoH: chunk size " + size + " was accepted");
            } catch (IOException ex) {
                check(ex.getMessage() != null && ex.getMessage().contains("too large"),
                        "DoH: chunk size " + size + " was rejected without overflow (" + ex.getMessage() + ")");
            }
            one.close();
        }
        try {
            new DohResolver(new String[]{"not-an-endpoint"}, clientCtx).lookupA("m.example.com");
            check(false, "DoH: malformed endpoint did not raise an error");
        } catch (IOException ex) {
            check(true, "DoH: malformed endpoint raised an IOException instead of a runtime exception");
        }

        // TLS olmayan (enjekte edilmis) cevap veren bozuk uc nokta -> yedege gecilmeli
        final ServerSocket bad = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread badSrv = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        Socket raw = bad.accept();
                        raw.getOutputStream().write("HTTP/1.1 403 Forbidden\r\n\r\nengellendi".getBytes());
                        raw.getOutputStream().flush();
                        raw.close();
                    }
                } catch (IOException e) {
                    // kapandi
                }
            }
        });
        badSrv.start();
        String badEp = "127.0.0.1:" + bad.getLocalPort() + "=localhost";
        DohResolver only = new DohResolver(new String[]{badEp}, clientCtx);
        try {
            only.lookupA("d.example.com");
            check(false, "DoH: non-TLS reply did not raise an error");
        } catch (IOException ex) {
            check(true, "DoH: non-TLS reply raised an IOException (" + ex.getClass().getSimpleName() + ")");
        }
        DohResolver fb = new DohResolver(new String[]{badEp, ep}, clientCtx);
        check(fb.lookupA("e.example.com").equals(Collections.singletonList("1.2.3.4")),
                "DoH: broken endpoint was skipped and the backup used");
        bad.close();
        ss.close();
    }

    static void testDomainParsing() {
        check(Probe.parseDomains("discord.com, https://X.com/path?q=1  roblox.com:443;wattpad.com.")
                        .equals(Arrays.asList("discord.com", "x.com", "roblox.com", "wattpad.com")),
                "Sites: commas, spaces, scheme, path, port and a trailing dot are cleaned up");
        check(Probe.parseDomains("a.com a.com A.COM").equals(Collections.singletonList("a.com")), "Sites: duplicates collapse");
        check(Probe.parseDomains("").equals(Probe.DEFAULT_DOMAINS) && Probe.parseDomains(null).equals(Probe.DEFAULT_DOMAINS)
                && Probe.parseDomains("  ,; ").equals(Probe.DEFAULT_DOMAINS), "Sites: empty input gives the default list");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 12; i++) many.append("site").append(i).append(".example.com ");
        check(Probe.parseDomains(many.toString()).size() == Probe.MAX_DOMAINS, "Sites: at most " + Probe.MAX_DOMAINS + " are kept");
        for (String bad : new String[]{"localhost", "1.2.3.4", "not a.domain", "exämple.com", "a..b.com", "-bad.com", "bad-.com",
                "x.com:abc", "x.com:123456", "a_b.com/x y", "a.com\\evil", "@evil.com", "x.com;rm -rf", "<script>.com", "a.b"+"c".repeat(70)+".com"}) {
            try {
                Probe.parseDomains(bad);
                check(bad.contains("rm -rf") || bad.equals("not a.domain") || bad.equals("a_b.com/x y"), "Sites: '" + bad + "' was accepted");
            } catch (IllegalArgumentException ex) {
                check(true, "Sites: '" + bad + "' rejected (" + ex.getMessage() + ")");
            }
        }
        boolean rejectedFirst = false;
        try {
            Probe.parseDomains("good.com, bad_site, other.com");
        } catch (IllegalArgumentException ex) {
            rejectedFirst = "bad_site".equals(ex.getMessage());
        }
        check(rejectedFirst, "Sites: the offending entry is reported");
    }

    public static void main(String[] a) throws Exception {
        testChecksums();
        testSni();
        testPlans();
        testDomainParsing();
        testRefusedConnection();
        testDisorderSend();
        testDiagnose();
        testNetworkReset();
        testDns();
        testUdpAndQuic();
        testTcpFlow(Presets.byId("sni"), false);
        testTcpFlow(Presets.byId("tlskayit"), false);
        testTcpFlow(Presets.byId("oob"), false);
        testTcpFlow(Presets.NONE, false);
        testTcpFlow(Presets.byId("hafif"), true);
        testProbe();
        System.out.println();
        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
