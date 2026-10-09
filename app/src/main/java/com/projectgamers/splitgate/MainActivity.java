// Copyright (C) 2026 Project Gamers
// SPDX-License-Identifier: GPL-3.0-or-later
package com.projectgamers.splitgate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.projectgamers.splitgate.engine.DohResolver;
import com.projectgamers.splitgate.engine.Presets;
import com.projectgamers.splitgate.engine.Probe;
import com.projectgamers.splitgate.engine.TunEngine;

import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1;
    private static final int REQ_NOTIF = 2;

    // Renkler
    private static final int BG = 0xFF0B1020;
    private static final int CARD = 0xFF141B2D;
    private static final int STROKE = 0xFF222C45;
    private static final int CHIP_OFF = 0xFF1B2440;
    private static final int TEXT = 0xFFEAF0FF;
    private static final int MUTED = 0xFF8A95B3;
    private static final int ACCENT = 0xFF2EE6A8;
    private static final int ON_ACCENT = 0xFF06241B;
    private static final int AMBER = 0xFFFFC857;
    private static final int WARN = 0xFFFF6B6B;

    private static final String[] DNS_IDS = {"quad9", "cloudflare", "google"};
    private static final String[] DNS_NAMES = {"Quad9", "Cloudflare", "Google"};
    private static final String KEY_PROBE_DOMAINS = BypassVpnService.KEY_PROBE_DOMAINS;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private PowerButton power;
    private TextView statusTitle, statusSub;
    private TextView downVal, downSub, upVal, upSub, connVal, splitVal, dnsVal;
    private final LinearLayout[] presetRows = new LinearLayout[Presets.ALL.length];
    private final TextView[] presetTitles = new TextView[Presets.ALL.length];
    private final TextView[] presetDescs = new TextView[Presets.ALL.length];
    private final TextView[] dnsChips = new TextView[DNS_IDS.length];
    private TextView savedNets;
    private String shownPreset;
    private TextView testBtn, testStatus, testResults, applyBtn;
    private EditText testDomains;
    private volatile boolean testing;
    private String bestId;
    private TextView logView, logArrow;
    private View logBody;
    private boolean logOpen;
    private int shownLogVersion = -1;

    // hiz hesabi
    private TunEngine lastEngine;
    private long lastDown, lastUp, lastTick, downRate, upRate;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------------ kurulum

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(BypassVpnService.PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(root);

        buildHeader(root);
        buildPower(root);
        buildStats(root);
        buildSettings(root);
        buildTest(root);
        buildInfo(root);
        buildLog(root);
        TextView copyright = tv(getString(R.string.copyright), 11, MUTED, false);
        copyright.setGravity(Gravity.CENTER);
        root.addView(copyright, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, dp(16), 0, 0));

        setContentView(scroll);
        String forNet = NetworkId.savedPreset(prefs, NetworkId.current(this));
        highlightPreset(forNet != null ? forNet : prefs.getString(BypassVpnService.KEY_PRESET, "sni"));
        selectDns(prefs.getString(BypassVpnService.KEY_DNS, "quad9"), false);
        if (LocaleHelper.saved(this) == null) showLanguageDialog(true);
    }

    private void showLanguageDialog(final boolean firstRun) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_language)
                .setCancelable(!firstRun)
                .setItems(LocaleHelper.NAMES, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        LocaleHelper.save(MainActivity.this, LocaleHelper.CODES[which]);
                        recreate();
                    }
                })
                .show();
    }

    private String presetLabel(Presets.Preset p) {
        switch (p.id) {
            case "none": return getString(R.string.preset_none);
            case "hafif": return getString(R.string.preset_hafif);
            case "sni": return getString(R.string.preset_sni);
            case "agresif": return getString(R.string.preset_agresif);
            case "oob": return getString(R.string.preset_oob);
            case "tlskayit": return getString(R.string.preset_tlskayit);
            case "disorder": return getString(R.string.preset_disorder);
            case "disordersni": return getString(R.string.preset_disordersni);
            case "tlsdisorder": return getString(R.string.preset_tlsdisorder);
            default: return p.label;
        }
    }

    private String presetDesc(Presets.Preset p) {
        switch (p.id) {
            case "none": return getString(R.string.preset_none_desc);
            case "hafif": return getString(R.string.preset_hafif_desc);
            case "sni": return getString(R.string.preset_sni_desc);
            case "agresif": return getString(R.string.preset_agresif_desc);
            case "oob": return getString(R.string.preset_oob_desc);
            case "tlskayit": return getString(R.string.preset_tlskayit_desc);
            case "disorder": return getString(R.string.preset_disorder_desc);
            case "disordersni": return getString(R.string.preset_disordersni_desc);
            case "tlsdisorder": return getString(R.string.preset_tlsdisorder_desc);
            default: return p.desc;
        }
    }

    private String errText(String err) {
        if (err == null) return "";
        if (err.equals(Probe.ERR_TIMEOUT)) return getString(R.string.err_timeout);
        if (err.equals(Probe.ERR_CERTIFICATE)) return getString(R.string.err_certificate);
        if (err.equals(Probe.ERR_HOSTNAME)) return getString(R.string.err_hostname);
        if (err.equals(Probe.ERR_CONNECT_TIMEOUT)) return getString(R.string.err_connect_timeout);
        if (err.equals(Probe.ERR_RESET)) return getString(R.string.err_reset);
        if (err.equals(Probe.ERR_REFUSED)) return getString(R.string.err_refused);
        if (err.equals(Probe.ERR_UNREACHABLE)) return getString(R.string.err_unreachable);
        if (err.equals(Probe.ERR_CLOSED)) return getString(R.string.err_closed);
        if (err.startsWith(Probe.ERR_DNS_PREFIX)) return "DNS: " + err.substring(Probe.ERR_DNS_PREFIX.length());
        return err;
    }

    private String verdictText(Probe.Verdict v) {
        if (v == null) return "";
        switch (v) {
            case WORKS: return getString(R.string.verdict_works);
            case NOT_BLOCKED: return getString(R.string.verdict_not_blocked);
            case DNS_FAILED: return getString(R.string.verdict_dns);
            case NO_CONNECTION: return getString(R.string.verdict_no_connection);
            case IP_BLOCK: return getString(R.string.verdict_ip_block);
            case INTERCEPTED: return getString(R.string.verdict_intercepted);
            default: return getString(R.string.verdict_dpi);
        }
    }

    private void buildHeader(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        row.addView(logo, lp(dp(40), dp(40), 0, 0, 0, 0, 0));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tv(getString(R.string.app_name), 22, TEXT, true));
        col.addView(tv(getString(R.string.tagline), 12, MUTED, false));
        row.addView(col, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, dp(12), 0, 0, 0));
        row.addView(smallButton("🌐 " + LocaleHelper.nameOf(LocaleHelper.code(this)), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLanguageDialog(false);
            }
        }));

        root.addView(row, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, dp(8)));
    }

    private void buildPower(LinearLayout root) {
        power = new PowerButton(this);
        power.setContentDescription(getString(R.string.toggle_cd));
        power.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToggle();
            }
        });
        LinearLayout.LayoutParams plp = lp(dp(210), dp(210), 0, 0, dp(10), 0, dp(6));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(power, plp);

        statusTitle = tv(getString(R.string.status_off), 24, MUTED, true);
        statusTitle.setGravity(Gravity.CENTER);
        root.addView(statusTitle, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, 0));

        statusSub = tv(getString(R.string.status_hint), 13, MUTED, false);
        statusSub.setGravity(Gravity.CENTER);
        root.addView(statusSub, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, dp(18)));
    }

    private void buildStats(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        downVal = tv("0 B", 22, TEXT, true);
        downSub = tv("↓ —", 12, ACCENT, false);
        upVal = tv("0 B", 22, TEXT, true);
        upSub = tv("↑ —", 12, ACCENT, false);

        row.addView(statCard(getString(R.string.stat_downloaded), downVal, downSub), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, 0, 0, dp(6), 0));
        row.addView(statCard(getString(R.string.stat_uploaded), upVal, upSub), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, dp(6), 0, 0, 0));
        root.addView(row, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, dp(12)));

        LinearLayout mini = card();
        LinearLayout mrow = new LinearLayout(this);
        mrow.setOrientation(LinearLayout.HORIZONTAL);
        connVal = tv("0", 18, TEXT, true);
        splitVal = tv("0", 18, TEXT, true);
        dnsVal = tv("0", 18, TEXT, true);
        mrow.addView(miniStat(getString(R.string.stat_active), connVal), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        mrow.addView(miniStat(getString(R.string.stat_split), splitVal), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        mrow.addView(miniStat(getString(R.string.stat_dns), dnsVal), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        mini.addView(mrow);
        root.addView(mini, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, dp(12)));
    }

    private void buildSettings(LinearLayout root) {
        LinearLayout c = card();
        c.addView(tv(getString(R.string.section_method), 11, MUTED, true));

        for (int i = 0; i < Presets.ALL.length; i++) {
            final Presets.Preset pr = Presets.ALL[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            TextView title = tv(presetLabel(pr), 14, MUTED, true);
            TextView desc = tv(presetDesc(pr), 11, MUTED, false);
            row.addView(title);
            row.addView(desc);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    choosePreset(pr.id);
                }
            });
            presetRows[i] = row;
            presetTitles[i] = title;
            presetDescs[i] = desc;
            c.addView(row, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, i == 0 ? dp(10) : dp(6), 0, 0));
        }

        Switch auto = new Switch(this);
        auto.setChecked(prefs.getBoolean(BypassVpnService.KEY_AUTO, true));
        auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(BypassVpnService.KEY_AUTO, checked).apply();
            }
        });
        c.addView(switchRow(getString(R.string.auto_title), getString(R.string.auto_desc), auto),
                lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, dp(16), 0, 0));

        savedNets = tv("", 12, MUTED, false);
        c.addView(savedNets, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, dp(10), 0, 0));
        c.addView(smallButton(getString(R.string.nets_forget), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                NetworkId.forgetAll(prefs);
                renderSavedNets();
                Toast.makeText(MainActivity.this, R.string.nets_forgotten, Toast.LENGTH_SHORT).show();
            }
        }), lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, dp(8), 0, 0));
        renderSavedNets();

        c.addView(tv(getString(R.string.section_dns), 11, MUTED, true), lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, dp(16), 0, 0));
        LinearLayout dnsRow = new LinearLayout(this);
        dnsRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < DNS_IDS.length; i++) {
            final String id = DNS_IDS[i];
            TextView chip = tv(DNS_NAMES[i], 13, MUTED, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(6), dp(11), dp(6), dp(11));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectDns(id, true);
                }
            });
            dnsChips[i] = chip;
            dnsRow.addView(chip, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, i == 0 ? 0 : dp(4), 0, i == DNS_IDS.length - 1 ? 0 : dp(4), 0));
        }
        c.addView(dnsRow, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, dp(8), 0, 0));

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(BypassVpnService.KEY_QUIC, true));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(BypassVpnService.KEY_QUIC, checked).apply();
                BypassVpnService.applyQuicLive(checked);
            }
        });
        c.addView(switchRow(getString(R.string.quic_title), getString(R.string.quic_desc), sw), lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, dp(16), 0, 0));

        root.addView(c, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, dp(12)));
    }

    private LinearLayout switchRow(String title, String desc, Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(tv(title, 15, TEXT, true));
        txt.addView(tv(desc, 12, MUTED, false));
        row.addView(txt, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        row.addView(sw);
        return row;
    }

    private void renderSavedNets() {
        List<String[]> nets = NetworkId.savedList(prefs);
        if (nets.isEmpty()) {
            savedNets.setText(getString(R.string.nets_none));
            return;
        }
        StringBuilder sb = new StringBuilder(getString(R.string.nets_title));
        for (String[] n : nets) {
            sb.append("\n• ").append(n[0]).append(" → ").append(presetLabel(Presets.byId(n[1])))
                    .append(n[2].equals("manual") ? getString(R.string.nets_manual) : getString(R.string.nets_auto));
        }
        savedNets.setText(sb.toString());
    }

    private void buildTest(LinearLayout root) {
        LinearLayout c = card();
        c.addView(tv(getString(R.string.section_test), 11, MUTED, true));
        TextView info = tv(getString(R.string.test_info), 12, MUTED, false);
        info.setPadding(0, dp(6), 0, dp(10));
        c.addView(info);

        testDomains = new EditText(this);
        testDomains.setSingleLine(true);
        testDomains.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        testDomains.setHint(R.string.test_domains_hint);
        testDomains.setHintTextColor(MUTED);
        testDomains.setTextColor(TEXT);
        testDomains.setTextSize(13);
        testDomains.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable box = new GradientDrawable();
        box.setColor(CHIP_OFF);
        box.setCornerRadius(dp(12));
        testDomains.setBackground(box);
        testDomains.setText(prefs.getString(KEY_PROBE_DOMAINS, TextUtils.join(", ", Probe.DEFAULT_DOMAINS)));
        c.addView(testDomains, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, dp(10)));

        testBtn = smallButton(getString(R.string.test_run), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTest();
            }
        });
        c.addView(testBtn);

        testStatus = tv("", 12, ACCENT, false);
        testStatus.setPadding(0, dp(10), 0, 0);
        testStatus.setVisibility(View.GONE);
        c.addView(testStatus);

        testResults = tv("", 12, TEXT, false);
        testResults.setTypeface(Typeface.MONOSPACE);
        testResults.setPadding(0, dp(8), 0, 0);
        testResults.setVisibility(View.GONE);
        c.addView(testResults);

        applyBtn = smallButton(getString(R.string.test_apply), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bestId != null) choosePreset(bestId);
            }
        });
        applyBtn.setVisibility(View.GONE);
        c.addView(applyBtn, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, dp(10), 0, 0));

        TextView rinfo = tv(getString(R.string.report_info), 12, MUTED, false);
        rinfo.setPadding(0, dp(14), 0, dp(8));
        c.addView(rinfo);
        LinearLayout rrow = new LinearLayout(this);
        rrow.setOrientation(LinearLayout.HORIZONTAL);
        rrow.addView(smallButton(getString(R.string.report_send), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askReport(false);
            }
        }), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, 0, 0, dp(4), 0));
        rrow.addView(smallButton(getString(R.string.report_share), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askReport(true);
            }
        }), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, dp(4), 0, 0, 0));
        c.addView(rrow);

        root.addView(c, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, dp(12)));
    }

    private void askReport(final boolean share) {
        final EditText note = new EditText(this);
        note.setHint(R.string.report_note_hint);
        note.setMinLines(3);
        note.setGravity(Gravity.TOP | Gravity.START);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(note, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, 0));
        new AlertDialog.Builder(this)
                .setTitle(R.string.report_note_title)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(share ? R.string.report_share : R.string.report_send, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String body = Report.build(MainActivity.this, prefs, note.getText().toString());
                        if (share) {
                            Report.share(MainActivity.this, body);
                        } else if (!Report.email(MainActivity.this, body)) {
                            Toast.makeText(MainActivity.this, R.string.report_no_mail, Toast.LENGTH_LONG).show();
                            Report.share(MainActivity.this, body);
                        }
                    }
                })
                .show();
    }

    private void runTest() {
        if (testing) return;
        final List<String> domains;
        try {
            domains = Probe.parseDomains(testDomains.getText().toString());
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, getString(R.string.test_bad_domain, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        String joined = TextUtils.join(", ", domains);
        prefs.edit().putString(KEY_PROBE_DOMAINS, joined).apply();
        testDomains.setText(joined);
        testing = true;
        bestId = null;
        testBtn.setText(getString(R.string.test_running));
        testStatus.setVisibility(View.VISIBLE);
        testStatus.setText(getString(R.string.test_starting));
        testResults.setVisibility(View.GONE);
        applyBtn.setVisibility(View.GONE);
        final String dnsId = prefs.getString(BypassVpnService.KEY_DNS, "quad9");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text;
                String best = null;
                DohResolver resolver = new DohResolver(DohResolver.endpointsFor(dnsId));
                try {
                    List<Probe.Result> res = Probe.runAll(resolver, domains,
                            new Probe.Progress() {
                                @Override
                                public void step(final Presets.Preset preset) {
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            testStatus.setText(getString(R.string.test_progress, presetLabel(preset)));
                                        }
                                    });
                                }
                            }, AndroidTtl.INSTANCE);
                    best = Probe.best(res);
                    text = render(res);
                    BypassVpnService.lastResults = res;
                    NetworkId tn = NetworkId.current(MainActivity.this);
                    BypassVpnService.lastResultsNet = tn != null ? tn.label : null;
                    if (best == null) text = verdictText(Probe.diagnose(res)) + "\n\n" + text;
                } catch (Exception e) {
                    text = getString(R.string.test_error, e.getClass().getSimpleName()
                            + (e.getMessage() != null ? ": " + e.getMessage() : ""));
                } finally {
                    resolver.close();
                }
                final String fText = text;
                final String fBest = best;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        testing = false;
                        testBtn.setText(getString(R.string.test_run));
                        bestId = fBest;
                        testResults.setText(fText);
                        testResults.setVisibility(View.VISIBLE);
                        if (fBest != null) {
                            testStatus.setText(getString(R.string.test_recommended, presetLabel(Presets.byId(fBest))));
                            applyBtn.setVisibility(View.VISIBLE);
                        } else {
                            testStatus.setText(getString(R.string.test_none));
                        }
                    }
                });
            }
        }, "dpi-probe").start();
    }

    private String render(List<Probe.Result> res) {
        StringBuilder sb = new StringBuilder();
        for (Probe.Result r : res) {
            sb.append(String.format(java.util.Locale.US, "%-11s %d/%d", presetLabel(r.preset), r.ok, r.total));
            if (r.avgMs >= 0) sb.append("  ").append(r.avgMs).append(" ms");
            sb.append('\n');
        }
        for (Probe.Result r : res) {
            if (r.preset != Presets.NONE) continue;
            for (Map.Entry<String, Probe.DomainResult> e : r.domains.entrySet()) {
                if (!e.getValue().ok) sb.append("\n").append(getString(R.string.test_baseline_err, e.getKey(), errText(e.getValue().err)));
            }
        }
        return sb.toString().trim();
    }

    private void buildInfo(LinearLayout root) {
        LinearLayout c = card();
        c.addView(tv(getString(R.string.section_privacy), 11, MUTED, true));
        TextView t = tv(getString(R.string.privacy_text), 12, MUTED, false);
        t.setPadding(0, dp(6), 0, 0);
        c.addView(t);
        root.addView(c, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, dp(12)));
    }

    private void buildLog(LinearLayout root) {
        LinearLayout c = card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = tv(getString(R.string.section_log), 11, MUTED, true);
        head.addView(title, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        logArrow = tv("▸", 14, MUTED, true);
        head.addView(logArrow);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logOpen = !logOpen;
                logBody.setVisibility(logOpen ? View.VISIBLE : View.GONE);
                logArrow.setText(logOpen ? "▾" : "▸");
                shownLogVersion = -1;
                refresh();
            }
        });
        c.addView(head);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        logBody = body;

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(smallButton(getString(R.string.log_copy), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("dpi-log", LogBuffer.dump()));
                Toast.makeText(MainActivity.this, R.string.log_copied, Toast.LENGTH_SHORT).show();
            }
        }), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, 0, 0, dp(4), 0));
        btns.addView(smallButton(getString(R.string.log_clear), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogBuffer.clear();
                refresh();
            }
        }), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, dp(4), 0, 0, 0));
        body.addView(btns, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, dp(10), 0, dp(8)));

        logView = tv("", 11, MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        body.addView(logView);

        c.addView(body);
        root.addView(c, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, 0));
    }

    // ------------------------------------------------------------------ gorsel yardimcilar

    private TextView tv(String text, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), STROKE);
        c.setBackground(g);
        return c;
    }

    private LinearLayout statCard(String label, TextView value, TextView sub) {
        LinearLayout c = card();
        c.addView(tv(label, 11, MUTED, true));
        value.setPadding(0, dp(6), 0, 0);
        c.addView(value);
        c.addView(sub);
        return c;
    }

    private LinearLayout miniStat(String label, TextView value) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        value.setGravity(Gravity.CENTER);
        c.addView(value);
        TextView l = tv(label, 11, MUTED, false);
        l.setGravity(Gravity.CENTER);
        c.addView(l);
        return c;
    }

    private TextView smallButton(String text, View.OnClickListener l) {
        TextView t = tv(text, 13, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(10), dp(8), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CHIP_OFF);
        g.setCornerRadius(dp(12));
        t.setBackground(g);
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = weight > 0
                ? new LinearLayout.LayoutParams(w, h, weight)
                : new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private void choosePreset(String id) {
        String sel = Presets.byId(id).id;
        NetworkId.save(prefs, NetworkId.current(this), sel, true);
        BypassVpnService.applyLive(sel, "chosen by hand");
        if (BypassVpnService.running) {
            startService(new Intent(this, BypassVpnService.class).setAction(BypassVpnService.ACTION_VERIFY));
        }
        highlightPreset(sel);
        renderSavedNets();
    }

    private void highlightPreset(String id) {
        String sel = Presets.byId(id).id;
        shownPreset = sel;
        for (int i = 0; i < Presets.ALL.length; i++) {
            boolean on = Presets.ALL[i].id.equals(sel);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(12));
            g.setColor(on ? ACCENT : CHIP_OFF);
            presetRows[i].setBackground(g);
            presetTitles[i].setTextColor(on ? ON_ACCENT : TEXT);
            presetDescs[i].setTextColor(on ? ON_ACCENT : MUTED);
        }
    }

    private void selectDns(String id, boolean fromUser) {
        String sel = DNS_IDS[0];
        for (String d : DNS_IDS) if (d.equals(id)) sel = d;
        if (fromUser) {
            prefs.edit().putString(BypassVpnService.KEY_DNS, sel).apply();
            notifyRestartNeeded();
        }
        for (int i = 0; i < DNS_IDS.length; i++) {
            boolean on = DNS_IDS[i].equals(sel);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(12));
            g.setColor(on ? ACCENT : CHIP_OFF);
            dnsChips[i].setBackground(g);
            dnsChips[i].setTextColor(on ? ON_ACCENT : MUTED);
        }
    }

    private void notifyRestartNeeded() {
        if (BypassVpnService.running) {
            Toast.makeText(this, R.string.restart_needed, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ yasam dongusu

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    // ------------------------------------------------------------------ baslat / durdur

    private void onToggle() {
        if (BypassVpnService.running) {
            startService(new Intent(this, BypassVpnService.class).setAction(BypassVpnService.ACTION_STOP));
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            }, 400);
            return;
        }
        // Android 13+: durum bildirimi icin izin iste (reddedilse de VPN calisir)
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        prepareAndStart();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIF) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this, R.string.notif_denied, Toast.LENGTH_LONG).show();
        }
        prepareAndStart();
    }

    private void prepareAndStart() {
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN);
        } else {
            startVpn();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) return;
        if (resultCode == RESULT_OK) startVpn();
        else LogBuffer.add("VPN permission was not granted");
    }

    private void startVpn() {
        startService(new Intent(this, BypassVpnService.class).setAction(BypassVpnService.ACTION_START));
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 500);
    }

    // ------------------------------------------------------------------ ekran guncelleme

    private void refresh() {
        boolean on = BypassVpnService.running;
        TunEngine e = BypassVpnService.current;
        power.setOn(on);

        if (on && e != null) {
            long now = SystemClock.elapsedRealtime();
            long down = e.bytesDown(), up = e.bytesUp();
            if (e != lastEngine) {
                lastEngine = e;
                lastDown = down;
                lastUp = up;
                lastTick = now;
                downRate = 0;
                upRate = 0;
            } else if (now - lastTick >= 900) {
                long dt = now - lastTick;
                downRate = (down - lastDown) * 1000 / dt;
                upRate = (up - lastUp) * 1000 / dt;
                lastDown = down;
                lastUp = up;
                lastTick = now;
            }
            String activeId = BypassVpnService.activePreset;
            Presets.Preset active = Presets.byId(activeId != null ? activeId : prefs.getString(BypassVpnService.KEY_PRESET, "sni"));
            if (!active.id.equals(shownPreset)) {
                highlightPreset(active.id);
                renderSavedNets();
            }
            BypassVpnService.Check ck = BypassVpnService.check;
            statusTitle.setText(getString(BypassVpnService.checkTitle(ck)));
            statusTitle.setTextColor(ck == BypassVpnService.Check.FAILED ? WARN
                    : (ck == BypassVpnService.Check.CHECKING || ck == BypassVpnService.Check.IDLE) ? AMBER : ACCENT);
            StringBuilder sub = new StringBuilder(Fmt.duration(now - BypassVpnService.startedAt));
            String net = BypassVpnService.networkLabel;
            if (net != null) sub.append(" · ").append(net);
            sub.append(" · ").append(presetLabel(active));
            String detail = BypassVpnService.checkDetail;
            if (detail != null && ck == BypassVpnService.Check.WORKING) sub.append(" · ").append(getString(R.string.check_sites, detail));
            if (ck == BypassVpnService.Check.FAILED) sub.append("\n").append(verdictText(BypassVpnService.verdict));
            statusSub.setText(sub.toString());
            downVal.setText(Fmt.bytes(down));
            downSub.setText("↓ " + Fmt.rate(downRate, getString(R.string.per_second)));
            upVal.setText(Fmt.bytes(up));
            upSub.setText("↑ " + Fmt.rate(upRate, getString(R.string.per_second)));
            connVal.setText(String.valueOf(e.activeTcp()));
            splitVal.setText(String.valueOf(e.splitCount()));
            dnsVal.setText(String.valueOf(e.dnsCount()));
        } else {
            lastEngine = null;
            statusTitle.setText(getString(R.string.status_off));
            statusTitle.setTextColor(MUTED);
            statusSub.setText(getString(R.string.status_hint));
            downSub.setText("↓ —");
            upSub.setText("↑ —");
            connVal.setText("0");
        }

        int v = LogBuffer.version();
        if (logOpen && v != shownLogVersion) {
            shownLogVersion = v;
            logView.setText(LogBuffer.dump(80));
        }
    }
}
