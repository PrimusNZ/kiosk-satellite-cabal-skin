// SPDX-License-Identifier: MIT
package nz.primus.kiosk.cabal;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

/**
 * The portrait and everything derived from it once: the blurred halo, the jaw
 * strip and the jaw mesh. All positions are in the coordinates of the
 * original 1440 by 1080 artboard the eyes, jaw and halo were placed in.
 */
final class Portrait {
    /** The cropped portrait's bounds on the artboard. The picture is drawn into this rectangle. */
    static final float VIEW_LEFT = 334.6875f, VIEW_TOP = 30.03f, VIEW_RIGHT = 1105.3125f, VIEW_BOTTOM = 1000.2f;
    static final float VIEW_WIDTH = VIEW_RIGHT - VIEW_LEFT, VIEW_HEIGHT = VIEW_BOTTOM - VIEW_TOP;

    /** The jaw mesh: the mouth slot sits near (715, 807), and the mesh runs from there to past the chin. */
    static final float MESH_LEFT = 500f, MESH_RIGHT = 930f, MESH_TOP = 788f, MESH_BOTTOM = 1010f;
    static final int COLS = 24, ROWS = 30;
    static final int VERTS = (COLS + 1) * (ROWS + 1);

    /** The halo is baked at this fraction of the artboard and blurred by HALO_SIGMA artboard pixels. */
    static final float HALO_SCALE = 0.5f, HALO_SIGMA = 26f;
    static final float HALO_CX = 720f, HALO_CY = 530f;

    /** The whole portrait. */
    final Bitmap face;
    /** The portrait blurred, with its middle cleared so only the edge glow is left. */
    final Bitmap halo;
    /** The part of the portrait under the jaw mesh, one texel per mesh cell edge. */
    final Bitmap jaw;
    /** The mesh's resting vertices as x, y pairs and how far each follows the jaw down. */
    final float[] jawBase = new float[VERTS * 2];
    final float[] jawWeight = new float[VERTS];

    Portrait(Bitmap face) {
        this.face = face;
        int v = 0, k = 0;
        for (int r = 0; r <= ROWS; r++) {
            float y = MESH_TOP + (MESH_BOTTOM - MESH_TOP) * r / ROWS;
            for (int c = 0; c <= COLS; c++) {
                float x = MESH_LEFT + (MESH_RIGHT - MESH_LEFT) * c / COLS;
                jawBase[k++] = x;
                jawBase[k++] = y;
                jawWeight[v++] = follow(x, y);
            }
        }
        jaw = cropJaw(face);
        halo = bakeHalo(face);
    }

    private static float smooth(float a, float b, float x) {
        float u = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return u * u * (3 - 2 * u);
    }

    /**
     * How far down a point follows the opening, 0 to 1. Nothing moves over
     * the upper lip. The weight is full just under the slot, so the slot's dark
     * pixels stretch into the opening, then eases out toward the chin and the
     * sides so the mesh never tears at its border.
     */
    static float follow(float x, float y) {
        float vertical = smooth(809f, 819f, y) * (1f - smooth(950f, 1008f, y));
        float across = 1f - smooth(70f, 200f, Math.abs(x - 715f));
        return vertical * across;
    }

    /** A mesh bitmap spreads over the whole grid, so the strip under it is cut out. Beyond the picture's last row its edge repeats. */
    private static Bitmap cropJaw(Bitmap face) {
        float sx = face.getWidth() / VIEW_WIDTH, sy = face.getHeight() / VIEW_HEIGHT;
        int left = Math.round((MESH_LEFT - VIEW_LEFT) * sx);
        int top = Math.round((MESH_TOP - VIEW_TOP) * sy);
        int width = Math.max(1, Math.round((MESH_RIGHT - MESH_LEFT) * sx));
        int height = Math.max(1, Math.round((MESH_BOTTOM - MESH_TOP) * sy));
        left = Math.max(0, Math.min(face.getWidth() - width, left));
        Bitmap strip = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(strip);
        int inside = Math.max(0, Math.min(height, face.getHeight() - top));
        if (inside > 0) canvas.drawBitmap(face, new Rect(left, top, left + width, top + inside), new Rect(0, 0, width, inside), null);
        if (inside < height) {
            int last = face.getHeight() - 1;
            canvas.drawBitmap(face, new Rect(left, last, left + width, last + 1), new Rect(0, inside, width, height), null);
        }
        return strip;
    }

