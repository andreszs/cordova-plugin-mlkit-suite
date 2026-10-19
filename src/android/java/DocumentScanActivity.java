package com.andreszs.plugins.mlkitsuite;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import com.google.mlkit.common.MlKitException;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

public final class DocumentScanActivity extends Activity {
    public static final String EXTRA_OPTIONS_JSON = "mlKitSuite.document.options";
    public static final String EXTRA_IMAGE_WEB_BASE_URL = "mlKitSuite.document.imageWebBaseUrl";
    public static final String EXTRA_DOCUMENT_WEB_BASE_URL = "mlKitSuite.document.documentWebBaseUrl";
    public static final String EXTRA_RESULT_JSON = "mlKitSuite.document.result";
    public static final String EXTRA_ERROR_CODE = "mlKitSuite.document.errorCode";
    public static final String EXTRA_ERROR_MESSAGE = "mlKitSuite.document.errorMessage";
    public static final String EXTRA_ERROR_RECOVERABLE = "mlKitSuite.document.errorRecoverable";

    private static final int SCANNER_REQUEST = 8641;
    private static final long MINIMUM_TOTAL_MEMORY_BYTES = 1_700_000_000L;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !hasRequiredMemory()) {
            finishWithError(
                "UNSUPPORTED_DEVICE",
                "ML Kit Document Scanner requires Android 7.0 or newer and at least 1.7 GB of total RAM.",
                false
            );
            return;
        }
        try {
            JSONObject raw = new JSONObject(getIntent().getStringExtra(EXTRA_OPTIONS_JSON));
            DocumentScanConfig config = DocumentScanConfig.fromJson(raw);
            GmsDocumentScanner scanner = GmsDocumentScanning.getClient(config.toNativeOptions());
            scanner.getStartScanIntent(this)
                .addOnSuccessListener(this::launchScanner)
                .addOnFailureListener(this::handleScannerFailure);
        } catch (IllegalArgumentException exception) {
            finishWithError("INVALID_ARGUMENT", exception.getMessage(), false);
        } catch (Exception exception) {
            finishWithError("SCANNER_UNAVAILABLE", "Unable to initialize Document Scanner.", true);
        }
    }

    private boolean hasRequiredMemory() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return true;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return info.totalMem >= MINIMUM_TOTAL_MEMORY_BYTES;
    }

    private void launchScanner(IntentSender sender) {
        try {
            startIntentSenderForResult(sender, SCANNER_REQUEST, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException exception) {
            finishWithError("SCANNER_UNAVAILABLE", "Unable to open Document Scanner.", true);
        }
    }

    private void handleScannerFailure(Exception exception) {
        if (exception instanceof MlKitException
            && ((MlKitException) exception).getErrorCode() == MlKitException.UNSUPPORTED) {
            finishWithError(
                "UNSUPPORTED_DEVICE",
                "ML Kit Document Scanner is not supported on this device.",
                false
            );
            return;
        }
        String message = exception.getLocalizedMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "ML Kit Document Scanner is unavailable.";
        }
        finishWithError("SCANNER_UNAVAILABLE", message, true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SCANNER_REQUEST) return;
        if (resultCode != RESULT_OK) {
            finishWithError("USER_CANCELLED", "Document scanning was cancelled by the user.", true);
            return;
        }
        try {
            GmsDocumentScanningResult result = GmsDocumentScanningResult.fromActivityResultIntent(data);
            if (result == null) throw new IllegalStateException("Document Scanner returned no result.");
            finishWithSuccess(copyResult(result));
        } catch (Exception exception) {
            finishWithError("PROCESSING_FAILED", "Unable to persist the scanned document.", true);
        }
    }

    private JSONObject copyResult(GmsDocumentScanningResult result) throws Exception {
        File cache = new File(getCacheDir(), "mlkit-suite");
        if (!cache.exists() && !cache.mkdirs()) {
            throw new IllegalStateException("Unable to create the ML Kit cache directory.");
        }
        String scanId = UUID.randomUUID().toString().toLowerCase();
        String imageBaseUrl = getIntent().getStringExtra(EXTRA_IMAGE_WEB_BASE_URL);
        String documentBaseUrl = getIntent().getStringExtra(EXTRA_DOCUMENT_WEB_BASE_URL);
        JSONArray pagesJson = new JSONArray();
        List<GmsDocumentScanningResult.Page> pages = result.getPages();
        if (pages != null) {
            for (int index = 0; index < pages.size(); index++) {
                Uri source = pages.get(index).getImageUri();
                File destination = new File(cache,
                    "document-" + scanId + "-page-" + (index + 1) + ".jpg");
                copyUri(source, destination);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(destination.getAbsolutePath(), bounds);
                JSONObject page = new JSONObject();
                page.put("imagePath", destination.getAbsolutePath());
                page.put("imageUri", imageBaseUrl + destination.getName());
                page.put("mimeType", "image/jpeg");
                page.put("bytes", destination.length());
                page.put("width", Math.max(bounds.outWidth, 0));
                page.put("height", Math.max(bounds.outHeight, 0));
                pagesJson.put(page);
            }
        }

        JSONObject payload = new JSONObject();
        payload.put("status", "success");
        payload.put("pages", pagesJson);
        GmsDocumentScanningResult.Pdf pdf = result.getPdf();
        if (pdf == null) {
            payload.put("pdf", JSONObject.NULL);
        } else {
            File destination = new File(cache, "document-" + scanId + ".pdf");
            copyUri(pdf.getUri(), destination);
            JSONObject pdfJson = new JSONObject();
            pdfJson.put("path", destination.getAbsolutePath());
            pdfJson.put("uri", documentBaseUrl + destination.getName());
            pdfJson.put("mimeType", "application/pdf");
            pdfJson.put("bytes", destination.length());
            pdfJson.put("pageCount", pdf.getPageCount());
            payload.put("pdf", pdfJson);
        }
        return payload;
    }

    private void copyUri(Uri source, File destination) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IllegalStateException("Unable to open scanner output.");
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private void finishWithSuccess(JSONObject result) {
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT_JSON, result.toString());
        setResult(RESULT_OK, data);
        finish();
    }

    private void finishWithError(String code, String message, boolean recoverable) {
        Intent data = new Intent();
        data.putExtra(EXTRA_ERROR_CODE, code);
        data.putExtra(EXTRA_ERROR_MESSAGE, message);
        data.putExtra(EXTRA_ERROR_RECOVERABLE, recoverable);
        setResult(RESULT_CANCELED, data);
        finish();
    }
}
