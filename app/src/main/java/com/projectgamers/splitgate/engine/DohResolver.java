package com.projectgamers.splitgate.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * DNS-over-HTTPS (RFC 8484). Sunuculara IP ile baglanir (ad cozmeye gerek yok) ama TLS'te SNI olarak
 * saglayicinin gercek adini ve Host basligini gonderir; SNI'siz IP baglantilarini bozan aglarda da calisir.
 * AAAA sorgulari bos cevaplanir (IPv6 tunelde yok, IPv4'e zorlanir).
 */
public final class DohResolver implements DnsResolver {
    /** Uc nokta bicimi: "ip[:port]=host" (port yoksa 443). */
    private static final String[] QUAD9 = {"9.9.9.9=dns.quad9.net", "149.112.112.112=dns.quad9.net"};
    private static final String[] CLOUDFLARE = {"1.1.1.1=cloudflare-dns.com", "1.0.0.1=cloudflare-dns.com"};
    private static final String[] GOOGLE = {"8.8.8.8=dns.google", "8.8.4.4=dns.google"};

    /** Birincil saglayici ilk sirada olacak sekilde ("quad9" | "cloudflare" | "google") uc nokta listesi. */
    public static String[] endpointsFor(String primary) {
        String[][] order;
        if ("cloudflare".equals(primary)) order = new String[][]{CLOUDFLARE, QUAD9, GOOGLE};
        else if ("google".equals(primary)) order = new String[][]{GOOGLE, QUAD9, CLOUDFLARE};
        else order = new String[][]{QUAD9, CLOUDFLARE, GOOGLE};
        String[] out = new String[6];
        int i = 0;
        for (String[] g : order) for (String e : g) out[i++] = e;
        return out;
    }

    private static final long CACHE_MS = 30_000;
    private static final int CACHE_MAX = 512;
    private static final long IDLE_MS = 20_000;
    private static final int CONNECT_TIMEOUT_MS = 3500;
    private static final int READ_TIMEOUT_MS = 4000;
    private static final int MAX_BODY = 65535;

    private static final class Cached {
        final byte[] resp;
        final long at;

        Cached(byte[] resp, long at) {
            this.resp = resp;
            this.at = at;
        }
    }

    private static final class Conn {
        final SSLSocket sock;
        final InputStream in;
        final OutputStream out;
        long lastUsed;

        Conn(SSLSocket sock) throws IOException {
            this.sock = sock;
            this.in = sock.getInputStream();
            this.out = sock.getOutputStream();
        }

        void close() {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private final String[] endpoints;
    private final SSLContext ctx;
    private volatile int preferred = 0;
    private final Map<String, ArrayDeque<Conn>> idle = new HashMap<>();
    private final Map<String, Cached> cache = new LinkedHashMap<String, Cached>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> e) {
            return size() > CACHE_MAX;
        }
    };

    public DohResolver() {
        this(endpointsFor("quad9"));
    }

    public DohResolver(String[] endpoints) {
        this(endpoints, null);
    }

    DohResolver(String[] endpoints, SSLContext ctx) {
        this.endpoints = endpoints.clone();
        SSLContext c = ctx;
        if (c == null) {
            try {
                c = SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        this.ctx = c;
    }

    /** Bosta bekleyen tum DoH baglantilarini kapatir. */
    public void close() {
        synchronized (idle) {
            for (ArrayDeque<Conn> dq : idle.values()) {
                for (Conn c : dq) c.close();
            }
            idle.clear();
        }
    }

    /**
     * Ag degisiminden sonra cagrilir: eski agdaki bosta baglantilar (yeniden kullanilinca okuma zaman asimina
     * kadar takilirlar) ve o aga ozgu olabilecek cevaplar (CDN adresleri) atilir, birincil saglayiciya donulur.
     */
    public void flush() {
        close();
        synchronized (cache) {
            cache.clear();
        }
        preferred = 0;
    }

    /** Alan adinin IPv4 adreslerini DoH ile cozer. */
    public List<String> lookupA(String host) throws IOException {
        byte[] r = resolve(Dns.buildQuery(host, Dns.TYPE_A));
        List<String> ips = Dns.answersA(r);
        if (ips.isEmpty()) throw new IOException("DNS answer is empty");
        return ips;
    }

    @Override
    public byte[] resolve(byte[] q) throws IOException {
        int qEnd = Dns.questionEnd(q, q.length);
        if (qEnd < 0) throw new IOException("invalid DNS query");
        if (Dns.qtype(q, qEnd) == Dns.TYPE_AAAA) return Dns.emptyResponse(q, qEnd, 0);

        String key = Arrays.toString(Arrays.copyOfRange(q, 12, qEnd));
        long now = System.currentTimeMillis();
        synchronized (cache) {
            Cached c = cache.get(key);
            if (c != null && now - c.at < CACHE_MS) return withId(c.resp, q);
        }

        IOException last = null;
        for (int i = 0; i < endpoints.length; i++) {
            int idx = (preferred + i) % endpoints.length;
            try {
                byte[] r = exchange(endpoints[idx], q);
                preferred = idx;
                if (r.length >= 12 && (r[3] & 0x0f) == 0 && Pkt.u16(r, 6) > 0) {
                    synchronized (cache) {
                        cache.put(key, new Cached(r, now));
                    }
                }
                return withId(r, q);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("DoH failed");
    }

    private static byte[] withId(byte[] resp, byte[] q) {
        byte[] r = resp.clone();
        r[0] = q[0];
        r[1] = q[1];
        return r;
    }

    // ------------------------------------------------------------------ baglanti

    private Conn takeIdle(String ep) {
        synchronized (idle) {
            ArrayDeque<Conn> dq = idle.get(ep);
            long now = System.currentTimeMillis();
            while (dq != null && !dq.isEmpty()) {
                Conn c = dq.pollFirst();
                if (now - c.lastUsed < IDLE_MS) return c;
                c.close();
            }
        }
        return null;
    }

    private void giveBack(String ep, Conn c) {
        c.lastUsed = System.currentTimeMillis();
        synchronized (idle) {
            ArrayDeque<Conn> dq = idle.get(ep);
            if (dq == null) {
                dq = new ArrayDeque<>();
                idle.put(ep, dq);
            }
            if (dq.size() >= 2) {
                c.close();
                return;
            }
            dq.addFirst(c);
        }
    }

    private Conn open(String ep) throws IOException {
        int eq = ep.indexOf('=');
        if (eq <= 0 || eq == ep.length() - 1) throw new IOException("invalid DoH endpoint");
        String addr = ep.substring(0, eq);
        String host = ep.substring(eq + 1);
        int port = 443;
        int colon = addr.indexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(addr.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IOException("invalid DoH endpoint");
            }
            addr = addr.substring(0, colon);
        }
        Socket raw = new Socket();
        try {
            raw.setTcpNoDelay(true);
            raw.connect(new InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS);
            raw.setSoTimeout(READ_TIMEOUT_MS);
            SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            SSLParameters sp = ssl.getSSLParameters();
            sp.setServerNames(Collections.singletonList(new SNIHostName(host)));
            sp.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(sp);
            ssl.startHandshake();
            return new Conn(ssl);
        } catch (IOException | RuntimeException e) {
            try {
                raw.close();
            } catch (IOException ignored) {
            }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e);
        }
    }

    private byte[] exchange(String ep, byte[] q) throws IOException {
        String host = ep.substring(ep.indexOf('=') + 1);
        Conn c = takeIdle(ep);
        if (c != null) {
            try {
                return request(ep, c, host, q);
            } catch (IOException stale) {
                c.close(); // bosta bekleyen baglanti kopmus olabilir; yenisini ac
            }
        }
        c = open(ep);
        try {
            return request(ep, c, host, q);
        } catch (IOException e) {
            c.close();
            throw e;
        }
    }

    // ------------------------------------------------------------------ HTTP/1.1

    private byte[] request(String ep, Conn c, String host, byte[] q) throws IOException {
        String head = "POST /dns-query HTTP/1.1\r\nHost: " + host + "\r\n"
                + "Content-Type: application/dns-message\r\n"
                + "Accept: application/dns-message\r\n"
                + "Content-Length: " + q.length + "\r\n"
                + "Connection: keep-alive\r\n\r\n";
        byte[] h = head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] req = new byte[h.length + q.length];
        System.arraycopy(h, 0, req, 0, h.length);
        System.arraycopy(q, 0, req, h.length, q.length);
        c.out.write(req);
        c.out.flush();

        String statusLine = readLine(c.in);
        if (statusLine == null) throw new IOException("DoH connection closed");
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("DoH invalid response");
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("DoH invalid response");
        }
        long contentLength = -1;
        boolean chunked = false;
        boolean close = parts[0].equals("HTTP/1.0");
        for (int i = 0; i < 100; i++) {
            String line = readLine(c.in);
            if (line == null) throw new IOException("DoH connection closed");
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String k = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            String v = line.substring(colon + 1).trim().toLowerCase(java.util.Locale.ROOT);
            if (k.equals("content-length")) {
                try {
                    contentLength = Long.parseLong(v);
                } catch (NumberFormatException e) {
                    throw new IOException("DoH invalid length");
                }
            } else if (k.equals("transfer-encoding") && v.contains("chunked")) {
                chunked = true;
            } else if (k.equals("connection") && v.contains("close")) {
                close = true;
            }
        }
        byte[] body;
        if (chunked) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            while (true) {
                String sz = readLine(c.in);
                if (sz == null) throw new IOException("DoH connection closed");
                int semi = sz.indexOf(';');
                if (semi >= 0) sz = sz.substring(0, semi);
                int n;
                try {
                    n = Integer.parseInt(sz.trim(), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("DoH invalid chunk");
                }
                if (n == 0) {
                    while (true) { // trailer'lar
                        String t = readLine(c.in);
                        if (t == null || t.isEmpty()) break;
                    }
                    break;
                }
                if (n < 0 || n > MAX_BODY - bo.size()) throw new IOException("DoH response too large");
                readFully(c.in, bo, n);
                readLine(c.in); // parca sonu CRLF
            }
            body = bo.toByteArray();
        } else if (contentLength >= 0) {
            if (contentLength > MAX_BODY) throw new IOException("DoH response too large");
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) contentLength);
            readFully(c.in, bo, (int) contentLength);
            body = bo.toByteArray();
        } else {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = c.in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_BODY) throw new IOException("DoH response too large");
            }
            body = bo.toByteArray();
            close = true;
        }
        if (close) c.close();
        else giveBack(ep, c);
        if (code != 200) throw new IOException("DoH HTTP " + code);
        if (body.length < 12) throw new IOException("DoH response too short");
        return body;
    }

    /** CRLF ile biten satiri okur (CRLF dahil degil); akis bitmisse null. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) return sb.length() == 0 ? null : sb.toString();
            if (b == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > 8192) throw new IOException("DoH header too long");
        }
    }

    private static void readFully(InputStream in, ByteArrayOutputStream bo, int n) throws IOException {
        byte[] buf = new byte[Math.min(n, 4096)];
        int left = n;
        while (left > 0) {
            int r = in.read(buf, 0, Math.min(buf.length, left));
            if (r < 0) throw new IOException("DoH connection closed");
            bo.write(buf, 0, r);
            left -= r;
        }
    }
}
