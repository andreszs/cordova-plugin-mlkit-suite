package com.andreszs.plugins.mlkitsuite;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.apache.cordova.CallbackContext;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.List;

final class StaticImageProcessor {
    private StaticImageProcessor() { }

    static void recognizeText(Context context, String rawPath, File cacheDirectory,
                              CallbackContext callback) {
        InputImage image;
        try {
            image = resolveImage(context, rawPath, cacheDirectory);
        } catch (Exception exception) {
            callback.error(MlKitSuitePlugin.error(
                "INVALID_IMAGE", "Unable to open imagePath.", false
            ));
            return;
        }
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(image)
            .addOnSuccessListener(result -> {
                callback.success(result.getText());
                recognizer.close();
            })
            .addOnFailureListener(error -> {
                callback.error(MlKitSuitePlugin.error(
                    "PROCESSING_FAILED", "Unable to recognize text in the image.", true
                ));
                recognizer.close();
            });
    }

    static void scanBarcodes(Context context, String rawPath, File cacheDirectory,
                             int[] formats, CallbackContext callback) {
        InputImage image;
        try {
            image = resolveImage(context, rawPath, cacheDirectory);
        } catch (Exception exception) {
            callback.error(MlKitSuitePlugin.error(
                "INVALID_IMAGE", "Unable to open imagePath.", false
            ));
            return;
        }
        BarcodeScannerOptions.Builder builder = new BarcodeScannerOptions.Builder();
        if (formats.length > 0) {
            int[] additional = new int[Math.max(0, formats.length - 1)];
            if (additional.length > 0) System.arraycopy(formats, 1, additional, 0, additional.length);
            builder.setBarcodeFormats(formats[0], additional);
        }
        BarcodeScanner scanner = BarcodeScanning.getClient(builder.build());
        scanner.process(image)
            .addOnSuccessListener(barcodes -> {
                callback.success(toJson(barcodes));
                scanner.close();
            })
            .addOnFailureListener(error -> {
                callback.error(MlKitSuitePlugin.error(
                    "PROCESSING_FAILED", "Unable to scan barcodes in the image.", true
                ));
                scanner.close();
            });
    }

    private static InputImage resolveImage(Context context, String rawPath, File cacheDirectory)
        throws Exception {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new IllegalArgumentException("imagePath is empty.");
        }
        String value = rawPath.trim();
        Uri uri;
        if (value.startsWith("https://localhost/_mlkit_suite_image_/")
            || value.startsWith("http://localhost/_mlkit_suite_image_/")) {
            String name = value.substring(value.lastIndexOf('/') + 1).split("\\?", 2)[0];
            File file = new File(cacheDirectory, name).getCanonicalFile();
            if (!cacheDirectory.getCanonicalFile().equals(file.getParentFile()) || !file.isFile()) {
                throw new IllegalArgumentException("Plugin image is unavailable.");
            }
            uri = Uri.fromFile(file);
        } else if (value.startsWith("file:") || value.startsWith("content:")) {
            uri = Uri.parse(value);
        } else {
            File file = new File(value);
            if (!file.isFile()) throw new IllegalArgumentException("Image file does not exist.");
            uri = Uri.fromFile(file);
        }
        return InputImage.fromFilePath(context, uri);
    }

    private static JSONArray toJson(List<Barcode> barcodes) {
        JSONArray values = new JSONArray();
        for (Barcode barcode : barcodes) {
            JSONObject value = new JSONObject();
            try {
                String text = barcode.getRawValue();
                if (text == null) text = barcode.getDisplayValue();
                value.put("text", text == null ? "" : text);
                value.put("format", BarcodeScanConfig.nameForFormat(barcode.getFormat()));
                value.put("valueType", barcode.getValueType());
                Rect bounds = barcode.getBoundingBox();
                if (bounds == null) {
                    value.put("boundingBox", JSONObject.NULL);
                } else {
                    JSONObject box = new JSONObject();
                    box.put("x", bounds.left);
                    box.put("y", bounds.top);
                    box.put("width", bounds.width());
                    box.put("height", bounds.height());
                    value.put("boundingBox", box);
                }
                JSONArray points = new JSONArray();
                Point[] corners = barcode.getCornerPoints();
                if (corners != null) {
                    for (Point corner : corners) {
                        JSONObject point = new JSONObject();
                        point.put("x", corner.x);
                        point.put("y", corner.y);
                        points.put(point);
                    }
                }
                value.put("cornerPoints", points);
                values.put(value);
            } catch (Exception ignored) { }
        }
        return values;
    }
}
