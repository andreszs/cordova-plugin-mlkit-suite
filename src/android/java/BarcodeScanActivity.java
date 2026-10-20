package com.andreszs.plugins.mlkitsuite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.common.Barcode;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BarcodeScanActivity extends Activity
    implements LifecycleOwner, BarcodeFrameAnalyzer.Listener {

    public static final String EXTRA_OPTIONS_JSON = "mlKitSuite.barcode.options";
    public static final String EXTRA_RESULT_JSON = "mlKitSuite.barcode.result";
    public static final String EXTRA_ERROR_CODE = "mlKitSuite.barcode.errorCode";
    public static final String EXTRA_ERROR_MESSAGE = "mlKitSuite.barcode.errorMessage";
    public static final String EXTRA_ERROR_RECOVERABLE = "mlKitSuite.barcode.errorRecoverable";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private LifecycleRegistry lifecycleRegistry;
    private BarcodeScanConfig config;
    private PreviewView previewView;
    private BarcodeOverlayView overlayView;
    private Button torchButton;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private BarcodeFrameAnalyzer analyzer;
    private Camera camera;
    private boolean torchEnabled;
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
            config = BarcodeScanConfig.fromJson(new JSONObject(
                getIntent().getStringExtra(EXTRA_OPTIONS_JSON)));
        } catch (Exception error) {
            finishWithError("INVALID_ARGUMENT", "Invalid barcode scan options.", false);
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
        if (cameraProvider != null) cameraProvider.unbindAll();
        analysisExecutor.shutdownNow();
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

        overlayView = new BarcodeOverlayView(this, config.instructionText, config.guideColor);
        root.addView(overlayView, matchParent());

        if (config.showCloseButton) {
            Button close = cameraButton("Close");
            close.setOnClickListener(view -> cancelByUser());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(100), dp(52));
            params.gravity = Gravity.TOP | Gravity.START;
            params.topMargin = dp(12);
            params.leftMargin = dp(8);
            root.addView(close, params);
        }

        if (config.showTorchButton) {
            torchButton = cameraButton("Torch off");
            torchButton.setVisibility(View.GONE);
            torchButton.setOnClickListener(view -> toggleTorch());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(112), dp(52));
            params.gravity = Gravity.TOP | Gravity.END;
            params.topMargin = dp(12);
            params.rightMargin = dp(8);
            root.addView(torchButton, params);
        }
        setContentView(root);
    }

    private Button cameraButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    finishWithError("CAMERA_UNAVAILABLE", "A rear camera is not available.", false);
                    return;
                }
                Size resolution = new Size(BarcodeScanConfig.TARGET_WIDTH,
                    BarcodeScanConfig.TARGET_HEIGHT);
                Preview preview = new Preview.Builder()
                    .setTargetResolution(resolution)
                    .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation())
                    .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageAnalysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(resolution)
                    .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation())
                    .build();
                analyzer = new BarcodeFrameAnalyzer(config, this,
                    ContextCompat.getMainExecutor(this));
                imageAnalysis.setAnalyzer(analysisExecutor, analyzer);
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageAnalysis);
                if (torchButton != null && camera.getCameraInfo().hasFlashUnit()) {
                    torchButton.setVisibility(View.VISIBLE);
                }
            } catch (Exception error) {
                finishWithError("CAMERA_UNAVAILABLE", "Unable to start the rear camera: "
                    + error.getMessage(), true);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchEnabled = !torchEnabled;
        camera.getCameraControl().enableTorch(torchEnabled);
        torchButton.setText(torchEnabled ? "Torch on" : "Torch off");
    }

    @Override
    public void onSearching() {
        if (!completed) overlayView.update("Looking for a barcode", false);
    }

    @Override
    public void onOutsideGuide() {
        if (!completed) overlayView.update("Center the barcode inside the frame", false);
    }

    @Override
    public boolean isInsideGuide(Barcode barcode, int imageWidth, int imageHeight) {
        return !completed && overlayView.containsBarcode(
            barcode.getBoundingBox(), imageWidth, imageHeight
        );
    }

    @Override
    public void onCandidate(String text, String format, int frames, int requiredFrames) {
        if (!completed) {
            overlayView.update("Detected " + format + " — hold steady (" + frames
                + "/" + requiredFrames + ")", true);
        }
    }

    @Override
    public void onConfirmed(String text, String format) {
        if (completed) return;
        completed = true;
        JSONObject result = new JSONObject();
        try {
            result.put("status", "success");
            result.put("text", text);
            result.put("format", format);
        } catch (Exception ignored) { }
        Intent intent = new Intent();
        intent.putExtra(EXTRA_RESULT_JSON, result.toString());
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    public void onAnalyzerError(Exception error) {
        if (!completed) overlayView.update("Unable to analyze the camera image", false);
    }

    private void cancelByUser() {
        if (completed) return;
        completed = true;
        Intent intent = new Intent();
        intent.putExtra(EXTRA_ERROR_CODE, "USER_CANCELLED");
        intent.putExtra(EXTRA_ERROR_MESSAGE, "Barcode scanning was cancelled by the user.");
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
            "No barcode was confirmed before the timeout.", true);
        mainHandler.postDelayed(timeoutRunnable, config.timeoutMs);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
