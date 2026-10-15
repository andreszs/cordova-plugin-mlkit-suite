package com.andreszs.plugins.mlkitsuite;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

final class FaceFrameAnalyzer implements ImageAnalysis.Analyzer {
    interface Listener {
        void onAnalysis(FaceCaptureModels.Analysis analysis);
        void onAnalyzerError(Exception error);
    }

    private final FaceCaptureConfig config;
    private final Listener listener;
    private final Executor callbackExecutor;
    private final FaceDetector detector;
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final FaceCaptureModels.StabilityTracker stability = new FaceCaptureModels.StabilityTracker();
    private volatile boolean enabled = true;

    FaceFrameAnalyzer(FaceCaptureConfig config, Listener listener, Executor callbackExecutor) {
        this.config = config;
        this.listener = listener;
        this.callbackExecutor = callbackExecutor;
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build();
        detector = FaceDetection.getClient(options);
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
                FaceCaptureModels.Analysis analysis = classify(faces, width, height, quality[0], quality[1]);
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

    private FaceCaptureModels.Analysis classify(
        List<Face> faces, int width, int height, float brightness, float sharpness
    ) {
        FaceCaptureModels.State state;
        if (faces.isEmpty()) {
            stability.clear();
            return empty(FaceCaptureModels.State.SEARCHING, brightness, sharpness);
        }
        if (config.checks.singleFace && faces.size() != 1) {
            stability.clear();
            return empty(FaceCaptureModels.State.MULTIPLE_FACES, brightness, sharpness);
        }

        Face face = largestFace(faces);
        Rect box = face.getBoundingBox();
        RectF normalized = new RectF(
            clamp(box.left / (float) width), clamp(box.top / (float) height),
            clamp(box.right / (float) width), clamp(box.bottom / (float) height));
        float widthFraction = normalized.width();
        float centerOffset = (float) Math.hypot(
            normalized.centerX() - 0.5f, normalized.centerY() - 0.44f);
        float leftEye = probability(face.getLeftEyeOpenProbability());
        float rightEye = probability(face.getRightEyeOpenProbability());
        float smile = probability(face.getSmilingProbability());

        if (config.checks.faceSize && widthFraction < 0.28f) state = FaceCaptureModels.State.MOVE_CLOSER;
        else if (config.checks.faceSize && widthFraction > 0.62f) state = FaceCaptureModels.State.MOVE_AWAY;
        else if (config.checks.centering && centerOffset > 0.13f) state = FaceCaptureModels.State.CENTER_FACE;
        else if (config.checks.pose && (Math.abs(face.getHeadEulerAngleY()) > 11f
            || Math.abs(face.getHeadEulerAngleX()) > 11f || Math.abs(face.getHeadEulerAngleZ()) > 9f)) {
            state = FaceCaptureModels.State.LOOK_STRAIGHT;
        } else if (config.checks.eyesOpen && (leftEye < 0.62f || rightEye < 0.62f)) {
            state = FaceCaptureModels.State.OPEN_EYES;
        } else if (config.checks.neutralExpression && smile > 0.38f) {
            state = FaceCaptureModels.State.NEUTRAL_EXPRESSION;
        } else if (config.checks.brightness && brightness < 0.20f) state = FaceCaptureModels.State.IMPROVE_LIGHTING;
        else if (config.checks.brightness && brightness > 0.88f) state = FaceCaptureModels.State.REDUCE_LIGHTING;
        else if (config.checks.sharpness && sharpness < 0.10f) state = FaceCaptureModels.State.HOLD_STILL;
        else state = FaceCaptureModels.State.READY;

        long stableMs = 0;
        if (state == FaceCaptureModels.State.READY && config.checks.stability) {
            stableMs = stability.update(normalized, SystemClock.elapsedRealtime());
            if (stableMs < 650L) state = FaceCaptureModels.State.HOLD_STILL;
        } else if (state != FaceCaptureModels.State.READY) {
            stability.clear();
        }
        return new FaceCaptureModels.Analysis(state, normalized, face.getHeadEulerAngleY(),
            face.getHeadEulerAngleX(), face.getHeadEulerAngleZ(), leftEye, rightEye, smile,
            brightness, sharpness, stableMs);
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
        return new FaceCaptureModels.Analysis(state, null, 0, 0, 0, -1, -1, -1,
            brightness, sharpness, 0);
    }

    private static float probability(Float value) {
        return value == null ? -1f : value;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
