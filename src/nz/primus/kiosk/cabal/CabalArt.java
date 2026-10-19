// SPDX-License-Identifier: MIT
package nz.primus.kiosk.cabal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The CABAL portrait: a halo that breathes and swells with the voice, two
 * atomic eyes, and a jaw that drops while the assistant speaks. It animates
 * itself while attached and reads the voice from {@link CabalSkin}.
 */
final class CabalArt extends View {
    static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3;

    private static final int CYAN = 0xB7DFFF;
    private static final float ATOM_SCALE = 0.78f;
    private static final float IRIS_RADIUS = 26f, ELECTRON_RADIUS = 9f, NUCLEUS_RADIUS = 15f;
    /** Radians a second of the syllable motion: about 4.5 syllables a second. */
    private static final float SYLLABLE_RATE = 28f;
    /** The furthest the lower lip travels, in artboard pixels. */
    private static final float DROP = 30f;
    /** The plate's padding around the face, as a fraction of its shorter side. */
    private static final float PAD_FRACTION = 0.04f;

    private final CabalSkin skin;
    private final Paint facePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint jawPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint irisGlow = disc(IRIS_RADIUS, 0.14f);
    private final Paint electronGlow = disc(ELECTRON_RADIUS, 0.34f);
    private final Paint nucleusGlow = disc(NUCLEUS_RADIUS, 0.4f);
    private final Paint orbit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint electron = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nucleus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF area = new RectF();
    private final RectF oval = new RectF();
    /** Where the portrait is drawn on the artboard. */
    private final RectF view = new RectF(Portrait.VIEW_LEFT, Portrait.VIEW_TOP, Portrait.VIEW_RIGHT, Portrait.VIEW_BOTTOM);
    private final float[] jawPos = new float[Portrait.VERTS * 2];
    private boolean plate;
    private final long origin = SystemClock.uptimeMillis();

    /** Eased toward their targets every frame, so a change of mode glides. */
    private float glow = 0.2f, open;
    private double last = -1;
    /** The loudest recent level while listening and while speaking, each decaying. */
    private float micPeak, playPeak;

    /** With a plate the face sits on black that fills the view, rounded. Without one the view is the face's own box. */
    CabalArt(Context context, CabalSkin skin, boolean plate) {
        super(context);
        this.skin = skin;
        this.plate = plate;
        haloPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        jawPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        platePaint.setColor(Color.BLACK);
        orbit.setStyle(Paint.Style.STROKE);
        orbit.setStrokeWidth(1f);
        orbit.setColor(cyan(0.36f));
        electron.setColor(argb(0xD2E8FA, 0.82f));
        nucleus.setColor(argb(0xDCEEFF, 0.9f));
    }

    /** Whether the face sits on a rounded black plate that fills the view. */
    void setPlate(boolean plate) {
        this.plate = plate;
        invalidate();
    }

