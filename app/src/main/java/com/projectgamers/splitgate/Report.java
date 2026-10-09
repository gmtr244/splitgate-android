// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import com.projectgamers.splitgate.engine.Presets;
import com.projectgamers.splitgate.engine.Probe;
import com.projectgamers.splitgate.engine.TunEngine;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Sorun raporu: ag, ayarlar, son test sonuclari ve teshis. Gezilen site adlarini icermez. */
final class Report {
    static final String TO = "gmtr244@gmail.com";

    private static final String[] LOG_KEEP = {
            "VPN ", "Engine ", "Network", "Method", "Check", "warning", "could not", "TUN ", "packet error"
    };

    private Report() {}

    static String build(Context c, SharedPreferences sp, String userNote) {
        StringBuilder b = new StringBuilder();
        b.append("SplitGate report ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())).append('\n');
        String note = userNote != null ? userNote.trim() : "";
        b.append("\n--- Problem (written by the user) ---\n").append(note.isEmpty() ? "(not filled in)" : note).append("\n\n");
        b.append("App: ").append(version(c)).append('\n');
        b.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        NetworkId net = NetworkId.current(c);
        b.append("Network: ").append(net != null ? net.label + " [" + net.key + "]" : "none").append('\n');
        b.append("Private DNS: ").append(privateDns(c)).append('\n');
        b.append("DNS provider: ").append(sp.getString(BypassVpnService.KEY_DNS, "quad9")).append('\n');
        b.append("Block QUIC: ").append(sp.getBoolean(BypassVpnService.KEY_QUIC, true)).append('\n');
        b.append("Auto method: ").append(sp.getBoolean(BypassVpnService.KEY_AUTO, true)).append('\n');
        String active = BypassVpnService.activePreset != null ? BypassVpnService.activePreset
                : sp.getString(BypassVpnService.KEY_PRESET, "sni");
        b.append("Method: ").append(Presets.byId(active).label).append('\n');
        b.append("Connected: ").append(BypassVpnService.running).append('\n');
        b.append("Check: ").append(BypassVpnService.check);
        if (BypassVpnService.checkDetail != null) b.append(' ').append(BypassVpnService.checkDetail);
        if (BypassVpnService.verdict != null) b.append(" / ").append(BypassVpnService.verdict);
        b.append('\n');
        TunEngine eng = BypassVpnService.current;
        if (eng != null && BypassVpnService.running) {
            long up = SystemClock.elapsedRealtime() - BypassVpnService.startedAt;
            b.append("Traffic: down ").append(Fmt.bytes(eng.bytesDown())).append(", up ").append(Fmt.bytes(eng.bytesUp()))
                    .append(" in ").append(Fmt.duration(up)).append('\n');
            b.append("Connections: ").append(eng.stats()).append('\n');
        } else {
            b.append("Traffic: not connected\n");
        }
        b.append("Test sites: ").append(sp.getString(BypassVpnService.KEY_PROBE_DOMAINS, "(default)")).append('\n');
        for (String[] n : NetworkId.savedList(sp)) {
            b.append("Saved: ").append(n[0]).append(" -> ").append(Presets.byId(n[1]).label).append(" (").append(n[2]).append(")\n");
        }

        List<Probe.Result> res = BypassVpnService.lastResults;
        b.append("\n--- Last test");
        if (BypassVpnService.lastResultsNet != null) b.append(" (").append(BypassVpnService.lastResultsNet).append(')');
        b.append(" ---\n");
        if (res == null || res.isEmpty()) {
            b.append("no test yet\n");
        } else {
            b.append("Verdict: ").append(Probe.diagnose(res)).append('\n');
            for (Probe.Result r : res) {
                b.append(String.format(Locale.US, "%-22s %d/%d", r.preset.label, r.ok, r.total));
                if (r.avgMs >= 0) b.append("  ").append(r.avgMs).append(" ms");
                b.append('\n');
                for (Map.Entry<String, Probe.DomainResult> e : r.domains.entrySet()) {
                    Probe.DomainResult d = e.getValue();
                    b.append("    ").append(e.getKey()).append(": ").append(d.ok ? "ok " + d.ms + " ms" : d.err).append('\n');
                }
            }
        }

        b.append("\n--- Log (connection lines removed) ---\n");
        int disorderOff = 0;
        for (String line : LogBuffer.dump().split("\n")) {
            String msg = line.length() > 10 ? line.substring(10) : line;
            if (msg.contains("disorder unavailable")) {
                disorderOff++;
                continue;
            }
            for (String k : LOG_KEEP) {
                if (msg.startsWith(k)) {
                    b.append(line).append('\n');
                    break;
                }
            }
        }
        if (disorderOff > 0) b.append("disorder unavailable on ").append(disorderOff).append(" connections\n");
        return b.toString();
    }

    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private static String privateDns(Context c) {
        try {
            String mode = Settings.Global.getString(c.getContentResolver(), "private_dns_mode");
            return mode != null ? mode : "unknown";
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /** E-posta uygulamasini alici, konu ve metin dolu olarak acar; yoksa false. */
    static boolean email(Context c, String body) {
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + TO));
        i.putExtra(Intent.EXTRA_EMAIL, new String[]{TO});
        i.putExtra(Intent.EXTRA_SUBJECT, c.getString(R.string.report_subject, version(c)));
        i.putExtra(Intent.EXTRA_TEXT, body);
        try {
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    static void share(Context c, String body) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, c.getString(R.string.report_subject, version(c)));
        i.putExtra(Intent.EXTRA_TEXT, body);
        c.startActivity(Intent.createChooser(i, c.getString(R.string.report_share)));
    }
}