    private static Bitmap bakeHalo(Bitmap face) {
        int w = (int) Math.ceil(VIEW_WIDTH * HALO_SCALE), h = (int) Math.ceil(VIEW_HEIGHT * HALO_SCALE);
        Bitmap small = Bitmap.createScaledBitmap(face, w, h, true);
        int[] pixels = new int[w * h];
        small.getPixels(pixels, 0, w, 0, 0, w, h);
        // Premultiplied channels, so the edge of the picture fades into nothing as it blurs.
        float[][] channels = new float[4][w * h];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            float a = (p >>> 24) / 255f;
            channels[0][i] = a * 255f;
            channels[1][i] = ((p >> 16) & 255) * a;
            channels[2][i] = ((p >> 8) & 255) * a;
            channels[3][i] = (p & 255) * a;
        }
        float[] scratch = new float[w * h];
        for (float[] channel : channels) blur(channel, scratch, w, h, HALO_SIGMA * HALO_SCALE);
        float sx = VIEW_WIDTH / w, sy = VIEW_HEIGHT / h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // An ellipse a little narrower than tall: clear inside, solid out at the rim.
                float dx = (VIEW_LEFT + (x + 0.5f) * sx - HALO_CX) / 0.78f;
                float dy = VIEW_TOP + (y + 0.5f) * sy - HALO_CY;
                float d = (float) Math.sqrt(dx * dx + dy * dy) / 560f;
                float m = Math.max(0f, Math.min(1f, (d - 0.55f) / (0.86f - 0.55f)));
                int i = y * w + x;
                float a = channels[0][i] * m;
                if (a < 0.5f) {
                    pixels[i] = 0;
                    continue;
                }
                // setPixels takes straight colors.
                int r = Math.min(255, Math.round(channels[1][i] * m * 255f / a));
                int g = Math.min(255, Math.round(channels[2][i] * m * 255f / a));
                int b = Math.min(255, Math.round(channels[3][i] * m * 255f / a));
                pixels[i] = (Math.min(255, Math.round(a)) << 24) | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap blurred = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        blurred.setPixels(pixels, 0, w, 0, 0, w, h);
        return blurred;
    }

    /** A Gaussian approximated by three box blurs, with zero beyond the edges. */
    private static void blur(float[] data, float[] scratch, int w, int h, float sigma) {
        int passes = 3;
        double ideal = Math.sqrt(12.0 * sigma * sigma / passes + 1);
        int lower = (int) Math.floor(ideal);
        if (lower % 2 == 0) lower--;
        int upper = lower + 2;
        int small = (int) Math.round((12.0 * sigma * sigma - passes * lower * lower - 4.0 * passes * lower - 3.0 * passes) / (-4.0 * lower - 4.0));
        for (int pass = 0; pass < passes; pass++) {
            int radius = ((pass < small ? lower : upper) - 1) / 2;
            box(data, scratch, w, h, radius, true);
            box(scratch, data, w, h, radius, false);
        }
    }

    private static void box(float[] in, float[] out, int w, int h, int radius, boolean horizontal) {
        int lines = horizontal ? h : w, length = horizontal ? w : h;
        int step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        float scale = 1f / (2 * radius + 1);
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            float sum = 0;
            for (int i = 0; i <= radius && i < length; i++) sum += in[base + i * step];
            for (int i = 0; i < length; i++) {
                out[base + i * step] = sum * scale;
                int add = i + radius + 1, drop = i - radius;
                if (add < length) sum += in[base + add * step];
                if (drop >= 0) sum -= in[base + drop * step];
            }
        }
    }
}
