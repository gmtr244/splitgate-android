// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.projectgamers.splitgate.engine.Presets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Alttaki agi (Wi-Fi, mobil operator, Ethernet) tanimlar ve her ag icin secilen yontemi saklar. */
final class NetworkId {
    private static final String PRESET_PREFIX = "net_preset:";
    private static final String MANUAL_PREFIX = "net_manual:";
    private static final String LABEL_PREFIX = "net_label:";

    final String key;
    final String label;

    private NetworkId(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** Uygulama kendi VPN'inin disinda oldugu icin bagliyken de fiziksel agi dondurur. */
    static NetworkId current(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network n = cm.getActiveNetwork();
        if (n == null) return null;
        return of(c, cm.getNetworkCapabilities(n));
    }

    static NetworkId of(Context c, NetworkCapabilities nc) {
        if (nc == null) return null;
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return new NetworkId("wifi", "Wi-Fi");
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return new NetworkId("eth", "Ethernet");
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            String code = "", name = "";
            try {
                TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) {
                    int sub = SubscriptionManager.getDefaultDataSubscriptionId();
                    if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm = tm.createForSubscriptionId(sub);
                    code = clean(tm.getNetworkOperator());
                    name = clean(tm.getNetworkOperatorName());
                }
            } catch (RuntimeException ignore) {
            }
            String mobile = c.getString(R.string.net_mobile);
            return new NetworkId(code.isEmpty() ? "cell" : "cell:" + code,
                    name.isEmpty() ? mobile : mobile + " (" + name + ")");
        }
        return new NetworkId("other", c.getString(R.string.net_other));
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    static String savedPreset(SharedPreferences sp, NetworkId n) {
        return n == null ? null : sp.getString(PRESET_PREFIX + n.key, null);
    }

    static boolean isManual(SharedPreferences sp, NetworkId n) {
        return n != null && sp.getBoolean(MANUAL_PREFIX + n.key, false);
    }

    static void save(SharedPreferences sp, NetworkId n, String presetId, boolean manual) {
        SharedPreferences.Editor e = sp.edit().putString(BypassVpnService.KEY_PRESET, presetId);
        if (n != null) {
            e.putString(PRESET_PREFIX + n.key, presetId)
                    .putBoolean(MANUAL_PREFIX + n.key, manual)
                    .putString(LABEL_PREFIX + n.key, n.label);
        }
        e.apply();
    }

    static void forgetAll(SharedPreferences sp) {
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith(PRESET_PREFIX) || k.startsWith(MANUAL_PREFIX) || k.startsWith(LABEL_PREFIX)) e.remove(k);
        }
        e.apply();
    }

    /** Yontemi kayitli her ag icin {etiket, on ayar kimligi, "manual"|"auto"}. */
    static List<String[]> savedList(SharedPreferences sp) {
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, ?> en : sp.getAll().entrySet()) {
            if (!en.getKey().startsWith(PRESET_PREFIX) || !(en.getValue() instanceof String)) continue;
            String key = en.getKey().substring(PRESET_PREFIX.length());
            String label = sp.getString(LABEL_PREFIX + key, key);
            String preset = Presets.byId((String) en.getValue()).id;
            out.add(new String[]{label, preset, sp.getBoolean(MANUAL_PREFIX + key, false) ? "manual" : "auto"});
        }
        return out;
    }
}
