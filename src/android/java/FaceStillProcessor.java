package com.andreszs.plugins.mlkitsuite;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;

import androidx.exifinterface.media.ExifInterface;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.UUID;

final class FaceStillProcessor {
    interface Callback {
        void onSuccess(Result result);
        void onRetry(String message);
        void onFailure(Exception error);
    }

    static final class Result {
        final File file;
        final int width;
        final int height;
        final float brightness;
        final float sharpness;
        final float yaw;
        final float pitch;
        final float roll;
        final float leftEye;
        final float rightEye;
        final float smile;

        Result(File file, int width, int height, float brightness, float sharpness,
               Face face) {
            this.file = file;
            this.width = width;
            this.height = height;
            this.brightness = brightness;
            this.sharpness = sharpness;
            yaw = face.getHeadEulerAngleY();
            pitch = face.getHeadEulerAngleX();
            roll = face.getHeadEulerAngleZ();
            leftEye = face.getLeftEyeOpenProbability() == null ? -1f : face.getLeftEyeOpenProbability();
            rightEye = face.getRightEyeOpenProbability() == null ? -1f : face.getRightEyeOpenProbability();
            smile = face.getSmilingProbability() == null ? -1f : face.getSmilingProbability();
        }
    }

    private final FaceCaptureConfig config;
    private final FaceDetector detector;

