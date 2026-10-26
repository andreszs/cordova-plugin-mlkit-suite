package com.andreszs.plugins.mlkitsuite;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class BarcodeOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF guide = new RectF();
    private final RectF animatedGuide = new RectF();
    private final RectF animationStart = new RectF();
    private final RectF animationTarget = new RectF();
    private final String instruction;
    private final Integer guideColor;
    private String message;
    private boolean candidateVisible;
    private boolean successVisible;
    private ValueAnimator successAnimator;

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

    void showSuccess(Rect bounds, int imageWidth, int imageHeight, String text) {
        updateGuide(getWidth(), getHeight());
        float[] mapped = bounds == null ? null : BarcodeScanRegion.map(
            getWidth(), getHeight(), imageWidth, imageHeight,
            bounds.left, bounds.top, bounds.right, bounds.bottom
        );
        animationStart.set(guide);
        if (mapped == null) {
            animationTarget.set(guide);
        } else {
            float padding = dp(10);
            animationTarget.set(
                Math.max(0, mapped[0] - padding),
                Math.max(0, mapped[1] - padding),
                Math.min(getWidth(), mapped[2] + padding),
                Math.min(getHeight(), mapped[3] + padding)
            );
        }
        animatedGuide.set(animationStart);
        message = text;
        candidateVisible = true;
        successVisible = true;
        if (successAnimator != null) successAnimator.cancel();
        successAnimator = ValueAnimator.ofFloat(0f, 1f);
        successAnimator.setDuration(220L);
        successAnimator.setInterpolator(new DecelerateInterpolator());
        successAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            animatedGuide.set(
                lerp(animationStart.left, animationTarget.left, progress),
                lerp(animationStart.top, animationTarget.top, progress),
                lerp(animationStart.right, animationTarget.right, progress),
                lerp(animationStart.bottom, animationTarget.bottom, progress)
            );
            invalidate();
        });
        successAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        updateGuide(width, height);
        RectF frame = successVisible ? animatedGuide : guide;

        Path shade = new Path();
        shade.setFillType(Path.FillType.EVEN_ODD);
        shade.addRect(0, 0, width, height, Path.Direction.CW);
        shade.addRoundRect(frame, dp(18), dp(18), Path.Direction.CCW);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99000000);
        canvas.drawPath(shade, paint);

        int color = candidateVisible ? 0xff4ade80 : Color.WHITE;
        if (candidateVisible) {
            drawCornerFrame(canvas, frame, color, color, color, color);
        } else if (guideColor != null) {
            drawCornerFrame(canvas, frame, guideColor, guideColor, guideColor, guideColor);
        } else {
            drawCornerFrame(canvas, frame, 0xff4285f4, 0xfffbbc04, 0xff34a853,
                0xffea4335);
        }

        if (!successVisible && !instruction.isEmpty()) {
            drawCenteredText(canvas, instruction, Math.max(dp(72), guide.top - dp(42)),
                dp(17), Color.WHITE);
        }
        drawCenteredText(canvas, message, Math.min(height - dp(72), frame.bottom + dp(54)),
            dp(17), color);
    }

    private void updateGuide(float width, float height) {
        float guideSize = Math.min(width * 0.80f, height * 0.50f);
        float centerY = height * 0.45f;
        guide.set(width / 2f - guideSize / 2f, centerY - guideSize / 2f,
            width / 2f + guideSize / 2f, centerY + guideSize / 2f);
    }

    private void drawCornerFrame(Canvas canvas, RectF frame, int topLeft, int topRight,
                                 int bottomLeft, int bottomRight) {
        float radius = Math.min(dp(22), Math.min(frame.width(), frame.height()) * 0.25f);
        float leg = Math.min(Math.min(frame.width(), frame.height()) * 0.32f, dp(76));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        Path path = new Path();
        path.moveTo(frame.left, frame.top + leg);
        path.lineTo(frame.left, frame.top + radius);
        path.quadTo(frame.left, frame.top, frame.left + radius, frame.top);
        path.lineTo(frame.left + leg, frame.top);
        paint.setColor(topLeft);
        canvas.drawPath(path, paint);

        path.reset();
        path.moveTo(frame.right - leg, frame.top);
        path.lineTo(frame.right - radius, frame.top);
        path.quadTo(frame.right, frame.top, frame.right, frame.top + radius);
        path.lineTo(frame.right, frame.top + leg);
        paint.setColor(topRight);
        canvas.drawPath(path, paint);

        path.reset();
        path.moveTo(frame.left, frame.bottom - leg);
        path.lineTo(frame.left, frame.bottom - radius);
        path.quadTo(frame.left, frame.bottom, frame.left + radius, frame.bottom);
        path.lineTo(frame.left + leg, frame.bottom);
        paint.setColor(bottomLeft);
        canvas.drawPath(path, paint);

        path.reset();
        path.moveTo(frame.right - leg, frame.bottom);
        path.lineTo(frame.right - radius, frame.bottom);
        path.quadTo(frame.right, frame.bottom, frame.right, frame.bottom - radius);
        path.lineTo(frame.right, frame.bottom - leg);
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

    private float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (successAnimator != null) successAnimator.cancel();
        super.onDetachedFromWindow();
    }
}
