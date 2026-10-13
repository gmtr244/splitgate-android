// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class VpnTile extends TileService {
    private static final long PENDING_MS = 5000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable revert = new Runnable() {
        @Override
        public void run() {
            update();
        }
    };

    static void refresh(Context c) {
        try {
            TileService.requestListeningState(c, new ComponentName(c, VpnTile.class));
        } catch (RuntimeException ignore) {
            // kutucuk eklenmemişse ya da sistem reddederse uygulama etkilenmez
        }
    }

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        boolean stop = BypassVpnService.running;
        if (!stop && VpnService.prepare(this) != null) {
            openApp();
            return;
        }
        try {
            startService(new Intent(this, BypassVpnService.class)
                    .setAction(stop ? BypassVpnService.ACTION_STOP : BypassVpnService.ACTION_START));
        } catch (RuntimeException e) {
            // arka plandan servis başlatma reddedildi (API 26+): uygulamayı açarak devam et
            openApp();
            return;
        }
        // Tıklamaya hemen karşılık ver; asıl durum servis başlayınca/kapanınca refresh() ile gelir, gelmezse geri döner.
        showPending(!stop);
    }

    @Override
    public void onStopListening() {
        handler.removeCallbacks(revert);
    }

    private void showPending(boolean on) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(getString(R.string.app_name));
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(getString(R.string.tile_wait));
        t.updateTile();
        handler.removeCallbacks(revert);
        handler.postDelayed(revert, PENDING_MS);
    }

    private void openApp() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(open);
        }
    }

    private void update() {
        handler.removeCallbacks(revert);
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = BypassVpnService.running;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(getString(R.string.app_name));
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(getString(on ? R.string.tile_on : R.string.tile_off));
        }
        t.updateTile();
    }
}
