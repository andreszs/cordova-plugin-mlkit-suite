package com.andreszs.plugins.mlkitsuite;

import android.media.Image;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

final class BarcodeFrameAnalyzer implements ImageAnalysis.Analyzer, AutoCloseable {
    interface Listener {
        void onSearching();
        void onCandidate(String text, String format, int confirmedFrames, int requiredFrames);
        void onConfirmed(String text, String format);
        void onAnalyzerError(Exception error);
    }

    private final BarcodeScanner scanner;
    private final BarcodeConfirmationTracker tracker =
        new BarcodeConfirmationTracker(BarcodeScanConfig.CONFIRMATION_FRAMES);
    private final Listener listener;
    private final Executor callbackExecutor;
    private boolean completed;

    BarcodeFrameAnalyzer(BarcodeScanConfig config, Listener listener, Executor callbackExecutor) {
        int[] values = config.formatValues;
        int[] additional = Arrays.copyOfRange(values, 1, values.length);
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
            .setBarcodeFormats(values[0], additional)
            .build();
        scanner = BarcodeScanning.getClient(options);
        this.listener = listener;
        this.callbackExecutor = callbackExecutor;
    }

    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        if (completed) {
            imageProxy.close();
            return;
        }
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            tracker.reset();
            callbackExecutor.execute(listener::onSearching);
            imageProxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(mediaImage,
            imageProxy.getImageInfo().getRotationDegrees());
        scanner.process(image)
            .addOnSuccessListener(callbackExecutor, this::handleBarcodes)
            .addOnFailureListener(callbackExecutor, error -> {
                tracker.reset();
                listener.onAnalyzerError(error);
            })
            .addOnCompleteListener(task -> imageProxy.close());
    }

    private void handleBarcodes(List<Barcode> barcodes) {
        if (completed) return;
        Barcode selected = null;
        for (Barcode barcode : barcodes) {
            String rawValue = barcode.getRawValue();
            if (rawValue != null && !rawValue.isEmpty()) {
                selected = barcode;
                break;
            }
        }
        if (selected == null) {
            tracker.reset();
            listener.onSearching();
            return;
        }

        String text = selected.getRawValue();
        String format = BarcodeScanConfig.nameForFormat(selected.getFormat());
        int frames = tracker.observe(text, format);
        listener.onCandidate(text, format, frames, BarcodeScanConfig.CONFIRMATION_FRAMES);
        if (tracker.isConfirmed()) {
            completed = true;
            listener.onConfirmed(text, format);
        }
    }

    @Override
    public void close() {
        completed = true;
        scanner.close();
    }
}
