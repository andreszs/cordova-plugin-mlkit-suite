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
        void onOutsideGuide();
        boolean isInsideGuide(Barcode barcode, int imageWidth, int imageHeight);
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
        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        int imageWidth = rotation % 180 == 0 ? imageProxy.getWidth() : imageProxy.getHeight();
        int imageHeight = rotation % 180 == 0 ? imageProxy.getHeight() : imageProxy.getWidth();
        scanner.process(image)
            .addOnSuccessListener(callbackExecutor,
                barcodes -> handleBarcodes(barcodes, imageWidth, imageHeight))
            .addOnFailureListener(callbackExecutor, error -> {
                tracker.reset();
                listener.onAnalyzerError(error);
            })
            .addOnCompleteListener(task -> imageProxy.close());
    }

    private void handleBarcodes(List<Barcode> barcodes, int imageWidth, int imageHeight) {
        if (completed) return;
        Barcode selected = null;
        boolean detectedOutsideGuide = false;
        for (Barcode barcode : barcodes) {
            String text = BarcodeTextDecoder.decode(
                barcode.getRawBytes(), barcode.getRawValue(), barcode.getDisplayValue()
            );
            if (!text.isEmpty()) {
                if (listener.isInsideGuide(barcode, imageWidth, imageHeight)) {
                    selected = barcode;
                    break;
                }
                detectedOutsideGuide = true;
            }
        }
        if (selected == null) {
            tracker.reset();
            if (detectedOutsideGuide) listener.onOutsideGuide();
            else listener.onSearching();
            return;
        }

        String text = BarcodeTextDecoder.decode(
            selected.getRawBytes(), selected.getRawValue(), selected.getDisplayValue()
        );
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
