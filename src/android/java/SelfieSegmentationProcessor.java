package com.andreszs.plugins.mlkitsuite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.Executor;

final class SelfieSegmentationProcessor {
    interface Callback {
        void onSuccess(Result result);
        void onFailure(String code, String message, boolean recoverable);
    }

    static final class Result {
        final File file;
        final int width;
        final int height;

        Result(File file, int width, int height) {
            this.file = file;
            this.width = width;
            this.height = height;
        }
    }

    private SelfieSegmentationProcessor() { }

    static void process(Context context, String rawPath, File cacheDirectory, int maxDimension,
                        Executor callbackExecutor, MlKitLocalization localization,
                        Callback callback) {
        final Bitmap source;
        try {
            Uri uri = StaticImageProcessor.resolveUri(rawPath, cacheDirectory);
            source = loadOrientedBitmap(context, uri, maxDimension);
        } catch (Exception exception) {
            callback.onFailure("INVALID_IMAGE", localization.text("image_open_failed"), false);
            return;
        }

        SelfieSegmenterOptions options = new SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build();
        Segmenter segmenter = Segmentation.getClient(options);
        segmenter.process(InputImage.fromBitmap(source, 0))
            .addOnSuccessListener(callbackExecutor, mask -> {
                try {
                    callback.onSuccess(writeCutout(source, mask, cacheDirectory));
                } catch (Exception exception) {
                    callback.onFailure(
                        "PROCESSING_FAILED",
                        localization.text("selfie_segmentation_failed"),
                        true
                    );
                } finally {
                    source.recycle();
                    segmenter.close();
                }
            })
            .addOnFailureListener(callbackExecutor, error -> {
                source.recycle();
                segmenter.close();
                callback.onFailure(
                    "PROCESSING_FAILED",
                    localization.text("selfie_segmentation_failed"),
                    true
                );
            });
    }

    private static Result writeCutout(Bitmap source, SegmentationMask mask, File directory)
        throws Exception {
        int width = source.getWidth();
        int height = source.getHeight();
        int maskWidth = mask.getWidth();
        int maskHeight = mask.getHeight();
        ByteBuffer confidence = mask.getBuffer();
        confidence.rewind();

        float[] maskValues = new float[maskWidth * maskHeight];
        for (int index = 0; index < maskValues.length; index++) {
            maskValues[index] = confidence.getFloat();
        }
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int y = 0; y < height; y++) {
            int maskY = Math.min(maskHeight - 1, y * maskHeight / height);
            for (int x = 0; x < width; x++) {
                int maskX = Math.min(maskWidth - 1, x * maskWidth / width);
                float value = maskValues[maskY * maskWidth + maskX];
                int alpha = Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
                int offset = y * width + x;
                pixels[offset] = (pixels[offset] & 0x00ffffff) | (alpha << 24);
            }
        }

        Bitmap cutout = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        if (!directory.exists() && !directory.mkdirs()) {
            cutout.recycle();
            throw new IllegalStateException("Unable to create the image cache directory.");
        }
        File destination = new File(
            directory,
            "selfie-" + UUID.randomUUID().toString().toLowerCase() + ".png"
        );
        try (FileOutputStream stream = new FileOutputStream(destination)) {
            if (!cutout.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new IllegalStateException("Unable to encode the segmented PNG.");
            }
        } finally {
            cutout.recycle();
        }
        return new Result(destination, width, height);
    }

    private static Bitmap loadOrientedBitmap(Context context, Uri uri, int maxDimension)
        throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IllegalStateException("Unable to open image.");
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        int sampleSize = 1;
        int largestBound = Math.max(bounds.outWidth, bounds.outHeight);
        while (largestBound > 0 && largestBound / sampleSize > maxDimension * 2) {
            sampleSize *= 2;
        }
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = sampleSize;
        decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap raw;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IllegalStateException("Unable to open image.");
            raw = BitmapFactory.decodeStream(stream, null, decodeOptions);
        }
        if (raw == null) throw new IllegalStateException("Unable to decode image.");

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream != null) {
                orientation = new ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                );
            }
        } catch (Exception ignored) { }

        Matrix matrix = orientationMatrix(orientation);
        Bitmap oriented = Bitmap.createBitmap(
            raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, true
        );
        if (oriented != raw) raw.recycle();

        int largest = Math.max(oriented.getWidth(), oriented.getHeight());
        if (largest <= maxDimension) return oriented;
        float scale = maxDimension / (float) largest;
        Bitmap resized = Bitmap.createScaledBitmap(
            oriented,
            Math.max(1, Math.round(oriented.getWidth() * scale)),
            Math.max(1, Math.round(oriented.getHeight() * scale)),
            true
        );
        if (resized != oriented) oriented.recycle();
        return resized;
    }

    private static Matrix orientationMatrix(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(-90f);
                break;
            default:
                break;
        }
        return matrix;
    }
}
