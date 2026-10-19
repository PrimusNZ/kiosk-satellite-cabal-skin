// SPDX-License-Identifier: MIT
package nz.primus.kiosk.cabal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import java.util.Random;

/**
 * The thinking indicator: a row of random 1s and 0s that streams from right to left while the assistant works on an
 * answer. New digits enter on the right and leave on the left, both ends fade out, and a digit now and then flips.
 * It only animates while it is showing.
 */
final class BinaryStream extends View {
    private static final int CYAN = 0xB7DFFF;
    /** The stream's speed in digit widths a second, and the share of its width that fades at each end. */
    private static final float SPEED = 7.5f, FADE_EDGE = 0.3f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private char[] digits = new char[0];
    private float[] brightness = new float[0];
    private float cell, offset;
    private int maxWidth = Integer.MAX_VALUE;
    private long last;

    BinaryStream(Context context) {
        super(context);
    }

    /** Sizes in pixels: the digits' text size and the widest the stream grows. It is as wide as it can be up to that. */
    void style(float textSize, int maxWidth) {
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(textSize);
        cell = paint.measureText("0");
        this.maxWidth = maxWidth;
        digits = new char[0];
        requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        int width = Math.min(MeasureSpec.getSize(widthSpec), maxWidth);
        setMeasuredDimension(width, (int) Math.ceil(metrics.descent - metrics.ascent));
    }

    private void fill(int count) {
        digits = new char[count];
        brightness = new float[count];
        for (int i = 0; i < count; i++) {
            digits[i] = bit();
            brightness[i] = shade();
        }
    }

    private char bit() { return random.nextBoolean() ? '1' : '0'; }

    /** Each digit has its own brightness, so the stream reads as data rather than a pattern. */
    private float shade() { return 0.45f + 0.55f * random.nextFloat(); }

    @Override protected void onDraw(Canvas canvas) {
        int width = getWidth();
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0f : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        if (width > 0 && cell > 0) {
            int count = (int) Math.ceil(width / cell) + 2;
            if (digits.length != count) fill(count);
            offset += SPEED * cell * dt;
            while (offset >= cell) {
                offset -= cell;
                System.arraycopy(digits, 1, digits, 0, count - 1);
                System.arraycopy(brightness, 1, brightness, 0, count - 1);
                digits[count - 1] = bit();
                brightness[count - 1] = shade();
            }
            if (random.nextInt(5) == 0) digits[random.nextInt(count)] = bit();
            float baseline = -paint.getFontMetrics().ascent;
            float edge = width * FADE_EDGE;
            for (int i = 0; i < count; i++) {
                float x = i * cell - offset;
                float centre = x + cell / 2;
                float fade = Math.max(0f, Math.min(1f, Math.min(centre, width - centre) / edge));
                if (fade <= 0f) continue;
                paint.setColor(((Math.round(255 * fade * brightness[i]) & 255) << 24) | CYAN);
                canvas.drawText(digits, i, 1, x, baseline, paint);
            }
        }
        postInvalidateOnAnimation();
    }

    /** Each time the stream shows it starts afresh. */
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE) {
            last = 0;
            digits = new char[0];
        }
    }
}
