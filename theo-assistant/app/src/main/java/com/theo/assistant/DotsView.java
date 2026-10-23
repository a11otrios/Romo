package com.theo.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** The four coloured dots: still when idle, bouncing while listening, waving while talking. */
final class DotsView extends View {

    enum Mode { IDLE, LISTENING, THINKING, TALKING }

    private static final int[] COLOURS = {0xFF4285F4, 0xFFEA4335, 0xFFFBBC05, 0xFF34A853};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Mode mode = Mode.IDLE;
    private float level;

    DotsView(Context context) {
        super(context);
    }

    void setMode(Mode mode) {
        this.mode = mode;
        invalidate();
    }

    /** Microphone loudness, roughly 0 to 1. */
    void setLevel(float level) {
        this.level = Math.max(0f, Math.min(1f, level));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = Math.min(w / 14f, h / 4f);
        float gap = r * 3f;
        float startX = w / 2f - gap * 1.5f;
        double t = SystemClock.uptimeMillis() / 1000.0;

        for (int i = 0; i < 4; i++) {
            float dy = 0;
            float radius = r;
            switch (mode) {
                case LISTENING:
                    dy = (float) (-Math.abs(Math.sin(t * 9 + i * 1.3)) * r * (0.4 + 2.2 * level));
                    break;
                case THINKING:
                    radius = (float) (r * (0.8 + 0.25 * Math.sin(t * 6 + i * Math.PI / 2)));
                    break;
                case TALKING:
                    dy = (float) (Math.sin(t * 7 - i * 0.9) * r * 0.9);
                    break;
                default:
                    break;
            }
            paint.setColor(COLOURS[i]);
            canvas.drawCircle(startX + gap * i, h / 2f + dy, radius, paint);
        }
        if (mode != Mode.IDLE) postInvalidateOnAnimation();
    }
}
