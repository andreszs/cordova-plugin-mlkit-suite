package com.andreszs.plugins.mlkitsuite;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

final class BarcodeIconDrawable extends Drawable {
    static final int CLOSE = 0;
    static final int TORCH = 1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int icon;
    private final boolean active;

    BarcodeIconDrawable(int icon, boolean active) {
        this.icon = icon;
        this.active = active;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float size = Math.min(bounds.width(), bounds.height());
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xf5ffffff);
        canvas.drawCircle(cx, cy, size * 0.46f, paint);

        paint.setColor(active ? 0xfffbbc04 : 0xff7b8497);
        if (icon == CLOSE) drawClose(canvas, cx, cy, size);
        else drawTorch(canvas, cx, cy, size);
    }

    private void drawClose(Canvas canvas, float cx, float cy, float size) {
        float arm = size * 0.17f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.085f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, paint);
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, paint);
    }

    private void drawTorch(Canvas canvas, float cx, float cy, float size) {
        Path path = new Path();
        path.moveTo(cx + size * 0.08f, cy - size * 0.25f);
        path.lineTo(cx - size * 0.15f, cy + size * 0.02f);
        path.lineTo(cx - size * 0.01f, cy + size * 0.02f);
        path.lineTo(cx - size * 0.08f, cy + size * 0.25f);
        path.lineTo(cx + size * 0.16f, cy - size * 0.06f);
        path.lineTo(cx + size * 0.02f, cy - size * 0.06f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
