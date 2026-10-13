// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

/**
 * Strateji sinamasi: her on ayari gercek bir TLS el sikismasiyla dener. ClientHello, secilen
 * stratejiyle bolunerek kendi soketimizden gonderilir; sertifika dogrulanir. Uygulama VPN disinda
 * tutuldugu icin motor calisirken de ayni sekilde calisir. Ad cozumleme DoH iledir.
 */
public final class Probe {
    private Probe() {}

    public static final int TIMEOUT_MS = 6000;

    public static final List<String> DEFAULT_DOMAINS =
            Collections.unmodifiableList(Arrays.asList("discord.com", "roblox.com", "x.com", "wattpad.com"));
    public static final int MAX_DOMAINS = 8;
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{1,253}$)[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?)+$");
    private static final Pattern NUMERIC = Pattern.compile("^[0-9.]+$");

    /**
     * Kullanicinin yazdigi site listesini temizler: virgul/bosluk ile ayrilmis alan adlari; http(s):// onegi,
     * yol ve port atilir. Gecersiz bir ad varsa o ad mesaj olarak IllegalArgumentException firlatir.
     * Bos giris varsayilan listeyi dondurur; en cok MAX_DOMAINS ad tutulur.
     */
    public static List<String> parseDomains(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            for (String raw : text.trim().split("[,;\\s]+")) {
                String d = raw.trim().toLowerCase(Locale.ROOT);
                if (d.isEmpty()) continue;
                if (d.startsWith("https://")) d = d.substring(8);
                else if (d.startsWith("http://")) d = d.substring(7);
                for (char stop : new char[]{'/', '?', '#'}) {
                    int i = d.indexOf(stop);
                    if (i >= 0) d = d.substring(0, i);
                }
                int colon = d.indexOf(':');
                if (colon >= 0) {
                    if (!d.substring(colon + 1).matches("[0-9]{1,5}")) throw new IllegalArgumentException(raw);
                    d = d.substring(0, colon);
                }
                while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
                if (!DOMAIN.matcher(d).matches() || NUMERIC.matcher(d).matches()) {
                    throw new IllegalArgumentException(raw);
                }
                if (!out.contains(d)) out.add(d);
                if (out.size() == MAX_DOMAINS) break;
            }
        }
        return out.isEmpty() ? new ArrayList<>(DEFAULT_DOMAINS) : out;
    }

    /** DomainResult.err icin makine-okunur kodlar; arayuz bunlari yerellestirir. */
    public static final String ERR_TIMEOUT = "timeout";
    public static final String ERR_CERTIFICATE = "certificate";
    public static final String ERR_HOSTNAME = "hostname";
    public static final String ERR_DNS_PREFIX = "dns: ";
    /** Sunucuya TCP baglantisi bile kurulamadi (zaman asimi): genelde IP engeli. */
    public static final String ERR_CONNECT_TIMEOUT = "connect-timeout";
    public static final String ERR_RESET = "reset";
    public static final String ERR_REFUSED = "refused";
    public static final String ERR_UNREACHABLE = "unreachable";
    public static final String ERR_CLOSED = "closed";

    public enum Verdict {
        /** En az bir bolme yontemi siteleri acti. */
        WORKS,
        /** Bolmeden de aciliyor: bu agda bu siteler engelli degil. */
        NOT_BLOCKED,
        /** DoH saglayicilarina ulasilamadi; siteler denenemedi. */
        DNS_FAILED,
        /** Ag yok ya da hedeflere yol yok. */
        NO_CONNECTION,
        /** TCP baglantisi bile kurulmuyor: IP engeli; bu uygulama asamaz. */
        IP_BLOCK,
        /** Sertifika uyusmuyor: baglanti araya giren biri tarafindan cevaplaniyor. */
        INTERCEPTED,
        /** Baglanti kuruluyor ama DPI her yontemi yakaliyor. */
        DPI_UNBEATEN
    }

    public interface Progress {
        void step(Presets.Preset preset);
    }

    public static final class DomainResult {
        public final boolean ok;
        public final int ms;
        public final String err;

        DomainResult(boolean ok, int ms, String err) {
            this.ok = ok;
            this.ms = ms;
            this.err = err;
        }
    }

    public static final class Result {
        public final Presets.Preset preset;
        public final int ok;
        public final int total;
        public final int avgMs;
        public final Map<String, DomainResult> domains;

        Result(Presets.Preset preset, int ok, int total, int avgMs, Map<String, DomainResult> domains) {
            this.preset = preset;
            this.ok = ok;
            this.total = total;
            this.avgMs = avgMs;
            this.domains = domains;
        }
    }

    /** Basarili olursa el sikisma suresini (ms) dondurur; basarisizsa istisna firlatir. */
    public static int handshake(String ip, int port, String host, Strategy st, SSLContext ctx, int timeoutMs)
            throws Exception {
        return handshake(ip, port, host, st, ctx, timeoutMs, null);
    }

    public static int handshake(String ip, int port, String host, Strategy st, SSLContext ctx, int timeoutMs,
                                SocketTtl ttl) throws Exception {
        long t0 = System.nanoTime();
        long deadline = t0 + timeoutMs * 1_000_000L;
        SSLEngine eng = ctx.createSSLEngine(host, port);
        eng.setUseClientMode(true);
        SSLParameters sp = eng.getSSLParameters();
        sp.setServerNames(Collections.singletonList(new SNIHostName(host)));
        sp.setEndpointIdentificationAlgorithm("HTTPS");
        eng.setSSLParameters(sp);

        Socket sock = new Socket();
        try {
            sock.setTcpNoDelay(true);
            try {
                sock.connect(new InetSocketAddress(ip, port), timeoutMs);
            } catch (SocketTimeoutException e) {
                throw new ConnectTimeout();
            }
            sock.setSoTimeout(timeoutMs);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            int netSize = Math.max(eng.getSession().getPacketBufferSize(), 17 * 1024);
            ByteBuffer netIn = ByteBuffer.allocate(netSize * 2);
            netIn.limit(0);
            ByteBuffer netOut = ByteBuffer.allocate(netSize);
            ByteBuffer empty = ByteBuffer.allocate(0);
            ByteBuffer app = ByteBuffer.allocate(Math.max(eng.getSession().getApplicationBufferSize(), 17 * 1024));

            boolean first = true;
            eng.beginHandshake();
            SSLEngineResult.HandshakeStatus hs = eng.getHandshakeStatus();
            while (hs != SSLEngineResult.HandshakeStatus.FINISHED
                    && hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                if (System.nanoTime() > deadline) throw new SocketTimeoutException("timed out");
                if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    Runnable r;
                    while ((r = eng.getDelegatedTask()) != null) r.run();
                    hs = eng.getHandshakeStatus();
                } else if (hs == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    netOut.clear();
                    SSLEngineResult res = eng.wrap(empty, netOut);
                    if (res.getStatus() == SSLEngineResult.Status.CLOSED) throw new IOException("connection closed");
                    hs = res.getHandshakeStatus();
                    netOut.flip();
                    byte[] b = new byte[netOut.remaining()];
                    netOut.get(b);
                    if (b.length > 0) {
                        if (first) {
                            first = false;
                            Sender.send(sock, out, st.plan(b), ttl);
                        } else {
                            out.write(b);
                            out.flush();
                        }
                    }
                } else {
                    app.clear();
                    SSLEngineResult res = eng.unwrap(netIn, app);
                    SSLEngineResult.Status status = res.getStatus();
                    if (status == SSLEngineResult.Status.CLOSED) throw new IOException("connection closed");
                    if (status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        netIn.compact();
                        if (!netIn.hasRemaining()) throw new IOException("TLS record too large");
                        int n = in.read(netIn.array(), netIn.position(), netIn.remaining());
                        if (n < 0) throw new IOException("connection closed (EOF)");
                        netIn.position(netIn.position() + n);
                        netIn.flip();
                        hs = eng.getHandshakeStatus();
                    } else if (status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        throw new IOException("application buffer too small");
                    } else {
                        hs = res.getHandshakeStatus();
                    }
                }
            }
            return (int) ((System.nanoTime() - t0) / 1_000_000L);
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class ConnectTimeout extends IOException {
        ConnectTimeout() {
            super("connect timed out");
        }
    }

    static String describe(Throwable e) {
        if (e instanceof ConnectTimeout) return ERR_CONNECT_TIMEOUT;
        if (e instanceof SocketTimeoutException) return ERR_TIMEOUT;
        Throwable c = e;
        while (c != null) {
            String msg = c.getMessage();
            if (msg != null && (msg.contains("No subject alternative") || msg.contains("not verified"))) return ERR_HOSTNAME;
            String n = c.getClass().getSimpleName();
            if (n.contains("CertPath") || n.contains("CertificateException")) return ERR_CERTIFICATE;
            c = c.getCause();
        }
        String lm = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        if (lm.contains("reset") || lm.contains("broken pipe")) return ERR_RESET;
        if (lm.contains("refused")) return ERR_REFUSED;
        if (lm.contains("unreachable") || lm.contains("no route")) return ERR_UNREACHABLE;
        if (lm.contains("connection closed") || lm.contains("eof")) return ERR_CLOSED;
        if (e instanceof java.net.NoRouteToHostException) return ERR_UNREACHABLE;
        if (e instanceof java.net.ConnectException) return ERR_REFUSED;
        String m = e.getMessage();
        String s = e.getClass().getSimpleName() + (m != null ? ": " + m : "");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    /** Tum on ayarlari verilen alan adlarinda dener. */
    public static List<Result> runAll(DohResolver dns, List<String> domains, Progress progress) throws Exception {
        return run(dns, domains, Arrays.asList(Presets.ALL), progress, null);
    }

    public static List<Result> runAll(DohResolver dns, List<String> domains, Progress progress, SocketTtl ttl)
            throws Exception {
        return run(dns, domains, Arrays.asList(Presets.ALL), progress, ttl);
    }

    /** Yalnizca verilen on ayarlari dener; listede NONE varsa bolmesiz taban cizgisi de olculur. */
    public static List<Result> run(DohResolver dns, List<String> domains, List<Presets.Preset> presets,
                                   Progress progress, final SocketTtl ttl) throws Exception {
        final SSLContext ctx = SSLContext.getDefault();
        final Map<String, String> ips = new LinkedHashMap<>();
        final Map<String, String> dnsErr = new LinkedHashMap<>();
        for (String d : domains) {
            try {
                ips.put(d, dns.lookupA(d).get(0));
            } catch (IOException e) {
                dnsErr.put(d, ERR_DNS_PREFIX + describe(e));
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, domains.size()));
        List<Result> results = new ArrayList<>();
        try {
            for (final Presets.Preset p : presets) {
                if (progress != null) progress.step(p);
                final Strategy st = new Strategy();
                Presets.apply(st, p);
                Map<String, Future<DomainResult>> futs = new LinkedHashMap<>();
                for (final String d : domains) {
                    final String ip = ips.get(d);
                    if (ip == null) continue;
                    futs.put(d, pool.submit(new Callable<DomainResult>() {
                        @Override
                        public DomainResult call() {
                            try {
                                int ms = handshake(ip, 443, d, st, ctx, TIMEOUT_MS, ttl);
                                return new DomainResult(true, ms, null);
                            } catch (Exception e) {
                                return new DomainResult(false, 0, describe(e));
                            }
                        }
                    }));
                }
                Map<String, DomainResult> per = new LinkedHashMap<>();
                int ok = 0;
                long sum = 0;
                for (String d : domains) {
                    DomainResult r;
                    Future<DomainResult> f = futs.get(d);
                    if (f == null) {
                        r = new DomainResult(false, 0, dnsErr.get(d));
                    } else {
                        r = f.get();
                    }
                    per.put(d, r);
                    if (r.ok) {
                        ok++;
                        sum += r.ms;
                    }
                }
                results.add(new Result(p, ok, domains.size(), ok > 0 ? (int) (sum / ok) : -1, per));
            }
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    /** En cok alan adini acan (esitlikte en hizli) bolme on ayarinin kimligi; hicbiri acmiyorsa null. */
    public static String best(List<Result> results) {
        Result best = null;
        for (Result r : results) {
            if (!bypasses(results, r)) continue;
            if (best == null || r.ok > best.ok || (r.ok == best.ok && r.avgMs < best.avgMs)) best = r;
        }
        return best != null ? best.preset.id : null;
    }

    public static Result find(List<Result> results, String presetId) {
        for (Result r : results) if (r.preset.id.equals(presetId)) return r;
        return null;
    }

    /** DNS'i cozulebilen (gercekten denenebilen) site sayisi. */
    public static int reachable(Result r) {
        int n = 0;
        for (DomainResult d : r.domains.values()) {
            if (d.ok || d.err == null || !d.err.startsWith(ERR_DNS_PREFIX)) n++;
        }
        return n;
    }

    /** Denenebilen her site bolmeden de aciliyor: bu agda engelli degiller. */
    public static boolean notBlocked(List<Result> results) {
        Result base = find(results, Presets.NONE.id);
        return base != null && reachable(base) > 0 && base.ok == reachable(base);
    }

    /**
     * Yontem en az bir siteyi, bolmesiz baglantinin acamadigi halde aciyor mu. Engelli olmayan bir sitenin acilmasi
     * "calisiyor" sayilmaz.
     */
    public static boolean bypasses(List<Result> results, Result r) {
        if (r == null || r.preset == Presets.NONE || r.ok == 0) return false;
        Result base = find(results, Presets.NONE.id);
        if (base == null) return true;
        for (Map.Entry<String, DomainResult> e : r.domains.entrySet()) {
            DomainResult b = base.domains.get(e.getKey());
            if (e.getValue().ok && (b == null || !b.ok)) return true;
        }
        return false;
    }

    public static Verdict diagnose(List<Result> results) {
        int dnsErr = 0, cells = 0;
        for (Result r : results) {
            for (DomainResult d : r.domains.values()) {
                cells++;
                if (!d.ok && d.err != null && d.err.startsWith(ERR_DNS_PREFIX)) dnsErr++;
            }
        }
        if (cells == 0 || dnsErr == cells) return Verdict.DNS_FAILED;
        if (notBlocked(results)) return Verdict.NOT_BLOCKED;
        for (Result r : results) if (bypasses(results, r)) return Verdict.WORKS;
        int connect = 0, unreachable = 0, cert = 0, other = 0;
        for (Result r : results) {
            for (DomainResult d : r.domains.values()) {
                if (d.ok || d.err == null || d.err.startsWith(ERR_DNS_PREFIX)) continue;
                if (d.err.equals(ERR_CONNECT_TIMEOUT) || d.err.equals(ERR_REFUSED)) connect++;
                else if (d.err.equals(ERR_UNREACHABLE)) unreachable++;
                else if (d.err.equals(ERR_CERTIFICATE) || d.err.equals(ERR_HOSTNAME)) cert++;
                else other++;
            }
        }
        // Esitlikte DPI_UNBEATEN: kullaniciya yanlislikla "yalnizca VPN asar" denmesin.
        if (unreachable > connect && unreachable > cert && unreachable > other) return Verdict.NO_CONNECTION;
        if (connect > unreachable && connect > cert && connect > other) return Verdict.IP_BLOCK;
        if (cert > unreachable && cert > connect && cert > other) return Verdict.INTERCEPTED;
        return Verdict.DPI_UNBEATEN;
    }
}
