package com.andreszs.plugins.mlkitsuite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

final class BarcodeOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF guide = new RectF();
    private final String instruction;
    private String message = "Looking for a barcode";
    private boolean candidateVisible;

    BarcodeOverlayView(Context context, String instruction) {
        super(context);
        this.instruction = instruction;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    BarcodeOverlayView(Context context, AttributeSet attrs) {
        this(context, "Place a barcode inside the frame");
    }

    void update(String text, boolean hasCandidate) {
        message = text;
        candidateVisible = hasCandidate;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float guideWidth = width * 0.86f;
        float guideHeight = Math.min(guideWidth * 0.62f, height * 0.44f);
        float centerY = height * 0.45f;
        guide.set(width / 2f - guideWidth / 2f, centerY - guideHeight / 2f,
            width / 2f + guideWidth / 2f, centerY + guideHeight / 2f);

        Path shade = new Path();
        shade.setFillType(Path.FillType.EVEN_ODD);
        shade.addRect(0, 0, width, height, Path.Direction.CW);
        shade.addRoundRect(guide, dp(18), dp(18), Path.Direction.CCW);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99000000);
        canvas.drawPath(shade, paint);

        int color = candidateVisible ? 0xff4ade80 : 0xffffffff;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4));
        paint.setColor(color);
        canvas.drawRoundRect(guide, dp(18), dp(18), paint);

        if (!instruction.isEmpty()) {
            drawCenteredText(canvas, instruction, Math.max(dp(72), guide.top - dp(42)),
                dp(17), Color.WHITE);
        }
        drawCenteredText(canvas, message, Math.min(height - dp(72), guide.bottom + dp(54)),
            dp(17), color);
    }

    private void drawCenteredText(Canvas canvas, String text, float baseline, float size, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size);
        paint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
        paint.setColor(color);
        String[] lines = text.split("\\n");
        for (int index = 0; index < lines.length; index++) {
            canvas.drawText(lines[index], getWidth() / 2f,
                baseline + index * size * 1.25f, paint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
