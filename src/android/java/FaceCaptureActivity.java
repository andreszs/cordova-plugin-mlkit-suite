package com.andreszs.plugins.mlkitsuite;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FaceCaptureActivity extends Activity
    implements LifecycleOwner, FaceFrameAnalyzer.Listener, SensorEventListener {

    private static final long SUCCESS_BEEP_DELAY_MS = 250L;
    private static final long DEVICE_VERTICAL_HOLD_MS = 700L;
    private static final double DEVICE_VERTICAL_ENTER_DEGREES = 10d;
    private static final double DEVICE_VERTICAL_EXIT_DEGREES = 10d;

    public static final String EXTRA_OPTIONS_JSON = "mlKitSuite.face.options";
    public static final String EXTRA_IMAGE_WEB_BASE_URL = "mlKitSuite.face.imageWebBaseUrl";
    public static final String EXTRA_RESULT_JSON = "mlKitSuite.face.result";
    public static final String EXTRA_ERROR_CODE = "mlKitSuite.face.errorCode";
    public static final String EXTRA_ERROR_MESSAGE = "mlKitSuite.face.errorMessage";
    public static final String EXTRA_ERROR_RECOVERABLE = "mlKitSuite.face.errorRecoverable";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService captureExecutor = Executors.newSingleThreadExecutor();
    private LifecycleRegistry lifecycleRegistry;
    private FaceCaptureConfig config;
    private PreviewView previewView;
    private FaceOverlayView overlayView;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private FaceFrameAnalyzer analyzer;
    private FaceStillProcessor stillProcessor;
    private MlKitSuccessBeep successBeep;
    private String imageWebBaseUrl;
    private boolean capturing;
    private boolean capturingCandidate;
    private boolean completed;
    private FaceStillProcessor.Result pendingCandidate;
    private FaceCaptureModels.Analysis lastCaptureAnalysis;
    private Runnable timeoutRunnable;
    private SensorManager sensorManager;
    private Sensor verticalitySensor;
    private boolean deviceVerticalReady;
    private long deviceVerticalSince;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        try {
            config = FaceCaptureConfig.fromJson(this, new JSONObject(
                getIntent().getStringExtra(EXTRA_OPTIONS_JSON)));
            imageWebBaseUrl = getIntent().getStringExtra(EXTRA_IMAGE_WEB_BASE_URL);
            stillProcessor = new FaceStillProcessor(config);
            successBeep = new MlKitSuccessBeep(this, mainHandler);
            configureVerticalitySensor();
        } catch (Exception error) {
            finishWithError("INVALID_ARGUMENT", "Invalid face capture options.", false);
            return;
        }
        createInterface();
        scheduleTimeout();
        previewView.post(this::startCamera);
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override protected void onStart() {
        super.onStart();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
    }

    @Override protected void onResume() {
        super.onResume();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
        startVerticalityMonitoring();
    }

    @Override protected void onPause() {
        stopVerticalityMonitoring();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        super.onPause();
    }

    @Override protected void onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (!completed) discardPendingCandidate();
        completed = true;
        if (timeoutRunnable != null) mainHandler.removeCallbacks(timeoutRunnable);
        if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
        if (analyzer != null) analyzer.close();
        if (stillProcessor != null) stillProcessor.close();
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (successBeep != null) successBeep.close();
        analysisExecutor.shutdownNow();
        captureExecutor.shutdownNow();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        cancelByUser();
    }

    private void createInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, matchParent());

        overlayView = new FaceOverlayView(this, config.localization, config.debugMode,
            config.localization.text("face_searching"), config.gestures.size() + 2,
            config.captureAfterGesture, config.checks, !config.gestures.isEmpty(),
            config.requireDeviceVertical && verticalitySensor != null);
        root.addView(overlayView, matchParent());

        if (config.showCloseButton) {
            Button close = new Button(this);
            close.setText("×");
            close.setContentDescription(config.localization.text("close_face"));
            close.setTextColor(Color.WHITE);
            close.setTextSize(28);
            close.setAllCaps(false);
            close.setGravity(Gravity.CENTER);
            close.setPadding(0, 0, 0, dp(3));
            GradientDrawable closeBackground = new GradientDrawable();
            closeBackground.setShape(GradientDrawable.OVAL);
            closeBackground.setColor(0x66000000);
            closeBackground.setStroke(dp(1), 0x99ffffff);
            close.setBackground(closeBackground);
            close.setOnClickListener(view -> cancelByUser());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(44), dp(44));
            params.gravity = Gravity.TOP | Gravity.START;
            params.topMargin = dp(12);
            params.leftMargin = dp(12);
            root.addView(close, params);
        }
        setContentView(root);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    finishWithError("CAMERA_UNAVAILABLE",
                        config.localization.text("front_camera_missing"), false);
                    return;
                }
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation())
                    .build();
                imageAnalysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(new android.util.Size(1280, 720))
                    .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation())
                    .build();
                analyzer = new FaceFrameAnalyzer(config, this, ContextCompat.getMainExecutor(this));
                analyzer.setDeviceVerticalReady(deviceVerticalReady);
                imageAnalysis.setAnalyzer(analysisExecutor, analyzer);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview, imageAnalysis, imageCapture);
                previewView.post(this::updateAnalyzerGeometry);
            } catch (Exception error) {
                finishWithError("CAMERA_UNAVAILABLE",
                    config.localization.format("front_camera_start_failed", error.getMessage()), true);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onAnalysis(FaceCaptureModels.Analysis analysis) {
        if (completed || capturing) return;
        updateAnalyzerGeometry();
        boolean candidateCapture = analysis.state == FaceCaptureModels.State.GESTURE
            && analysis.gestureProgress != null
            && analysis.gestureProgress.stage == FaceCaptureModels.GestureStage.CAPTURE;
        // A CAPTURE analysis may already be queued when the candidate finishes. It belongs to
        // the previous paused tracker state and must not trigger a second photo.
        if (candidateCapture && pendingCandidate != null) return;
        overlayView.setProgressCompleted(progressCompleted(analysis));
        if (analysis.state == FaceCaptureModels.State.READY && pendingCandidate == null) {
            overlayView.update(analysis.withState(FaceCaptureModels.State.HOLD_STILL,
                analysis.stableDurationMs), config.localization.text("face_hold_still"));
        } else if (analysis.state != FaceCaptureModels.State.READY
            && (!candidateCapture || config.revealCapture)) {
            overlayView.update(analysis, messageFor(analysis));
        }
        if (analysis.state == FaceCaptureModels.State.GESTURE
            && analysis.gestureProgress != null) {
            if (analysis.gestureProgress.stage == FaceCaptureModels.GestureStage.RETRY) {
                discardPendingCandidate();
            } else if (analysis.gestureProgress.stage == FaceCaptureModels.GestureStage.CAPTURE) {
                beginCapture(analysis, true);
            }
        } else if (analysis.state == FaceCaptureModels.State.READY) {
            if (pendingCandidate != null) finishWithResult(pendingCandidate);
            else beginCapture(analysis, false);
        }
    }

    private void updateAnalyzerGeometry() {
        if (analyzer == null || previewView == null || overlayView == null) return;
        analyzer.setPreviewGeometry(previewView.getWidth(), previewView.getHeight(),
            overlayView.centeringGuide());
    }

    @Override
    public void onAnalyzerError(Exception error) {
        if (!completed && !capturing) {
            overlayView.update(null, config.localization.text("face_analyze_failed"));
        }
    }

    private void beginCapture(FaceCaptureModels.Analysis analysis, boolean candidate) {
        if (capturing || completed || imageCapture == null) return;
        capturing = true;
        capturingCandidate = candidate;
        lastCaptureAnalysis = analysis;
        if (config.revealCapture) {
            overlayView.update(analysis.withState(FaceCaptureModels.State.CAPTURING,
                analysis.stableDurationMs), config.localization.text("face_capturing"));
        }
        mainHandler.postDelayed(this::capturePhoto, 150L);
    }

    private void capturePhoto() {
        if (completed || imageCapture == null) return;
        File raw = new File(getCacheDir(), "mlkit-suite-raw-" + System.nanoTime() + ".jpg");
        ImageCapture.OutputFileOptions output = new ImageCapture.OutputFileOptions.Builder(raw).build();
        imageCapture.takePicture(output, captureExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults result) {
                File directory = new File(getCacheDir(), "mlkit-suite");
                stillProcessor.process(raw, directory, new FaceStillProcessor.Callback() {
                    @Override public void onSuccess(FaceStillProcessor.Result processed) {
                        raw.delete();
                        mainHandler.post(() -> handleCapturedResult(processed));
                    }
                    @Override public void onRetry(String message) {
                        raw.delete();
                        mainHandler.post(() -> retry(message));
                    }
                    @Override public void onFailure(Exception error) {
                        raw.delete();
                        mainHandler.post(() -> finishWithError("PROCESSING_FAILED",
                            config.localization.format("face_process_failed", error.getMessage()), true));
                    }
                });
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                raw.delete();
                mainHandler.post(() -> retry(config.localization.text("face_capture_retry")));
            }
        });
    }

    private void retry(String message) {
        if (completed) return;
        if (!config.gestures.isEmpty() && analyzer != null) analyzer.retryGestures();
        discardPendingCandidate();
        overlayView.setProgressCompleted(0);
        overlayView.update(null, message);
        mainHandler.postDelayed(() -> {
            if (!completed) {
                capturing = false;
                capturingCandidate = false;
                overlayView.update(null, config.localization.text("face_searching"));
            }
        }, config.retryMessageMs);
    }

    private void handleCapturedResult(FaceStillProcessor.Result processed) {
        if (!capturingCandidate || config.gestures.isEmpty()
            || config.captureAfterGesture >= config.gestures.size()) {
            finishWithResult(processed);
            return;
        }
        discardPendingCandidate();
        pendingCandidate = processed;
        overlayView.setProgressCompleted(Math.min(config.gestures.size() + 2,
            config.captureAfterGesture + 2));
        capturing = false;
        capturingCandidate = false;
        analyzer.resumeAfterCandidateCapture();
    }

    private void discardPendingCandidate() {
        if (pendingCandidate != null) {
            pendingCandidate.file.delete();
            pendingCandidate = null;
        }
    }

    private void finishWithResult(FaceStillProcessor.Result value) {
        if (completed) return;
        overlayView.setProgressCompleted(config.gestures.size() + 2);
        if (lastCaptureAnalysis != null) {
            overlayView.update(lastCaptureAnalysis.withState(FaceCaptureModels.State.READY,
                lastCaptureAnalysis.stableDurationMs), config.localization.text("face_ready"));
        }
        if (pendingCandidate == value) pendingCandidate = null;
        completed = true;
        JSONObject result = new JSONObject();
        JSONObject face = new JSONObject();
        JSONObject quality = new JSONObject();
        try {
            result.put("status", "success");
            result.put("imageUri", imageWebBaseUrl + value.file.getName());
            result.put("imagePath", value.file.getAbsolutePath());
            result.put("width", value.width);
            result.put("height", value.height);
            result.put("mimeType", "image/jpeg");
            result.put("bytes", value.file.length());
            result.put("capturedAt", isoTimestamp());
            result.put("quality", quality);
            quality.put("brightness", round(value.brightness));
            quality.put("sharpness", round(value.sharpness));
            result.put("face", face);
            face.put("yaw", round(value.yaw));
            face.put("pitch", round(value.pitch));
            face.put("roll", round(value.roll));
            face.put("leftEyeOpenProbability", nullableProbability(value.leftEye));
            face.put("rightEyeOpenProbability", nullableProbability(value.rightEye));
            face.put("smilingProbability", nullableProbability(value.smile));
            result.put("appliedChecks", config.checks.toJson());
            result.put("completedGestures", config.gesturesJson());
            result.put("captureAfterGesture", config.captureAfterGesture);
        } catch (Exception ignored) { }
        Intent intent = new Intent();
        intent.putExtra(EXTRA_RESULT_JSON, result.toString());
        if (config.beepEnabled && successBeep != null) {
            successBeep.play();
            mainHandler.postDelayed(() -> {
                setResult(RESULT_OK, intent);
                finish();
            }, SUCCESS_BEEP_DELAY_MS);
        } else {
            setResult(RESULT_OK, intent);
            finish();
        }
    }

    private void cancelByUser() {
        if (completed) return;
        discardPendingCandidate();
        completed = true;
        Intent intent = new Intent();
        intent.putExtra(EXTRA_ERROR_CODE, "USER_CANCELLED");
        intent.putExtra(EXTRA_ERROR_MESSAGE, config.localization.text("face_cancelled"));
        intent.putExtra(EXTRA_ERROR_RECOVERABLE, true);
        setResult(RESULT_CANCELED, intent);
        finish();
    }

    private void finishWithError(String code, String message, boolean recoverable) {
        if (completed) return;
        discardPendingCandidate();
        completed = true;
        Intent intent = new Intent();
        intent.putExtra(EXTRA_ERROR_CODE, code);
        intent.putExtra(EXTRA_ERROR_MESSAGE, message);
        intent.putExtra(EXTRA_ERROR_RECOVERABLE, recoverable);
        setResult(RESULT_CANCELED, intent);
        finish();
    }

    private void scheduleTimeout() {
        if (config.timeoutMs <= 0) return;
        timeoutRunnable = () -> finishWithError("TIMEOUT",
            config.localization.text("face_timeout"), true);
        mainHandler.postDelayed(timeoutRunnable, config.timeoutMs);
    }

    private String messageFor(FaceCaptureModels.Analysis analysis) {
        switch (analysis.state) {
            case MULTIPLE_FACES: return config.localization.text("face_multiple");
            case MOVE_CLOSER: return config.localization.text("face_move_closer");
            case MOVE_AWAY: return config.localization.text("face_move_away");
            case CENTER_FACE: return config.localization.text("face_center");
            case LOOK_STRAIGHT: return config.localization.text("face_look_straight");
            case OPEN_EYES: return config.localization.text("face_open_eyes");
            case NEUTRAL_EXPRESSION: return config.localization.text("face_neutral");
            case IMPROVE_LIGHTING: return config.localization.text("face_more_light");
            case REDUCE_LIGHTING: return config.localization.text("face_less_light");
            case HOLD_STILL: return config.localization.text("face_hold_still");
            case DEVICE_TILTED: return config.localization.text("face_device_vertical");
            case GESTURE: return gestureMessage(analysis);
            case READY: return config.localization.text("face_ready");
            case CAPTURING: return config.localization.text("face_capturing");
            default: return config.localization.text("face_instruction");
        }
    }

    private void configureVerticalitySensor() {
        deviceVerticalReady = !config.requireDeviceVertical;
        if (!config.requireDeviceVertical) return;
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            verticalitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (verticalitySensor == null) {
                verticalitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        }
        // Do not block capture on devices that expose no usable orientation sensor.
        if (verticalitySensor == null) deviceVerticalReady = true;
    }

    private void startVerticalityMonitoring() {
        if (sensorManager == null || verticalitySensor == null) return;
        deviceVerticalReady = false;
        deviceVerticalSince = 0L;
        if (analyzer != null) analyzer.setDeviceVerticalReady(false);
        sensorManager.registerListener(this, verticalitySensor, SensorManager.SENSOR_DELAY_UI);
    }

    private void stopVerticalityMonitoring() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!config.requireDeviceVertical || event.values.length < 3) return;
        double x = event.values[0];
        double y = event.values[1];
        double z = event.values[2];
        double tiltDegrees = Math.toDegrees(Math.atan2(Math.hypot(x, z), Math.abs(y)));
        double limit = deviceVerticalReady
            ? DEVICE_VERTICAL_EXIT_DEGREES : DEVICE_VERTICAL_ENTER_DEGREES;
        long now = SystemClock.elapsedRealtime();
        boolean nextReady = deviceVerticalReady;
        if (tiltDegrees > limit) {
            deviceVerticalSince = 0L;
            nextReady = false;
        } else {
            if (deviceVerticalSince == 0L) deviceVerticalSince = now;
            if (now - deviceVerticalSince >= DEVICE_VERTICAL_HOLD_MS) nextReady = true;
        }
        if (nextReady != deviceVerticalReady) {
            deviceVerticalReady = nextReady;
            if (analyzer != null) analyzer.setDeviceVerticalReady(nextReady);
        }
        if (overlayView != null) {
            overlayView.updateDeviceTilt((float) tiltDegrees, deviceVerticalReady);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private String gestureMessage(FaceCaptureModels.Analysis analysis) {
        FaceCaptureModels.GestureProgress progress = analysis == null
            ? null : analysis.gestureProgress;
        if (progress == null || progress.stage == FaceCaptureModels.GestureStage.COMPLETE) {
            return config.localization.text("face_gestures_complete");
        }
        String action;
        if (progress.stage == FaceCaptureModels.GestureStage.RETRY) {
            return config.localization.text("face_gesture_retry");
        } else if (progress.stage == FaceCaptureModels.GestureStage.CAPTURE) {
            return config.localization.text("face_gesture_capture_candidate");
        } else if (progress.stage == FaceCaptureModels.GestureStage.WAITING_NEUTRAL) {
            if (analysis.pitch > 11f) {
                action = config.localization.text("face_lower_head");
            } else if (analysis.pitch < -11f) {
                action = config.localization.text("face_raise_head");
            } else {
                action = config.localization.text("face_gesture_prepare");
            }
        } else if (progress.stage == FaceCaptureModels.GestureStage.RETURN_NEUTRAL) {
            action = config.localization.text("face_gesture_return");
        } else {
            int phase = progress.debug == null ? 0 : progress.debug.phase;
            if (progress.gesture == FaceCaptureModels.Gesture.NOD) {
                action = config.localization.text(phase == 0
                    ? "face_gesture_nod_up" : "face_gesture_nod_down");
            } else if (progress.gesture == FaceCaptureModels.Gesture.SHAKE) {
                action = config.localization.text(phase == 0
                    ? "face_gesture_shake_right" : "face_gesture_shake_left");
            } else {
                action = config.localization.text("face_gesture_"
                    + progress.gesture.name().toLowerCase(Locale.US));
            }
        }
        return action;
    }

    private int progressCompleted(FaceCaptureModels.Analysis analysis) {
        int total = config.gestures.size() + 2;
        if (config.gestures.isEmpty()) {
            return analysis.state == FaceCaptureModels.State.READY
                || analysis.state == FaceCaptureModels.State.CAPTURING ? 1 : 0;
        }
        FaceCaptureModels.GestureProgress progress = analysis.gestureProgress;
        if (progress == null || progress.stage == FaceCaptureModels.GestureStage.RETRY) return 0;
        boolean faceAccepted = !(progress.index == 0
            && progress.stage == FaceCaptureModels.GestureStage.WAITING_NEUTRAL);
        int gesturesCompleted = progress.index;
        if (progress.stage == FaceCaptureModels.GestureStage.RETURN_NEUTRAL) {
            gesturesCompleted++;
        } else if (progress.stage == FaceCaptureModels.GestureStage.COMPLETE) {
            gesturesCompleted = progress.count;
        }
        int captureCompleted = pendingCandidate == null ? 0 : 1;
        return Math.min(total, (faceAccepted ? 1 : 0) + gesturesCompleted + captureCompleted);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static Object nullableProbability(float value) {
        return value < 0 ? JSONObject.NULL : round(value);
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private static String isoTimestamp() {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter.format(new Date());
    }
}
