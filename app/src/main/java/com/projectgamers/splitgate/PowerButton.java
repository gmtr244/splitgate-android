package com.projectgamers.splitgate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Yuvarlak guc dugmesi: kapaliyken gri, aciksa yesil parlama halkalariyla. */
final class PowerButton extends View {
    private static final int OFF = 0xFF4A5675;
    private static final int ON = 0xFF2EE6A8;
    private static final int DISC = 0xFF141B2D;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private boolean on;

    PowerButton(Context c) {
        super(c);
    }

    void setOn(boolean v) {
        if (on != v) {
            on = v;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
        int accent = on ? ON : OFF;

        p.setStyle(Paint.Style.FILL);
        if (on) {
            p.setColor(0x122EE6A8);
            c.drawCircle(cx, cy, r, p);
            p.setColor(0x202EE6A8);
            c.drawCircle(cx, cy, r * 0.87f, p);
        }
        p.setColor(DISC);
        c.drawCircle(cx, cy, r * 0.72f, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(r * 0.045f);
        p.setColor(accent);
        c.drawCircle(cx, cy, r * 0.72f, p);

        // guc simgesi: ustte bosluklu yay + dikey cizgi
        float ir = r * 0.30f;
        p.setStrokeWidth(r * 0.07f);
        oval.set(cx - ir, cy - ir, cx + ir, cy + ir);
        c.drawArc(oval, -50f, 280f, false, p);
        c.drawLine(cx, cy - ir * 1.15f, cx, cy - ir * 0.1f, p);
    }
}
