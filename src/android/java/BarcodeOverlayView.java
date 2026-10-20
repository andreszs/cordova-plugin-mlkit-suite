package com.andreszs.plugins.mlkitsuite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

final class BarcodeOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF guide = new RectF();
    private final String instruction;
    private final Integer guideColor;
    private String message;
    private boolean candidateVisible;

    BarcodeOverlayView(Context context, String instruction, Integer guideColor,
                       String initialMessage) {
        super(context);
        this.instruction = instruction;
        this.guideColor = guideColor;
        this.message = initialMessage;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    BarcodeOverlayView(Context context, AttributeSet attrs) {
        this(context,
            MlKitLocalization.from(context, (Object) null).text("barcode_instruction"),
            null,
            MlKitLocalization.from(context, (Object) null).text("barcode_searching"));
    }

    void update(String text, boolean hasCandidate) {
        message = text;
        candidateVisible = hasCandidate;
        invalidate();
    }

    boolean containsBarcode(Rect bounds, int imageWidth, int imageHeight) {
        if (bounds == null) return false;
        updateGuide(getWidth(), getHeight());
        return BarcodeScanRegion.contains(
            getWidth(), getHeight(), guide.left, guide.top, guide.right, guide.bottom,
            imageWidth, imageHeight, bounds.left, bounds.top, bounds.right, bounds.bottom
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        updateGuide(width, height);

        Path shade = new Path();
        shade.setFillType(Path.FillType.EVEN_ODD);
        shade.addRect(0, 0, width, height, Path.Direction.CW);
        shade.addRoundRect(guide, dp(18), dp(18), Path.Direction.CCW);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99000000);
        canvas.drawPath(shade, paint);

        int color = candidateVisible ? 0xff4ade80 : Color.WHITE;
        if (candidateVisible) {
            drawCornerFrame(canvas, color, color, color, color);
        } else if (guideColor != null) {
            drawCornerFrame(canvas, guideColor, guideColor, guideColor, guideColor);
        } else {
            drawCornerFrame(canvas, 0xff4285f4, 0xfffbbc04, 0xff34a853, 0xffea4335);
        }

        if (!instruction.isEmpty()) {
            drawCenteredText(canvas, instruction, Math.max(dp(72), guide.top - dp(42)),
                dp(17), Color.WHITE);
        }
        drawCenteredText(canvas, message, Math.min(height - dp(72), guide.bottom + dp(54)),
            dp(17), color);
    }

    private void updateGuide(float width, float height) {
        float guideSize = Math.min(width * 0.80f, height * 0.50f);
        float centerY = height * 0.45f;
        guide.set(width / 2f - guideSize / 2f, centerY - guideSize / 2f,
            width / 2f + guideSize / 2f, centerY + guideSize / 2f);
    }

    private void drawCornerFrame(Canvas canvas, int topLeft, int topRight,
                                 int bottomLeft, int bottomRight) {
        float radius = dp(22);
        float leg = Math.min(guide.width() * 0.22f, dp(76));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        Path path = new Path();
        path.moveTo(guide.left, guide.top + leg);
        path.lineTo(guide.left, guide.top + radius);
        path.quadTo(guide.left, guide.top, guide.left + radius, guide.top);
        path.lineTo(guide.left + leg, guide.top);
        paint.setColor(topLeft);
        canvas.drawPath(path, paint);

        path.reset();
        path.moveTo(guide.right - leg, guide.top);
        path.lineTo(guide.right - radius, guide.top);
        path.quadTo(guide.right, guide.top, guide.right, guide.top + radius);
        path.lineTo(guide.right, guide.top + leg);
        paint.setColor(topRight);
        canvas.drawPath(path, paint);

        path.reset();
        path.moveTo(guide.left, guide.bottom - leg);
        path.lineTo(guide.left, guide.bottom - radius);
        path.quadTo(guide.left, guide.bottom, guide.left + radius, guide.bottom);
        path.lineTo(guide.left + leg, guide.bottom);
        paint.setColor(bottomLeft);
        canvas.drawPath(path, paint);

        path.reset();
        path.moveTo(guide.right - leg, guide.bottom);
        path.lineTo(guide.right - radius, guide.bottom);
        path.quadTo(guide.right, guide.bottom, guide.right, guide.bottom - radius);
        path.lineTo(guide.right, guide.bottom - leg);
        paint.setColor(bottomRight);
        canvas.drawPath(path, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
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
