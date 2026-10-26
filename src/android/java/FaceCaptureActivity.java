package com.andreszs.plugins.mlkitsuite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    implements LifecycleOwner, FaceFrameAnalyzer.Listener {

    private static final long SUCCESS_BEEP_DELAY_MS = 250L;

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
    private boolean completed;
    private Runnable timeoutRunnable;

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
    }

    @Override protected void onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        super.onPause();
    }

    @Override protected void onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
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

        overlayView = new FaceOverlayView(this, config.instructionText, config.debugMode,
            config.localization.text("face_searching"));
        root.addView(overlayView, matchParent());

        if (config.showCloseButton) {
            Button close = new Button(this);
            close.setText(config.localization.text("close"));
            close.setContentDescription(config.localization.text("close_face"));
            close.setTextColor(Color.WHITE);
            close.setTextSize(16);
            close.setAllCaps(false);
            close.setBackgroundColor(Color.TRANSPARENT);
            close.setOnClickListener(view -> cancelByUser());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(100), dp(52));
            params.gravity = Gravity.TOP | Gravity.START;
            params.topMargin = dp(12);
            params.leftMargin = dp(8);
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
                imageAnalysis.setAnalyzer(analysisExecutor, analyzer);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview, imageAnalysis, imageCapture);
            } catch (Exception error) {
                finishWithError("CAMERA_UNAVAILABLE",
                    config.localization.format("front_camera_start_failed", error.getMessage()), true);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onAnalysis(FaceCaptureModels.Analysis analysis) {
        if (completed || capturing) return;
        overlayView.update(analysis, messageFor(analysis.state));
        if (analysis.state == FaceCaptureModels.State.READY) beginCapture(analysis);
    }

    @Override
    public void onAnalyzerError(Exception error) {
        if (!completed && !capturing) {
            overlayView.update(null, config.localization.text("face_analyze_failed"));
        }
    }

    private void beginCapture(FaceCaptureModels.Analysis analysis) {
        if (capturing || completed || imageCapture == null) return;
        capturing = true;
        overlayView.update(analysis.withState(FaceCaptureModels.State.CAPTURING,
            analysis.stableDurationMs), config.localization.text("face_capturing"));
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
                        mainHandler.post(() -> finishWithResult(processed));
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
        overlayView.update(null, message);
        mainHandler.postDelayed(() -> {
            if (!completed) {
                capturing = false;
                overlayView.update(null, config.localization.text("face_searching"));
            }
        }, 900L);
    }

    private void finishWithResult(FaceStillProcessor.Result value) {
        if (completed) return;
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

    private String messageFor(FaceCaptureModels.State state) {
        switch (state) {
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
            case READY: return config.localization.text("face_ready");
            case CAPTURING: return config.localization.text("face_capturing");
            default: return config.localization.text("face_instruction");
        }
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
