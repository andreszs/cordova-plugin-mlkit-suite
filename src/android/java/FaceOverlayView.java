package com.andreszs.plugins.mlkitsuite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

final class FaceOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF guide = new RectF();
    private FaceCaptureModels.Analysis analysis;
    private String message = "Looking for a face";
    private final String instruction;
    private final boolean debugMode;

    FaceOverlayView(Context context, String instruction, boolean debugMode) {
        super(context);
        this.instruction = instruction;
        this.debugMode = debugMode;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    FaceOverlayView(Context context, AttributeSet attrs) {
        this(context, "Place your face inside the oval", false);
    }

    void update(FaceCaptureModels.Analysis value, String text) {
        analysis = value;
        message = text;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float guideWidth = Math.min(width * 0.78f, height * 0.54f);
        float guideHeight = guideWidth * 1.28f;
        if (guideHeight > height * 0.65f) {
            guideHeight = height * 0.65f;
            guideWidth = guideHeight / 1.28f;
        }
        guide.set(width / 2f - guideWidth / 2f, height * 0.43f - guideHeight / 2f,
            width / 2f + guideWidth / 2f, height * 0.43f + guideHeight / 2f);

        Path shade = new Path();
        shade.setFillType(Path.FillType.EVEN_ODD);
        shade.addRect(0, 0, width, height, Path.Direction.CW);
        shade.addOval(guide, Path.Direction.CCW);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99000000);
        canvas.drawPath(shade, paint);

        int color = stateColor(analysis == null ? FaceCaptureModels.State.SEARCHING : analysis.state);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4));
        paint.setColor(color);
        canvas.drawOval(guide, paint);

        if (analysis != null && analysis.normalizedBounds != null && debugMode) {
            RectF box = analysis.normalizedBounds;
            RectF mapped = new RectF(
                (1f - box.right) * width, box.top * height,
                (1f - box.left) * width, box.bottom * height);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xccffffff);
            canvas.drawRect(mapped, paint);
        }

        if (!instruction.isEmpty()) {
            drawCenteredText(canvas, instruction, Math.max(dp(48), guide.top - dp(42)),
                dp(17), Color.WHITE);
        }
        drawCenteredText(canvas, message, Math.min(height - dp(62), guide.bottom + dp(56)),
            dp(18), color);

        if (debugMode && analysis != null) {
            String values = String.format(java.util.Locale.US,
                "yaw %.1f  pitch %.1f  roll %.1f\nlight %.2f  sharp %.2f  stable %d ms",
                analysis.yaw, analysis.pitch, analysis.roll, analysis.brightness,
                analysis.sharpness, analysis.stableDurationMs);
            drawCenteredText(canvas, values, height - dp(85), dp(13), 0xffdddddd);
        }
    }

    private void drawCenteredText(Canvas canvas, String text, float baseline, float size, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size);
        paint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
        paint.setColor(color);
        String[] lines = text.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            canvas.drawText(lines[i], getWidth() / 2f, baseline + i * size * 1.25f, paint);
        }
    }

    private int stateColor(FaceCaptureModels.State state) {
        if (state == FaceCaptureModels.State.READY || state == FaceCaptureModels.State.CAPTURING) {
            return 0xff4ade80;
        }
        if (state == FaceCaptureModels.State.SEARCHING || state == FaceCaptureModels.State.MULTIPLE_FACES) {
            return 0xffef4444;
        }
        return 0xfffacc15;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}

