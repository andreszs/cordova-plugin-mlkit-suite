package com.andreszs.plugins.mlkitsuite;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

final class FaceFrameAnalyzer implements ImageAnalysis.Analyzer {
    private static final long FACE_LOSS_GRACE_MS = 300L;

    interface Listener {
        void onAnalysis(FaceCaptureModels.Analysis analysis);
        void onAnalyzerError(Exception error);
    }

    private final FaceCaptureConfig config;
    private final Listener listener;
    private final Executor callbackExecutor;
    private final FaceDetector detector;
    private final FaceCaptureModels.GestureTracker gestureTracker;
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final FaceCaptureModels.StabilityTracker stability = new FaceCaptureModels.StabilityTracker();
    private volatile boolean enabled = true;
    private volatile boolean deviceVerticalReady;
    private volatile RectF previewGuide;
    private volatile int previewWidth;
    private volatile int previewHeight;
    private long faceMissingSince;

    FaceFrameAnalyzer(FaceCaptureConfig config, Listener listener, Executor callbackExecutor) {
        this.config = config;
        this.listener = listener;
        this.callbackExecutor = callbackExecutor;
        deviceVerticalReady = !config.requireDeviceVertical;
        gestureTracker = new FaceCaptureModels.GestureTracker(config.gestures,
            config.captureAfterGesture, config.gestureTimeoutMs, config.neutralTimeoutMs,
            config.retryMessageMs);
        FaceDetectorOptions.Builder builder = new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking();
        if (config.gestures.contains(FaceCaptureModels.Gesture.OPEN_MOUTH)) {
            builder.setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL);
        }
        detector = FaceDetection.getClient(builder.build());
    }

    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        if (!enabled || imageProxy.getImage() == null || !processing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }
        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        int width = (rotation == 90 || rotation == 270) ? imageProxy.getHeight() : imageProxy.getWidth();
        int height = (rotation == 90 || rotation == 270) ? imageProxy.getWidth() : imageProxy.getHeight();
        float[] quality = imageQuality(imageProxy);
        InputImage input = InputImage.fromMediaImage(imageProxy.getImage(), rotation);
        detector.process(input)
            .addOnSuccessListener(faces -> {
                FaceCaptureModels.Analysis analysis = classify(faces, width, height,
                    quality[0], quality[1]);
                callbackExecutor.execute(() -> listener.onAnalysis(analysis));
            })
            .addOnFailureListener(error -> callbackExecutor.execute(() -> listener.onAnalyzerError(error)))
            .addOnCompleteListener(task -> {
                processing.set(false);
                imageProxy.close();
            });
    }

    void close() {
        enabled = false;
        detector.close();
    }

    void setDeviceVerticalReady(boolean ready) {
        deviceVerticalReady = ready;
    }

    void setPreviewGeometry(int width, int height, RectF guide) {
        previewWidth = width;
        previewHeight = height;
        previewGuide = guide == null ? null : new RectF(guide);
    }

    private FaceCaptureModels.Analysis classify(
        List<Face> faces, int width, int height, float brightness, float sharpness
    ) {
        FaceCaptureModels.State state;
        long now = SystemClock.elapsedRealtime();
        if (config.requireDeviceVertical && !deviceVerticalReady) {
            stability.clear();
            faceMissingSince = 0L;
            FaceCaptureModels.GestureProgress progress = gestureTracker.progress();
            if (!config.gestures.isEmpty() && (gestureTracker.hasStarted()
                || progress.stage == FaceCaptureModels.GestureStage.RETRY)) {
                if (progress.stage != FaceCaptureModels.GestureStage.RETRY) {
                    gestureTracker.retry(now);
                }
                return empty(FaceCaptureModels.State.GESTURE, brightness, sharpness,
                    gestureTracker.progress());
            }
            gestureTracker.reset();
            return empty(FaceCaptureModels.State.DEVICE_TILTED, brightness, sharpness);
        }
        if (faces.isEmpty()) {
            stability.clear();
            if (!config.gestures.isEmpty() && gestureTracker.hasStarted()) {
                if (faceMissingSince == 0L) faceMissingSince = now;
                if (now - faceMissingSince <= FACE_LOSS_GRACE_MS) {
                    FaceCaptureModels.GestureProgress progress = gestureTracker.progress();
                    // Keep the internal capture checkpoint frozen, but never expose CAPTURE
                    // until a face is present again.
                    if (progress.stage == FaceCaptureModels.GestureStage.CAPTURE) {
                        progress = new FaceCaptureModels.GestureProgress(progress.gesture,
                            FaceCaptureModels.GestureStage.RETURN_NEUTRAL, progress.index,
                            progress.count, progress.debug, progress.phaseFeedback);
                    }
                    return empty(FaceCaptureModels.State.GESTURE, brightness, sharpness,
                        progress);
                }
                gestureTracker.retry(now);
                faceMissingSince = 0L;
                return empty(FaceCaptureModels.State.GESTURE, brightness, sharpness,
                    gestureTracker.progress());
            }
            faceMissingSince = 0L;
            gestureTracker.reset();
            return empty(FaceCaptureModels.State.SEARCHING, brightness, sharpness);
        }
        if (config.checks.singleFace && faces.size() != 1) {
            stability.clear();
            faceMissingSince = 0L;
            if (!config.gestures.isEmpty() && gestureTracker.hasStarted()) {
                gestureTracker.retry(now);
                return empty(FaceCaptureModels.State.GESTURE, brightness, sharpness,
                    gestureTracker.progress());
            }
            gestureTracker.reset();
            return empty(FaceCaptureModels.State.MULTIPLE_FACES, brightness, sharpness);
        }
        if (faceMissingSince != 0L) {
            gestureTracker.resumeAfterFaceLoss(Math.min(FACE_LOSS_GRACE_MS,
                now - faceMissingSince));
            faceMissingSince = 0L;
        }

        Face face = largestFace(faces);
        Rect box = face.getBoundingBox();
        RectF normalized = new RectF(
            clamp(box.left / (float) width), clamp(box.top / (float) height),
            clamp(box.right / (float) width), clamp(box.bottom / (float) height));
        float widthFraction = normalized.width();
        FaceCentering.Result centering = FaceCentering.evaluate(normalized,
            previewWidth, previewHeight, previewGuide);
        float leftEye = probability(face.getLeftEyeOpenProbability());
        float rightEye = probability(face.getRightEyeOpenProbability());
        float smile = probability(face.getSmilingProbability());
        float yaw = face.getHeadEulerAngleY();
        float pitch = face.getHeadEulerAngleX();
        float roll = face.getHeadEulerAngleZ();
        float mouthOpenRatio = mouthOpenRatio(face);
        FaceCaptureModels.GestureProgress gestureProgress = gestureTracker.tick(now);
        boolean activeGesture = gestureProgress.stage == FaceCaptureModels.GestureStage.PERFORM
            || gestureProgress.stage == FaceCaptureModels.GestureStage.RETURN_NEUTRAL
            || gestureProgress.stage == FaceCaptureModels.GestureStage.CAPTURE;

        if (gestureProgress.stage == FaceCaptureModels.GestureStage.RETRY) {
            state = FaceCaptureModels.State.GESTURE;
        } else if (activeGesture) {
            gestureProgress = gestureTracker.update(yaw, pitch, roll, leftEye, rightEye, smile,
                mouthOpenRatio, now);
            state = FaceCaptureModels.State.GESTURE;
        } else if (config.checks.faceSize && widthFraction < 0.28f) state = FaceCaptureModels.State.MOVE_CLOSER;
        else if (config.checks.faceSize && widthFraction > 0.62f) state = FaceCaptureModels.State.MOVE_AWAY;
        else if (config.checks.centering
            && (!centering.available || !centering.accepted)) {
            state = FaceCaptureModels.State.CENTER_FACE;
        }
        else if (config.checks.brightness && brightness < 0.20f) state = FaceCaptureModels.State.IMPROVE_LIGHTING;
        else if (config.checks.brightness && brightness > 0.88f) state = FaceCaptureModels.State.REDUCE_LIGHTING;
        else if (config.checks.sharpness && sharpness < 0.10f) state = FaceCaptureModels.State.HOLD_STILL;
        else if (!gestureTracker.isComplete()) {
            gestureProgress = gestureTracker.update(yaw, pitch, roll, leftEye, rightEye, smile,
                mouthOpenRatio, now);
            state = FaceCaptureModels.State.GESTURE;
        } else if (config.checks.pose && (Math.abs(yaw) > 11f
            || Math.abs(pitch) > 11f || Math.abs(roll) > 9f)) {
            state = FaceCaptureModels.State.LOOK_STRAIGHT;
        } else if (config.checks.eyesOpen && (leftEye < 0.62f || rightEye < 0.62f)) {
            state = FaceCaptureModels.State.OPEN_EYES;
        } else if (config.checks.neutralExpression && smile > 0.38f) {
            state = FaceCaptureModels.State.NEUTRAL_EXPRESSION;
        } else state = FaceCaptureModels.State.READY;

        long stableMs = 0;
        if (state == FaceCaptureModels.State.READY && config.checks.stability) {
            stableMs = stability.update(normalized, SystemClock.elapsedRealtime());
            if (stableMs < 650L) state = FaceCaptureModels.State.HOLD_STILL;
        } else if (state != FaceCaptureModels.State.READY) {
            stability.clear();
        }
        return new FaceCaptureModels.Analysis(state, normalized, yaw, pitch, roll,
            leftEye, rightEye, smile, brightness, sharpness, stableMs, mouthOpenRatio,
            gestureProgress, centering.horizontalOffset, centering.verticalOffset,
            centering.available, centering.accepted);
    }

    private static Face largestFace(List<Face> faces) {
        Face best = faces.get(0);
        int area = best.getBoundingBox().width() * best.getBoundingBox().height();
        for (int i = 1; i < faces.size(); i++) {
            Face candidate = faces.get(i);
            int candidateArea = candidate.getBoundingBox().width() * candidate.getBoundingBox().height();
            if (candidateArea > area) {
                best = candidate;
                area = candidateArea;
            }
        }
        return best;
    }

    private static float[] imageQuality(ImageProxy proxy) {
        ImageProxy.PlaneProxy plane = proxy.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int width = proxy.getWidth();
        int height = proxy.getHeight();
        long sum = 0;
        long gradients = 0;
        int count = 0;
        int step = Math.max(2, Math.min(width, height) / 120);
        for (int y = step; y < height; y += step) {
            for (int x = step; x < width; x += step) {
                int index = y * rowStride + x * pixelStride;
                int left = y * rowStride + (x - step) * pixelStride;
                if (index >= buffer.limit() || left >= buffer.limit()) continue;
                int value = buffer.get(index) & 0xff;
                int previous = buffer.get(left) & 0xff;
                sum += value;
                gradients += Math.abs(value - previous);
                count++;
            }
        }
        if (count == 0) return new float[] {0f, 0f};
        return new float[] {sum / (count * 255f), Math.min(1f, gradients / (count * 32f))};
    }

    private static FaceCaptureModels.Analysis empty(
        FaceCaptureModels.State state, float brightness, float sharpness
    ) {
        return empty(state, brightness, sharpness, null);
    }

    private static FaceCaptureModels.Analysis empty(
        FaceCaptureModels.State state, float brightness, float sharpness,
        FaceCaptureModels.GestureProgress gestureProgress
    ) {
        return new FaceCaptureModels.Analysis(state, null, 0, 0, 0, -1, -1, -1,
            brightness, sharpness, 0, 0, gestureProgress, Float.NaN, Float.NaN,
            false, false);
    }

    void resumeAfterCandidateCapture() {
        gestureTracker.resumeAfterCapture(SystemClock.elapsedRealtime());
    }

    void retryGestures() {
        gestureTracker.retry(SystemClock.elapsedRealtime());
    }

    private static float mouthOpenRatio(Face face) {
        FaceContour upper = face.getContour(FaceContour.UPPER_LIP_BOTTOM);
        FaceContour lower = face.getContour(FaceContour.LOWER_LIP_TOP);
        if (upper == null || lower == null || upper.getPoints().isEmpty()
            || lower.getPoints().isEmpty() || face.getBoundingBox().height() <= 0) return 0f;
        float upperY = 0f;
        for (android.graphics.PointF point : upper.getPoints()) upperY += point.y;
        float lowerY = 0f;
        for (android.graphics.PointF point : lower.getPoints()) lowerY += point.y;
        upperY /= upper.getPoints().size();
        lowerY /= lower.getPoints().size();
        return Math.abs(lowerY - upperY) / face.getBoundingBox().height();
    }

    private static float probability(Float value) {
        return value == null ? -1f : value;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
