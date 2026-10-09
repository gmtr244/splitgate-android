// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import com.projectgamers.splitgate.engine.DirectConnector;
import com.projectgamers.splitgate.engine.DohResolver;
import com.projectgamers.splitgate.engine.Presets;
import com.projectgamers.splitgate.engine.EngineLog;
import com.projectgamers.splitgate.engine.PacketIo;
import com.projectgamers.splitgate.engine.Probe;
import com.projectgamers.splitgate.engine.Strategy;
import com.projectgamers.splitgate.engine.TunEngine;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class BypassVpnService extends VpnService {
    static final String ACTION_START = "com.projectgamers.splitgate.START";
    static final String ACTION_STOP = "com.projectgamers.splitgate.STOP";
    static final String ACTION_VERIFY = "com.projectgamers.splitgate.VERIFY";
    static final String PREFS = "settings";
    static final String KEY_PRESET = "preset"; // Presets.ALL icindeki kimlik
    static final String KEY_DNS = "dns";       // birincil DoH saglayicisi: quad9 | cloudflare | google
    static final String KEY_QUIC = "quic";
    static final String KEY_AUTO = "auto_net";   // secili yontem calismazsa digerlerini dene ve calisani sec
    static final String KEY_PROBE_DOMAINS = "probe_domains";

    private static final long VERIFY_SETTLE_MS = 1500;

    /** Baglanti gercekten calisiyor mu: her baglanista ve ag degisiminde test sitelerinde denenir. */
    enum Check { IDLE, CHECKING, WORKING, NOT_BLOCKED, FAILED }

    private static final String CHANNEL_ID = "baglanti";
    private static final int NOTIF_ID = 1;

    static volatile boolean running;
    static volatile TunEngine current;
    /** SystemClock.elapsedRealtime() cinsinden baslangic zamani. */
    static volatile long startedAt;
    static volatile String activePreset;
    static volatile String networkLabel;
    static volatile Check check = Check.IDLE;
    static volatile Probe.Verdict verdict;
    /** Son kontrolde secili yontemin actigi site sayisi, ornegin "3/4". */
    static volatile String checkDetail;
    static volatile List<Probe.Result> lastResults;
    static volatile String lastResultsNet;

    private ParcelFileDescriptor tun;
    private volatile TunEngine engine;
    private DohResolver doh;
    private Thread engineThread;
    private ConnectivityManager.NetworkCallback netCallback;
    private Network lastNetwork;
    private boolean networkSeen;
    private final AtomicInteger netGen = new AtomicInteger();
    private ExecutorService probeExec;
    private ExecutorService netExec;
    private Future<?> probeTask;
    private NetworkId currentId;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_VERIFY.equals(intent.getAction())) {
            synchronized (this) {
                if (engine != null && currentId != null) {
                    cancelProbe();
                    scheduleVerify(currentId, netGen.incrementAndGet());
                } else if (engine == null) {
                    stopSelf();
                }
            }
            return START_NOT_STICKY;
        }
        startVpn();
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        LogBuffer.add("VPN permission was revoked by the system");
        stopVpn();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        cancelNotification();
        super.onDestroy();
    }

    private synchronized void startVpn() {
        if (engineThread != null) return;

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        Strategy st = new Strategy();
        NetworkId net = NetworkId.current(this);
        String saved = NetworkId.savedPreset(sp, net);
        Presets.Preset initial = Presets.byId(saved != null ? saved : sp.getString(KEY_PRESET, "sni"));
        Presets.apply(st, initial);
        st.blockQuic = sp.getBoolean(KEY_QUIC, true);
        activePreset = initial.id;
        networkLabel = net != null ? net.label : null;

        Builder b = new Builder()
                .setSession("SplitGate")
                .setMtu(1500)
                .addAddress("10.7.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.7.0.2");
        try {
            // Kendi soketlerimiz (DoH + hedefe cikis) tunele girmesin: dongu olmaz.
            b.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException e) {
            LogBuffer.add("warning: could not exclude our own app from the VPN: " + e.getMessage());
        }

        ParcelFileDescriptor pfd;
        try {
            pfd = b.establish();
        } catch (RuntimeException e) {
            LogBuffer.add("could not establish the VPN: " + e);
            stopSelf();
            return;
        }
        if (pfd == null) {
            LogBuffer.add("could not establish the VPN (permission may not have been granted)");
            stopSelf();
            return;
        }
        tun = pfd;

        FdIo io = new FdIo(pfd.getFileDescriptor());
        doh = new DohResolver(DohResolver.endpointsFor(sp.getString(KEY_DNS, "quad9")));
        engine = new TunEngine(io, st, doh, new DirectConnector(), new EngineLog() {
            @Override
            public void log(String msg) {
                LogBuffer.add(msg);
            }
        });
        engine.setSocketTtl(AndroidTtl.INSTANCE);
        current = engine;
        check = Check.IDLE;
        verdict = null;
        checkDetail = null;
        startedAt = SystemClock.elapsedRealtime();
        running = true;
        final TunEngine e = engine;
        engineThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    e.run();
                } finally {
                    onEngineEnded();
                }
            }
        }, "dpi-engine");
        engineThread.start();
        startNotifier(e);
        LogBuffer.add("VPN opened (method: " + initial.label + ")");
        probeExec = Executors.newSingleThreadExecutor();
        netExec = Executors.newSingleThreadExecutor();
        watchNetwork();
    }

    // ------------------------------------------------------------------ ag degisimi

    private void watchNetwork() {
        final ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                onDefaultNetwork(n, cm.getNetworkCapabilities(n));
            }

            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
                onDefaultNetwork(n, nc);
            }

            @Override
            public void onLost(Network n) {
                synchronized (BypassVpnService.this) {
                    if (!n.equals(lastNetwork)) return;
                    lastNetwork = null;
                    netGen.incrementAndGet();
                    cancelProbe();
                    check = Check.IDLE;
                    checkDetail = null;
                    verdict = null;
                    networkLabel = null;
                    try {
                        setUnderlyingNetworks(null);
                    } catch (RuntimeException ignore) {
                    }
                }
                LogBuffer.add("Network lost, waiting for the next one");
            }
        };
        try {
            // Uygulama VPN disinda oldugu icin bu, VPN degil fiziksel varsayilan agdir.
            cm.registerDefaultNetworkCallback(cb);
            netCallback = cb;
        } catch (RuntimeException ex) {
            LogBuffer.add("warning: cannot watch network changes: " + ex.getMessage());
        }
    }

    private void onDefaultNetwork(Network n, NetworkCapabilities nc) {
        // Android 16'da bu callback kisa bir an kendi VPN agimizi da bildirebiliyor; onu alttaki ag sanmamali.
        if (nc == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return;
        final NetworkId id;
        final int gen;
        final boolean switched;
        final TunEngine e;
        final DohResolver d;
        final String saved;
        synchronized (this) {
            e = engine;
            d = doh;
            if (e == null || n.equals(lastNetwork)) return;
            switched = networkSeen;
            networkSeen = true;
            lastNetwork = n;
            gen = netGen.incrementAndGet();
            cancelProbe();
            id = NetworkId.of(this, nc);
            currentId = id;
            try {
                setUnderlyingNetworks(new Network[]{n});
            } catch (RuntimeException ignore) {
            }
            String prevLabel = networkLabel;
            networkLabel = id.label;
            if (switched) {
                final String why = (prevLabel != null ? prevLabel : "none") + " -> " + id.label;
                runOnNetExec(new Runnable() {
                    @Override
                    public void run() {
                        e.resetConnections(why);
                        if (d != null) d.flush();
                    }
                });
            } else {
                LogBuffer.add("Network: " + id.label);
            }
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            saved = NetworkId.savedPreset(sp, id);
            if (saved != null) applyLive(Presets.byId(saved).id, "saved for " + id.label);
            scheduleVerify(id, gen);
        }
    }

    private void runOnNetExec(Runnable r) {
        ExecutorService ex = netExec;
        if (ex == null) return;
        try {
            ex.execute(r);
        } catch (RuntimeException ignore) {
        }
    }

    private void cancelProbe() {
        Future<?> f = probeTask;
        probeTask = null;
        if (f != null) f.cancel(true);
    }

    private void scheduleVerify(final NetworkId id, final int gen) {
        ExecutorService ex = probeExec;
        if (ex == null) return;
        check = Check.CHECKING;
        checkDetail = null;
        try {
            probeTask = ex.submit(new Runnable() {
                @Override
                public void run() {
                    verify(id, gen);
                }
            });
        } catch (RuntimeException ignore) {
        }
    }

    private boolean stillCurrent(int gen) {
        return gen == netGen.get() && engine != null;
    }

    /**
     * Secili yontemi test sitelerinde gercekten dener. Calismazsa ve otomatik secim aciksa (elle secilmemisse)
     * tum yontemleri dener ve en iyisine gecer. Sonuc "calisiyor" ya da nedeniyle birlikte "calismiyor" olur.
     */
    private void verify(NetworkId id, int gen) {
        try {
            Thread.sleep(VERIFY_SETTLE_MS);
        } catch (InterruptedException ie) {
            return;
        }
        if (!stillCurrent(gen)) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        List<String> domains;
        try {
            domains = Probe.parseDomains(sp.getString(KEY_PROBE_DOMAINS, null));
        } catch (IllegalArgumentException bad) {
            domains = Probe.parseDomains(null);
        }
        Presets.Preset active = Presets.byId(activePreset);
        LogBuffer.add("Checking " + active.label + " on " + id.label + " (" + domains.size() + " sites)");
        List<Probe.Result> quick = null, full = null;
        DohResolver r = new DohResolver(DohResolver.endpointsFor(sp.getString(KEY_DNS, "quad9")));
        try {
            List<Presets.Preset> two = new ArrayList<>();
            two.add(Presets.NONE);
            if (active != Presets.NONE) two.add(active);
            quick = Probe.run(r, domains, two, null, AndroidTtl.INSTANCE);
            Probe.Result a = Probe.find(quick, active.id);
            Probe.Result base = Probe.find(quick, Presets.NONE.id);
            boolean allOpen = a != null && a.preset != Presets.NONE && a.ok == Probe.reachable(a);
            boolean notBlocked = Probe.notBlocked(quick);
            boolean mayChange = sp.getBoolean(KEY_AUTO, true) && !NetworkId.isManual(sp, id);
            boolean dnsDead = Probe.diagnose(quick) == Probe.Verdict.DNS_FAILED;
            if (!allOpen && !notBlocked && mayChange && !dnsDead && stillCurrent(gen)) {
                LogBuffer.add(active.label + " does not open every site on " + id.label + ", trying all methods");
                full = Probe.runAll(r, domains, null, AndroidTtl.INSTANCE);
            }
        } catch (Exception ex) {
            if (stillCurrent(gen)) LogBuffer.add("Check failed: " + ex.getClass().getSimpleName());
            if (quick == null) {
                synchronized (this) {
                    if (stillCurrent(gen)) check = Check.IDLE;
                }
                return;
            }
        } finally {
            r.close();
        }
        synchronized (this) {
            if (!stillCurrent(gen)) return;
            List<Probe.Result> res = full != null ? full : quick;
            lastResults = res;
            lastResultsNet = id.label;
            if (full != null) {
                String best = Probe.best(full);
                Probe.Result br = best != null ? Probe.find(full, best) : null;
                Probe.Result cur = Probe.find(full, activePreset);
                if (Probe.bypasses(full, br) && (cur == null || br.ok > cur.ok)
                        && sp.getBoolean(KEY_AUTO, true) && !NetworkId.isManual(sp, id)) {
                    NetworkId.save(sp, id, best, false);
                    applyLive(best, "works best on " + id.label);
                }
            }
            Probe.Result a = Probe.find(res, activePreset);
            Probe.Result base = Probe.find(res, Presets.NONE.id);
            // Bu arada yontem elle degistirildi; onun kontrolu ayrica geliyor, denenmemis yonteme sonuc yazma.
            if (a == null && !Presets.NONE.id.equals(activePreset)) return;
            Probe.Verdict v = Probe.diagnose(res);
            verdict = v;
            if (Probe.bypasses(res, a)) {
                check = Check.WORKING;
                checkDetail = a.ok + "/" + a.total;
                LogBuffer.add("Check: " + a.preset.label + " opens " + checkDetail + " sites on " + id.label);
            } else if (Probe.notBlocked(res) && base != null) {
                check = Check.NOT_BLOCKED;
                checkDetail = base.ok + "/" + base.total;
                LogBuffer.add("Check: the test sites are not blocked on " + id.label);
            } else {
                check = Check.FAILED;
                checkDetail = null;
                LogBuffer.add("Check: NOT working on " + id.label + " (" + v + ")");
            }
        }
    }

    /** Calisan motora yontemi yeniden baslatmadan uygular; yeni baglantilar hemen bunu kullanir. */
    static void applyLive(String presetId, String why) {
        TunEngine e = current;
        Presets.Preset p = Presets.byId(presetId);
        if (e != null) Presets.apply(e.strategy(), p);
        if (!p.id.equals(activePreset)) LogBuffer.add("Method: " + p.label + " (" + why + ")");
        activePreset = p.id;
    }

    static void applyQuicLive(boolean block) {
        TunEngine e = current;
        if (e != null) e.strategy().blockQuic = block;
    }

    private synchronized void stopVpn() {
        TunEngine e = engine;
        if (e != null) e.stop(); // run() biter -> onEngineEnded() temizler
    }

    private synchronized void onEngineEnded() {
        if (netCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCallback);
            } catch (RuntimeException ignore) {
            }
            netCallback = null;
        }
        lastNetwork = null;
        networkSeen = false;
        netGen.incrementAndGet();
        cancelProbe();
        if (probeExec != null) {
            probeExec.shutdownNow();
            probeExec = null;
        }
        if (netExec != null) {
            netExec.shutdownNow();
            netExec = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignore) {
            }
            tun = null;
        }
        if (doh != null) {
            doh.close();
            doh = null;
        }
        engine = null;
        engineThread = null;
        current = null;
        running = false;
        networkLabel = null;
        currentId = null;
        check = Check.IDLE;
        verdict = null;
        checkDetail = null;
        cancelNotification(); // bagli degilken hicbir bildirim kalmaz
        LogBuffer.add("VPN closed");
        stopSelf();
    }

    // ------------------------------------------------------------------ bildirim

    /** Bagliyken saniyede bir bildirimi gunceller; baglanti kapaninca durur ve bildirimi siler. */
    private void startNotifier(final TunEngine e) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long lastUp = 0, lastDown = 0, lastT = SystemClock.elapsedRealtime();
                while (running && current == e) {
                    long now = SystemClock.elapsedRealtime();
                    long up = e.bytesUp(), down = e.bytesDown();
                    long dt = Math.max(1, now - lastT);
                    long upRate = (up - lastUp) * 1000 / dt;
                    long downRate = (down - lastDown) * 1000 / dt;
                    lastUp = up;
                    lastDown = down;
                    lastT = now;
                    if (running && current == e) postNotification(up, down, upRate, downRate, now - startedAt);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "dpi-notifier");
        t.setDaemon(true);
        t.start();
    }

    private void postNotification(long up, long down, long upRate, long downRate, long elapsedMs) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch); // zaten varsa degistirmez
            }
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, BypassVpnService.class).setAction(ACTION_STOP), flags);

            String line = "↓ " + Fmt.bytes(down) + "   ↑ " + Fmt.bytes(up);
            String perSecond = getString(R.string.per_second);
            String detail = getString(R.string.notif_detail, Fmt.bytes(down), Fmt.rate(downRate, perSecond),
                    Fmt.bytes(up), Fmt.rate(upRate, perSecond));

            nb.setSmallIcon(R.drawable.ic_stat_vpn)
                    .setContentTitle(getString(R.string.notif_title, getString(checkTitle(check)), Fmt.duration(elapsedMs)))
                    .setContentText(line)
                    .setStyle(new Notification.BigTextStyle().bigText(detail))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setColor(0xFF2EE6A8)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setContentIntent(open)
                    .addAction(0, getString(R.string.notif_stop), stop);
            if (Build.VERSION.SDK_INT < 26) nb.setPriority(Notification.PRIORITY_LOW);
            nm.notify(NOTIF_ID, nb.build());
        } catch (RuntimeException ignore) {
            // bildirim izni yoksa ya da sistem reddederse uygulama calismaya devam eder
        }
    }

    static int checkTitle(Check c) {
        switch (c) {
            case WORKING: return R.string.check_working;
            case NOT_BLOCKED: return R.string.check_not_blocked;
            case FAILED: return R.string.check_failed;
            case CHECKING: return R.string.check_checking;
            default: return R.string.status_on;
        }
    }

    private void cancelNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (RuntimeException ignore) {
        }
    }

    // ------------------------------------------------------------------ TUN G/C

    /**
     * TUN dosya tanimlayicisi uzerinde paket G/C. Tanimlayici engelleyici olmayan modda
     * (varsayilan) calisir; okuma poll() ile beklenir, boylece kapatma temiz olur.
     */
    private static final class FdIo implements PacketIo {
        private final FileDescriptor fd;
        private volatile boolean closed;

        FdIo(FileDescriptor fd) {
            this.fd = fd;
        }

        @Override
        public int read(byte[] buf) throws IOException {
            while (!closed) {
                try {
                    StructPollfd p = new StructPollfd();
                    p.fd = fd;
                    p.events = (short) OsConstants.POLLIN;
                    int r = Os.poll(new StructPollfd[]{p}, 250);
                    if (r <= 0) continue;
                    if ((p.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) return -1;
                    int n = Os.read(fd, buf, 0, buf.length);
                    if (n > 0) return n;
                    if (n < 0) return -1;
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue;
                    throw new IOException(e);
                }
            }
            return -1;
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            for (int tries = 0; tries < 50 && !closed; tries++) {
                try {
                    Os.write(fd, buf, off, len);
                    return;
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) {
                        waitWritable();
                        continue;
                    }
                    throw new IOException(e);
                }
            }
            if (!closed) throw new IOException("TUN write timed out");
        }

        private void waitWritable() {
            try {
                StructPollfd p = new StructPollfd();
                p.fd = fd;
                p.events = (short) OsConstants.POLLOUT;
                Os.poll(new StructPollfd[]{p}, 100);
            } catch (ErrnoException ignore) {
            }
        }

        @Override
        public void close() {
            closed = true; // read() en gec 250 ms icinde -1 doner
        }
    }
}