    private static int argb(int rgb, float alpha) {
        return Color.argb(Math.round(255 * alpha), (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
    }

    private static int cyan(float alpha) { return argb(CYAN, alpha); }

    /** A soft disc as a gradient, drawn at the origin of a translated canvas. */
    private static Paint disc(float radius, float alpha) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new RadialGradient(0, 0, radius, new int[] {cyan(alpha), cyan(alpha * 0.45f), cyan(0)}, new float[] {0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
        return paint;
    }

    /** The level against its source's recent peak: a peak decays to half in about two seconds, and the gain stops at 4x so room noise stays low. */
    private float scaled(float dt, boolean speaking) {
        float l = Math.max(0f, Math.min(1f, skin.level()));
        float peak = Math.max(l, (speaking ? playPeak : micPeak) * (float) Math.exp(-0.35 * dt));
        if (speaking) playPeak = peak; else micPeak = peak;
        return Math.max(0f, Math.min(1f, l * Math.min(4f, 0.9f / Math.max(peak, 0.1f))));
    }

    /** 0 to 1: how open the mouth should be. It stays closed unless the assistant speaks with the reactive bar on. */
    private float openTarget(double t, float l, int mode, boolean reactive) {
        if (!reactive || mode != SPEAKING) return 0f;
        float syllable = 0.5f + 0.5f * (float) Math.sin(t * SYLLABLE_RATE);
        return Math.max(0f, Math.min(1f, 0.04f + l * 0.62f + syllable * l * 0.34f));
    }

    private static float glowTarget(double t, float l, int mode) {
        switch (mode) {
            case SPEAKING: return 0.30f + l * 0.55f + 0.10f * (float) Math.sin(t * 28) * l;
            case LISTENING: return 0.26f + l * 0.30f + 0.05f * (float) Math.sin(t * 2.4);
            case THINKING: return 0.28f + 0.16f * (float) Math.sin(t * 3.6);
            default: return 0.20f + 0.08f * (float) Math.sin(t * 1.3);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        int width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;
        double t = (SystemClock.uptimeMillis() - origin) / 1000.0;
        float dt = last < 0 ? 1f : (float) Math.max(0, Math.min(0.1, t - last));
        last = t;
        int mode = skin.mode();
        boolean reactive = skin.reactive();
        boolean listening = mode == LISTENING, speaking = mode == SPEAKING;
        // Without a level to follow, the halo chatters on its own while speaking.
        float l = reactive ? (listening || speaking ? scaled(dt, speaking) : 0f)
            : speaking ? 0.3f + 0.25f * (0.5f + 0.5f * (float) Math.sin(t * 3.1)) * (0.5f + 0.5f * (float) Math.sin(t * SYLLABLE_RATE)) : 0f;
        // The halo glides quickly. The jaw opens faster than it closes.
        glow += (Math.max(0f, Math.min(1f, glowTarget(t, l, mode))) - glow) * (1f - (float) Math.exp(-20 * dt));
        float target = openTarget(t, l, mode, reactive);
        open += (target - open) * (1f - (float) Math.exp((target > open ? -40 : -14) * dt));

        area.set(0, 0, width, height);
        if (plate) {
            float side = Math.min(width, height);
            canvas.drawRoundRect(area, side * 0.1f, side * 0.1f, platePaint);
            float pad = side * PAD_FRACTION;
            area.inset(pad, pad);
        }
        // One uniform scale fits the cropped portrait, so the face is never stretched on a wide kiosk.
        float scale = Math.min(area.width() / view.width(), area.height() / view.height());
        float ox = area.left + (area.width() - view.width() * scale) / 2f;
        float oy = area.top + (area.height() - view.height() * scale) / 2f;
        canvas.save();
        canvas.translate(ox - view.left * scale, oy - view.top * scale);
        canvas.scale(scale, scale);
        Portrait portrait = skin.portrait();
        if (portrait != null) {
            canvas.drawBitmap(portrait.face, null, view, facePaint);
            drawJaw(canvas, portrait);
            drawGlow(canvas, portrait, t);
        }
        drawAtomicEye(canvas, 568f, 545f, t, 0f);
        drawAtomicEye(canvas, 865f, 545f, t, 1.37f);
        canvas.restore();
        if (isAttachedToWindow()) postInvalidateOnAnimation();
    }

    private void drawGlow(Canvas canvas, Portrait portrait, double t) {
        float swell = 1f + 0.012f * (float) Math.sin(t * 1.3) + 0.03f * glow;
        haloPaint.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, glow))));
        canvas.save();
        canvas.translate(Portrait.HALO_CX, Portrait.HALO_CY);
        canvas.scale(swell, swell);
        canvas.translate(-Portrait.HALO_CX, -Portrait.HALO_CY);
        canvas.drawBitmap(portrait.halo, null, view, haloPaint);
        canvas.restore();
    }

    /** The jaw strip replaces the portrait under it, each vertex following the opening by its weight. */
    private void drawJaw(Canvas canvas, Portrait portrait) {
        if (open < 0.015f) return;
        float dy = DROP * open;
        float[] base = portrait.jawBase, weight = portrait.jawWeight;
        for (int v = 0; v < weight.length; v++) {
            jawPos[v * 2] = base[v * 2];
            jawPos[v * 2 + 1] = base[v * 2 + 1] + dy * weight[v];
        }
        canvas.drawBitmapMesh(portrait.jaw, Portrait.COLS, Portrait.ROWS, jawPos, 0, null, 0, jawPaint);
    }

    private void drawAtomicEye(Canvas canvas, float cx, float cy, double t, float phase) {
        canvas.save();
        canvas.translate(cx, cy);
        // Drawn over the portrait's own sigil and smaller than it, so it reads as an iris.
        canvas.scale(ATOM_SCALE, ATOM_SCALE);
        canvas.drawCircle(0, 0, IRIS_RADIUS, irisGlow);
        for (int i = 0; i < 3; i++) {
            boolean even = i % 2 == 0;
            double angle = phase + t * (even ? 0.95 : -0.72) + i * Math.PI / 3;
            canvas.save();
            canvas.rotate((float) Math.toDegrees(angle));
            float ry = (i == 1 ? 17f : 23f) / 2f;
            oval.set(-21f, -ry, 21f, ry);
            canvas.drawOval(oval, orbit);
            double a = t * (even ? 2.25 : -1.8) + phase + i * 2.1;
            float ex = (float) Math.cos(a) * 21f, ey = (float) Math.sin(a) * (i == 1 ? 8.5f : 11.5f);
            canvas.save();
            canvas.translate(ex, ey);
            canvas.drawCircle(0, 0, ELECTRON_RADIUS, electronGlow);
            canvas.restore();
            canvas.drawCircle(ex, ey, 1.5f, electron);
            canvas.restore();
        }
        canvas.drawCircle(0, 0, NUCLEUS_RADIUS, nucleusGlow);
        canvas.drawCircle(0, 0, 2.8f, nucleus);
        canvas.restore();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        last = -1;
        postInvalidateOnAnimation();
    }
}
