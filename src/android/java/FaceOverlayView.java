package com.andreszs.plugins.mlkitsuite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.View;

final class FaceOverlayView extends View {
    private static final long PROGRESS_TRANSITION_MS = 260L;
    private static final int PROGRESS_GREEN = 0xff4ade80;
    private static final int PROGRESS_INTERMEDIATE = 0xffa2d54b;
    private static final int PROGRESS_YELLOW = 0xfffacc15;
    private static final int PROGRESS_FUTURE = 0x66ffffff;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF guide = new RectF();
    private final MlKitLocalization localization;
    private FaceCaptureModels.Analysis analysis;
    private String message;
    private final boolean debugMode;
    private final int progressSegments;
    private final int captureAfterGesture;
    private final FaceCaptureConfig.Checks checks;
    private final boolean hasGestures;
    private final boolean deviceVerticalCheckEnabled;
    private boolean deviceVerticalReady;
    private float deviceTiltDegrees = Float.NaN;
    private int progressCompleted;
    private int[] progressStartColors;
    private int[] progressTargetColors;
    private long[] progressTransitionStartedAt;

    FaceOverlayView(Context context, MlKitLocalization localization,
                    boolean debugMode, String initialMessage,
                    int progressSegments, int captureAfterGesture,
                    FaceCaptureConfig.Checks checks, boolean hasGestures,
                    boolean deviceVerticalCheckEnabled) {
        super(context);
        this.localization = localization;
        this.debugMode = debugMode;
        this.message = initialMessage;
        this.progressSegments = Math.max(0, progressSegments);
        this.captureAfterGesture = Math.max(0, captureAfterGesture);
        this.checks = checks;
        this.hasGestures = hasGestures;
        this.deviceVerticalCheckEnabled = deviceVerticalCheckEnabled;
        this.deviceVerticalReady = !deviceVerticalCheckEnabled;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    FaceOverlayView(Context context, AttributeSet attrs) {
        this(context, MlKitLocalization.from(context, (Object) null),
            false,
            MlKitLocalization.from(context, (Object) null).text("face_searching"),
            0, 0, FaceCaptureConfig.Checks.fromJson(null), false, false);
    }

    void update(FaceCaptureModels.Analysis value, String text) {
        analysis = value;
        message = text;
        invalidate();
    }

    void setProgressCompleted(int value) {
        progressCompleted = Math.max(0, Math.min(progressSegments, value));
        invalidate();
    }

    void updateDeviceTilt(float degrees, boolean ready) {
        deviceTiltDegrees = degrees;
        deviceVerticalReady = ready;
        invalidate();
    }

    RectF centeringGuide() {
        return calculateGuide(getWidth(), getHeight());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        boolean detailedGestureDebug = debugMode && hasGestures;
        float debugBottom = height - dp(8);
        float debugHeight = !debugMode ? 0 : detailedGestureDebug
            ? Math.min(dp(82), height * 0.15f)
            : Math.min(dp(52), height * 0.09f);
        float debugTop = debugBottom - debugHeight;
        RectF calculatedGuide = calculateGuide(width, height);
        guide.set(calculatedGuide);

        Path shade = new Path();
        shade.setFillType(Path.FillType.EVEN_ODD);
        shade.addRect(0, 0, width, height, Path.Direction.CW);
        shade.addOval(guide, Path.Direction.CCW);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99000000);
        canvas.drawPath(shade, paint);

        int color = stateColor(analysis == null ? FaceCaptureModels.State.SEARCHING : analysis.state);
        boolean phaseFeedback = analysis != null && analysis.gestureProgress != null
            && analysis.gestureProgress.phaseFeedback;
        int ovalColor = phaseFeedback ? 0xff4ade80 : color;
        int messageColor = analysis != null
            && (analysis.state == FaceCaptureModels.State.HOLD_STILL || phaseFeedback)
            ? 0xff4ade80 : color;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4));
        paint.setColor(ovalColor);
        canvas.drawOval(guide, paint);

        float messageTop = dp(64);
        float messageBottom = guide.top - dp(36);
        drawCenteredText(canvas, message, (messageTop + messageBottom) / 2f, dp(18),
            messageColor, dp(24), width - dp(24), messageTop, messageBottom, 3);
        drawProgress(canvas, width, guide.bottom + dp(18));

        if (debugMode && analysis != null) {
            FaceCaptureModels.GestureProgress progress = analysis.gestureProgress;
            if (detailedGestureDebug && progress != null && progress.debug != null) {
                drawGestureDebug(canvas, analysis, progress,
                    new RectF(dp(8), debugTop, width - dp(8), debugBottom));
            } else {
                CharSequence values = debugValues(analysis);
                drawCenteredText(canvas, values, (debugTop + debugBottom) / 2f, dp(10),
                    Color.WHITE, dp(10), width - dp(10), debugTop, debugBottom, 4);
            }
        }
    }

    private RectF calculateGuide(float width, float height) {
        if (width <= 0f || height <= 0f) return new RectF();
        boolean detailedGestureDebug = debugMode && hasGestures;
        float debugBottom = height - dp(8);
        float debugHeight = !debugMode ? 0 : detailedGestureDebug
            ? Math.min(dp(82), height * 0.15f)
            : Math.min(dp(52), height * 0.09f);
        float debugTop = debugBottom - debugHeight;
        float progressBottom = debugMode ? debugTop - dp(8) : height - dp(20);
        float guideTopLimit = Math.min(dp(160), height * 0.28f);
        float guideBottomLimit = progressBottom - dp(30);
        float availableGuideHeight = Math.max(1, guideBottomLimit - guideTopLimit);
        float guideWidth = Math.min(width * 0.78f, availableGuideHeight / 1.28f);
        float guideHeight = guideWidth * 1.28f;
        float guideTop = guideTopLimit + Math.max(0, availableGuideHeight - guideHeight) * 0.65f;
        return new RectF(width / 2f - guideWidth / 2f, guideTop,
            width / 2f + guideWidth / 2f, guideTop + guideHeight);
    }

    private void drawCenteredText(Canvas canvas, CharSequence text, float centerY, float size, int color,
                                  float left, float right, float top, float bottom, int maxLines) {
        if (text == null || text.length() == 0 || right <= left || bottom <= top) return;
        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(size);
        textPaint.setTypeface(android.graphics.Typeface.create(
            "sans", android.graphics.Typeface.BOLD));
        textPaint.setColor(color);
        int layoutWidth = Math.max(1, Math.round(right - left));
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build();
        float availableHeight = bottom - top;
        float y = centerY - layout.getHeight() / 2f;
        y = Math.max(top, Math.min(y, bottom - Math.min(layout.getHeight(), availableHeight)));
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        canvas.translate(left, y);
        layout.draw(canvas);
        canvas.restore();
    }

    private void drawGestureDebug(Canvas canvas, FaceCaptureModels.Analysis value,
                                  FaceCaptureModels.GestureProgress progress, RectF area) {
        FaceCaptureModels.GestureDebug debug = progress.debug;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xdd080a0e);
        canvas.drawRoundRect(area, dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x88facc15);
        canvas.drawRoundRect(area, dp(12), dp(12), paint);

        float scale = Math.max(0.82f, Math.min(1f, area.height() / dp(92)));
        float pad = dp(7) * scale;
        float small = dp(9) * scale;
        float body = dp(10) * scale;
        float left = area.left + pad;
        float right = area.right - pad;
        float usable = right - left;
        float y = area.top + pad + small;
        String title = localization.text("face_debug_title") + " · "
            + gestureDebugTitle(progress.gesture);
        int totalStages = debug.phaseCount + 1;
        int currentStage = progress.stage == FaceCaptureModels.GestureStage.WAITING_NEUTRAL
            ? 1 : progress.stage == FaceCaptureModels.GestureStage.RETURN_NEUTRAL
            ? totalStages : Math.min(totalStages, debug.phase + 2);
        drawFittedLine(canvas, title, left, y, usable - dp(42), small,
            0xfffacc15, Paint.Align.LEFT);
        drawFittedLine(canvas, currentStage + "/" + totalStages, right, y, dp(38),
            small, Color.WHITE, Paint.Align.RIGHT);

        y += dp(17) * scale;
        drawFittedLine(canvas, gestureSteps(progress, debug), (left + right) / 2f, y,
            usable, small, 0xfffacc15, Paint.Align.CENTER);

        if (debug.metric == FaceCaptureModels.GestureMetric.NEUTRAL) {
            y += dp(18) * scale;
            String angles = String.format(java.util.Locale.US,
                "Yaw %+.1f° · Pitch %+.1f° · Roll %+.1f°",
                debug.relativeYaw, debug.relativePitch, debug.relativeRoll);
            drawFittedLine(canvas, angles, (left + right) / 2f, y, usable,
                body, debug.satisfied ? 0xff4ade80 : Color.WHITE, Paint.Align.CENTER);
            y += dp(15) * scale;
            drawFittedLine(canvas, localization.text("face_debug_neutral_target"),
                (left + right) / 2f, y, usable, small, 0xfffacc15, Paint.Align.CENTER);
            y += dp(11) * scale;
            drawHoldMeter(canvas, left, right, y, debug.heldMs, debug.requiredHoldMs,
                debug.satisfied);
            return;
        }

        y += dp(18) * scale;
        String metricLine = metricLine(debug);
        drawFittedLine(canvas, metricLine, (left + right) / 2f, y, usable,
            body, debug.satisfied ? 0xff4ade80 : Color.WHITE, Paint.Align.CENTER);
        y += dp(12) * scale;
        drawMetricMeter(canvas, debug, left, right, y);
    }

    private String gestureDebugTitle(FaceCaptureModels.Gesture gesture) {
        if (gesture == FaceCaptureModels.Gesture.NOD) return "NOD UP + DOWN";
        if (gesture == FaceCaptureModels.Gesture.SHAKE) return "NOD RIGHT + LEFT";
        return gesture == null ? "GESTURE" : gesture.name().replace('_', ' ');
    }

    private String gestureSteps(FaceCaptureModels.GestureProgress progress,
                                FaceCaptureModels.GestureDebug debug) {
        String front = localization.text("face_debug_front");
        String[] actions;
        if (progress.gesture == FaceCaptureModels.Gesture.NOD) {
            actions = new String[] {localization.text("face_debug_up"),
                localization.text("face_debug_down")};
        } else if (progress.gesture == FaceCaptureModels.Gesture.SHAKE) {
            actions = new String[] {localization.text("face_debug_right"),
                localization.text("face_debug_left")};
        } else {
            actions = new String[] {gestureActionLabel(progress.gesture)};
        }
        StringBuilder result = new StringBuilder();
        if (progress.stage == FaceCaptureModels.GestureStage.RETURN_NEUTRAL) {
            for (String action : actions) appendStep(result, action + " ✓");
            appendStep(result, front + " ›");
            return result.toString();
        }
        appendStep(result, front + (progress.stage == FaceCaptureModels.GestureStage.WAITING_NEUTRAL
            ? " ›" : " ✓"));
        for (int i = 0; i < actions.length; i++) {
            boolean done = progress.stage == FaceCaptureModels.GestureStage.PERFORM
                && (i < debug.phase || (progress.phaseFeedback && i == debug.phase));
            boolean active = progress.stage == FaceCaptureModels.GestureStage.PERFORM
                && !progress.phaseFeedback && i == debug.phase;
            appendStep(result, actions[i] + (done ? " ✓" : active ? " ›" : ""));
        }
        return result.toString();
    }

    private void appendStep(StringBuilder result, String value) {
        if (result.length() > 0) result.append("   ·   ");
        result.append(value);
    }

    private String gestureActionLabel(FaceCaptureModels.Gesture gesture) {
        if (gesture == null) return localization.text("face_debug_action");
        switch (gesture) {
            case LOOK_UP: return localization.text("face_debug_up");
            case LOOK_DOWN: return localization.text("face_debug_down");
            case OPEN_MOUTH: return localization.text("face_debug_metric_mouth");
            case SMILE: return localization.text("face_debug_metric_smile");
            case BLINK: return localization.text("face_debug_metric_eyes");
            default: return localization.text("face_debug_action");
        }
    }

    private String metricLine(FaceCaptureModels.GestureDebug debug) {
        String metric = metricLabel(debug.metric);
        String comparator = debug.lessThan ? "<" : ">";
        if (debug.metric == FaceCaptureModels.GestureMetric.EYES) {
            return metric + " L " + formatMetricValue(debug.rawPrimary, debug.metric)
                + " · R " + formatMetricValue(debug.rawSecondary, debug.metric)
                + " · " + localization.text("face_debug_target") + " "
                + comparator + " " + formatMetricValue(debug.target, debug.metric);
        }
        return metric + " " + localization.text("face_debug_relative") + " "
            + formatMetricValue(debug.relative, debug.metric) + " · "
            + localization.text("face_debug_target") + " " + comparator + " "
            + formatMetricValue(debug.target, debug.metric);
    }

    private String metricLabel(FaceCaptureModels.GestureMetric metric) {
        switch (metric) {
            case PITCH: return localization.text("face_debug_metric_pitch");
            case YAW: return localization.text("face_debug_metric_yaw");
            case MOUTH: return localization.text("face_debug_metric_mouth");
            case SMILE: return localization.text("face_debug_metric_smile");
            case EYES: return localization.text("face_debug_metric_eyes");
            default: return localization.text("face_debug_neutral");
        }
    }

    private String formatMetricValue(float value, FaceCaptureModels.GestureMetric metric) {
        if (Float.isNaN(value) || value < 0f && metric == FaceCaptureModels.GestureMetric.EYES) {
            return "—";
        }
        if (metric == FaceCaptureModels.GestureMetric.PITCH
            || metric == FaceCaptureModels.GestureMetric.YAW) {
            return String.format(java.util.Locale.US, "%+.1f°", value);
        }
        return String.format(java.util.Locale.US,
            metric == FaceCaptureModels.GestureMetric.MOUTH ? "%.3f" : "%.2f", value);
    }

    private void drawMetricMeter(Canvas canvas, FaceCaptureModels.GestureDebug debug,
                                 float left, float right, float centerY) {
        float min = 0f;
        float max = 1f;
        if (debug.metric == FaceCaptureModels.GestureMetric.PITCH
            || debug.metric == FaceCaptureModels.GestureMetric.YAW) {
            min = -20f;
            max = 20f;
        } else if (debug.metric == FaceCaptureModels.GestureMetric.MOUTH) {
            max = 0.10f;
        }
        float trackHeight = dp(7);
        RectF track = new RectF(left, centerY - trackHeight / 2f,
            right, centerY + trackHeight / 2f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x44ffffff);
        canvas.drawRoundRect(track, trackHeight / 2f, trackHeight / 2f, paint);
        float thresholdX = meterX(debug.target, min, max, left, right);
        paint.setColor(0x55facc15);
        RectF zone = debug.lessThan
            ? new RectF(left, track.top, thresholdX, track.bottom)
            : new RectF(thresholdX, track.top, right, track.bottom);
        canvas.drawRoundRect(zone, trackHeight / 2f, trackHeight / 2f, paint);
        paint.setColor(0xfffacc15);
        canvas.drawRect(thresholdX - dp(1), track.top - dp(2),
            thresholdX + dp(1), track.bottom + dp(2), paint);
        float markerX = meterX(debug.relative, min, max, left, right);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(markerX, centerY, dp(5), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(debug.satisfied ? 0xff4ade80 : 0xfffacc15);
        canvas.drawCircle(markerX, centerY, dp(5), paint);
    }

    private float meterX(float value, float min, float max, float left, float right) {
        float normalized = Math.max(0f, Math.min(1f, (value - min) / (max - min)));
        return left + normalized * (right - left);
    }

    private void drawHoldMeter(Canvas canvas, float left, float right, float centerY,
                               long heldMs, long requiredMs, boolean accepted) {
        float height = dp(6);
        RectF track = new RectF(left, centerY - height / 2f,
            right, centerY + height / 2f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x44ffffff);
        canvas.drawRoundRect(track, height / 2f, height / 2f, paint);
        float ratio = requiredMs <= 0 ? 0 : Math.max(0f, Math.min(1f, heldMs / (float) requiredMs));
        RectF fill = new RectF(left, track.top, left + ratio * track.width(), track.bottom);
        paint.setColor(accepted ? 0xff4ade80 : 0xfffacc15);
        canvas.drawRoundRect(fill, height / 2f, height / 2f, paint);
    }

    private void drawFittedLine(Canvas canvas, String text, float x, float baseline,
                                float maxWidth, float requestedSize, int color,
                                Paint.Align align) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(android.graphics.Typeface.create("sans",
            android.graphics.Typeface.BOLD));
        paint.setTextAlign(align);
        paint.setTextSize(requestedSize);
        float measured = paint.measureText(text);
        if (measured > maxWidth && measured > 0) {
            paint.setTextSize(Math.max(dp(7.5f), requestedSize * maxWidth / measured));
        }
        paint.setColor(color);
        canvas.drawText(text, x, baseline, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private CharSequence debugValues(FaceCaptureModels.Analysis value) {
        SpannableStringBuilder result = new SpannableStringBuilder();
        boolean verticalAccepted = !deviceVerticalCheckEnabled || deviceVerticalReady;
        boolean faceAvailable = value.normalizedBounds != null;
        if (deviceVerticalCheckEnabled) {
            String tilt = Float.isNaN(deviceTiltDegrees) ? "tilt —"
                : String.format(java.util.Locale.US, "tilt %.1f°", deviceTiltDegrees);
            appendDebug(result, tilt, deviceVerticalReady);
        }
        if (checks.faceSize) {
            appendDebug(result, faceAvailable
                    ? String.format(java.util.Locale.US, "size %.2f",
                        value.normalizedBounds.width()) : "size —",
                verticalAccepted && faceAvailable && value.normalizedBounds.width() >= 0.28f
                    && value.normalizedBounds.width() <= 0.62f);
        }
        if (checks.centering) {
            String center = value.centeringAvailable
                ? String.format(java.util.Locale.US, "center x %+.2f y %+.2f",
                    value.centeringHorizontalOffset, value.centeringVerticalOffset)
                : "center —";
            appendDebug(result, center, verticalAccepted && faceAvailable
                && value.centeringAvailable && value.centeringAccepted);
        }
        if (checks.pose) {
            appendDebug(result, faceAvailable
                    ? String.format(java.util.Locale.US, "yaw %.1f", value.yaw) : "yaw —",
                verticalAccepted && faceAvailable && Math.abs(value.yaw) <= 11f);
            appendDebug(result, faceAvailable
                    ? String.format(java.util.Locale.US, "pitch %.1f", value.pitch) : "pitch —",
                verticalAccepted && faceAvailable && Math.abs(value.pitch) <= 11f);
            appendDebug(result, faceAvailable
                    ? String.format(java.util.Locale.US, "roll %.1f", value.roll) : "roll —",
                verticalAccepted && faceAvailable && Math.abs(value.roll) <= 9f);
        }
        if (hasGestures) {
            boolean mouthAccepted = value.mouthOpenRatio < 0.035f;
            if (value.gestureProgress != null
                && value.gestureProgress.stage == FaceCaptureModels.GestureStage.PERFORM
                && value.gestureProgress.gesture == FaceCaptureModels.Gesture.OPEN_MOUTH) {
                mouthAccepted = value.mouthOpenRatio > 0.055f;
            }
            appendDebug(result, faceAvailable ? String.format(java.util.Locale.US,
                "mouth %.3f", value.mouthOpenRatio) : "mouth —",
                verticalAccepted && faceAvailable && mouthAccepted);
        }
        if (checks.brightness) {
            appendDebug(result, String.format(java.util.Locale.US, "light %.2f", value.brightness),
                verticalAccepted && value.brightness >= 0.20f && value.brightness <= 0.88f);
        }
        if (checks.sharpness) {
            appendDebug(result, String.format(java.util.Locale.US, "sharp %.2f", value.sharpness),
                verticalAccepted && value.sharpness >= 0.10f);
        }
        if (checks.stability && !hasGestures) {
            appendDebug(result, String.format(java.util.Locale.US,
                "stable %d ms", value.stableDurationMs),
                verticalAccepted && value.stableDurationMs >= 650L);
        }
        return result;
    }

    private void appendDebug(SpannableStringBuilder result, String text, boolean accepted) {
        if (result.length() > 0) result.append("  ");
        int start = result.length();
        result.append(text);
        result.setSpan(new ForegroundColorSpan(accepted ? 0xff4ade80 : Color.WHITE),
            start, result.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void drawProgress(Canvas canvas, float width, float centerY) {
        if (progressSegments <= 0) return;
        float totalWidth = Math.min(width - dp(48), dp(280));
        float gap = dp(6);
        float segmentWidth = (totalWidth - gap * (progressSegments - 1)) / progressSegments;
        float height = dp(8);
        float x = (width - totalWidth) / 2f;
        boolean allProgressComplete = progressCompleted == progressSegments;
        FaceCaptureModels.GestureProgress currentProgress = analysis == null
            ? null : analysis.gestureProgress;
        int[] targets = new int[progressSegments];
        for (int i = 0; i < progressSegments; i++) {
            targets[i] = i < progressCompleted || allProgressComplete
                ? PROGRESS_GREEN : i == progressCompleted ? PROGRESS_YELLOW : PROGRESS_FUTURE;
        }
        if (currentProgress != null && currentProgress.debug != null
            && currentProgress.stage == FaceCaptureModels.GestureStage.PERFORM) {
            int segment = gestureSegmentIndex(currentProgress.index);
            if (segment >= 0 && segment < targets.length) {
                int acceptedPhases = currentProgress.debug.phase
                    + (currentProgress.phaseFeedback ? 1 : 0);
                targets[segment] = acceptedPhases <= 0 ? PROGRESS_YELLOW
                    : acceptedPhases < currentProgress.debug.phaseCount
                    ? PROGRESS_INTERMEDIATE : PROGRESS_GREEN;
            }
        }
        long now = SystemClock.uptimeMillis();
        ensureProgressAnimation(targets, now);
        boolean animating = false;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < progressSegments; i++) {
            int currentColor = animatedProgressColor(i, now);
            paint.setColor(currentColor);
            RectF segment = new RectF(x, centerY - height / 2f,
                x + segmentWidth, centerY + height / 2f);
            canvas.drawRoundRect(segment, height / 2f, height / 2f, paint);
            animating |= currentColor != progressTargetColors[i];
            x += segmentWidth + gap;
        }
        if (animating) postInvalidateOnAnimation();
    }

    private int gestureSegmentIndex(int gestureIndex) {
        return 1 + gestureIndex + (captureAfterGesture <= gestureIndex ? 1 : 0);
    }

    private void ensureProgressAnimation(int[] targets, long now) {
        if (progressTargetColors == null || progressTargetColors.length != targets.length) {
            progressStartColors = targets.clone();
            progressTargetColors = targets.clone();
            progressTransitionStartedAt = new long[targets.length];
            java.util.Arrays.fill(progressTransitionStartedAt, now);
            return;
        }
        for (int i = 0; i < targets.length; i++) {
            if (progressTargetColors[i] == targets[i]) continue;
            progressStartColors[i] = animatedProgressColor(i, now);
            progressTargetColors[i] = targets[i];
            progressTransitionStartedAt[i] = now;
        }
    }

    private int animatedProgressColor(int index, long now) {
        float t = Math.min(1f, Math.max(0f,
            (now - progressTransitionStartedAt[index]) / (float) PROGRESS_TRANSITION_MS));
        t = t * t * (3f - 2f * t);
        return blendColor(progressStartColors[index], progressTargetColors[index], t);
    }

    private int blendColor(int from, int to, float amount) {
        int alpha = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * amount);
        int red = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount);
        int green = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount);
        int blue = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount);
        return Color.argb(alpha, red, green, blue);
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