    FaceStillProcessor(FaceCaptureConfig config) {
        this.config = config;
        detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.12f)
            .build());
    }

    void process(File source, File outputDirectory, Callback callback) {
        try {
            Bitmap bitmap = orientedBitmap(source);
            detector.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(faces -> {
                    try {
                        Result result = createResult(bitmap, faces, outputDirectory);
                        callback.onSuccess(result);
                    } catch (RetryException retry) {
                        callback.onRetry(retry.getMessage());
                    } catch (Exception error) {
                        callback.onFailure(error);
                    } finally {
                        bitmap.recycle();
                    }
                })
                .addOnFailureListener(callback::onFailure);
        } catch (Exception error) {
            callback.onFailure(error);
        }
    }

    void close() {
        detector.close();
    }

    private Result createResult(Bitmap source, List<Face> faces, File directory) throws Exception {
        if (faces.size() != 1 && config.checks.singleFace) {
            throw new RetryException(faces.isEmpty()
                ? "No face was found in the captured photo."
                : "More than one face was found in the captured photo.");
        }
        if (faces.isEmpty()) throw new RetryException("No face was found in the captured photo.");
        Face face = largestFace(faces);
        validateFace(face, source.getWidth(), source.getHeight());
        Rect crop = cropRect(face.getBoundingBox(), source.getWidth(), source.getHeight(),
            config.outputWidth / (float) config.outputHeight);
        Bitmap cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height());
        Bitmap output = Bitmap.createScaledBitmap(cropped, config.outputWidth, config.outputHeight, true);
        if (cropped != output) cropped.recycle();

        float[] quality = quality(output);
        if (config.checks.brightness && quality[0] < 0.20f) {
            output.recycle();
            throw new RetryException("The captured photo is too dark.");
        }
        if (config.checks.brightness && quality[0] > 0.90f) {
            output.recycle();
            throw new RetryException("The captured photo is too bright.");
        }
        if (config.checks.sharpness && quality[1] < 0.10f) {
            output.recycle();
            throw new RetryException("The captured photo is not sharp enough.");
        }

        if (!directory.exists() && !directory.mkdirs()) {
            output.recycle();
            throw new IllegalStateException("Unable to create the image cache directory.");
        }
        File destination = new File(directory, "face-" + UUID.randomUUID().toString().toLowerCase() + ".jpg");
        try (FileOutputStream stream = new FileOutputStream(destination)) {
            if (!output.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, stream)) {
                throw new IllegalStateException("Unable to encode the captured JPEG.");
            }
        } finally {
            output.recycle();
        }
        return new Result(destination, config.outputWidth, config.outputHeight, quality[0], quality[1], face);
    }

    private void validateFace(Face face, int width, int height) throws RetryException {
        Rect box = face.getBoundingBox();
        float fraction = box.width() / (float) width;
        float offset = (float) Math.hypot(box.exactCenterX() / width - 0.5f,
            box.exactCenterY() / height - 0.44f);
        if (config.checks.faceSize && fraction < 0.22f) throw new RetryException("Move closer to the camera.");
        if (config.checks.centering && offset > 0.18f) throw new RetryException("Center your face and try again.");
        if (config.checks.pose && (Math.abs(face.getHeadEulerAngleY()) > 13f
            || Math.abs(face.getHeadEulerAngleX()) > 13f || Math.abs(face.getHeadEulerAngleZ()) > 11f)) {
            throw new RetryException("Look straight at the camera.");
        }
        Float left = face.getLeftEyeOpenProbability();
        Float right = face.getRightEyeOpenProbability();
        if (config.checks.eyesOpen && (left == null || right == null || left < 0.58f || right < 0.58f)) {
            throw new RetryException("Keep both eyes open.");
        }
        Float smile = face.getSmilingProbability();
        if (config.checks.neutralExpression && smile != null && smile > 0.42f) {
            throw new RetryException("Use a neutral expression.");
        }
    }

    private static Rect cropRect(Rect face, int width, int height, float aspect) {
        float cropHeight = Math.max(face.height() * 2.0f, face.width() / aspect * 1.35f);
        cropHeight = Math.min(cropHeight, height);
        float cropWidth = cropHeight * aspect;
        if (cropWidth > width) {
            cropWidth = width;
            cropHeight = cropWidth / aspect;
        }
        float centerX = face.exactCenterX();
        float centerY = face.exactCenterY() + face.height() * 0.18f;
        int left = Math.round(centerX - cropWidth / 2f);
        int top = Math.round(centerY - cropHeight / 2f);
        left = Math.max(0, Math.min(left, width - Math.round(cropWidth)));
        top = Math.max(0, Math.min(top, height - Math.round(cropHeight)));
        return new Rect(left, top, left + Math.round(cropWidth), top + Math.round(cropHeight));
    }

    private static Bitmap orientedBitmap(File file) throws Exception {
        Bitmap raw = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (raw == null) throw new IllegalStateException("Unable to decode the captured photo.");
        int orientation = new ExifInterface(file.getAbsolutePath()).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        Matrix matrix = new Matrix();
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) matrix.postRotate(90);
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) matrix.postRotate(180);
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) matrix.postRotate(270);
        Bitmap oriented = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, true);
        if (oriented != raw) raw.recycle();
        return oriented;
    }

    private static float[] quality(Bitmap bitmap) {
        int step = Math.max(2, Math.min(bitmap.getWidth(), bitmap.getHeight()) / 180);
        long sum = 0;
        long gradients = 0;
        int count = 0;
        for (int y = step; y < bitmap.getHeight(); y += step) {
            for (int x = step; x < bitmap.getWidth(); x += step) {
                int pixel = bitmap.getPixel(x, y);
                int previous = bitmap.getPixel(x - step, y);
                int luma = luma(pixel);
                sum += luma;
                gradients += Math.abs(luma - luma(previous));
                count++;
            }
        }
        return count == 0 ? new float[] {0, 0}
            : new float[] {sum / (count * 255f), Math.min(1f, gradients / (count * 32f))};
    }

    private static int luma(int color) {
        return (77 * ((color >> 16) & 0xff) + 150 * ((color >> 8) & 0xff)
            + 29 * (color & 0xff)) >> 8;
    }

    private static Face largestFace(List<Face> faces) {
        Face best = faces.get(0);
        for (Face face : faces) {
            if (face.getBoundingBox().width() * face.getBoundingBox().height()
                > best.getBoundingBox().width() * best.getBoundingBox().height()) best = face;
        }
        return best;
    }

    private static final class RetryException extends Exception {
        RetryException(String message) { super(message); }
    }
}
