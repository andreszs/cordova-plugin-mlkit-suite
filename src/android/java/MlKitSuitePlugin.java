package com.andreszs.plugins.mlkitsuite;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.WebResourceResponse;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaPluginPathHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;

public final class MlKitSuitePlugin extends CordovaPlugin {
    private static final String IMAGE_WEB_PATH = "_mlkit_suite_image_/";
    private static final String DOCUMENT_WEB_PATH = "_mlkit_suite_document_/";
    private static final int CAMERA_PERMISSION_REQUEST = 8611;
    private static final int FACE_CAPTURE_REQUEST = 8612;
    private static final int BARCODE_SCAN_REQUEST = 8613;
    private static final int DOCUMENT_SCAN_REQUEST = 8614;
    private static final long DOCUMENT_SCANNER_MINIMUM_MEMORY_BYTES = 1_700_000_000L;
    private static final String MODEL_DELIVERY_METADATA =
        "com.andreszs.plugins.mlkitsuite.MODEL_DELIVERY";

    private CallbackContext pendingCallback;
    private JSONObject pendingOptions;
    private String pendingAction;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        if ("getCapabilities".equals(action)) {
            callbackContext.success(capabilities());
            return true;
        }
        if ("recognizeText".equals(action)) {
            processText(args, callbackContext);
            return true;
        }
        if ("scanBarcodes".equals(action)) {
            processBarcodes(args, callbackContext);
            return true;
        }
        if ("prepareDocumentScanner".equals(action)) {
            prepareDocumentScanner(callbackContext);
            return true;
        }
        if (!("faceCapture".equals(action) || "barcodeScan".equals(action)
            || "documentScan".equals(action))) {
            return false;
        }
        if (pendingCallback != null) {
            callbackContext.error(error("BUSY", "Another interactive ML Kit session is already active.", true));
            return true;
        }

        JSONObject options = args.optJSONObject(0);
        if (options == null) options = new JSONObject();
        try {
            if ("faceCapture".equals(action)) {
                FaceCaptureConfig.fromJson(options);
            } else if ("barcodeScan".equals(action)) {
                BarcodeScanConfig.fromJson(options);
            } else {
                DocumentScanConfig.fromJson(options);
            }
        } catch (IllegalArgumentException exception) {
            callbackContext.error(error("INVALID_ARGUMENT", exception.getMessage(), false));
            return true;
        }

        pendingCallback = callbackContext;
        pendingOptions = options;
        pendingAction = action;
        if ("documentScan".equals(action)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !documentScannerDeviceSupported()) {
                finishWithError(
                    "UNSUPPORTED_DEVICE",
                    "ML Kit Document Scanner requires Android 6.0 or newer and at least 1.7 GB of total RAM.",
                    false
                );
                return true;
            }
            launchPendingActivity();
            return true;
        }
        if (cordova.hasPermission(Manifest.permission.CAMERA)) {
            launchPendingActivity();
        } else {
            cordova.requestPermission(this, CAMERA_PERMISSION_REQUEST, Manifest.permission.CAMERA);
        }
        return true;
    }

    @Override
    public CordovaPluginPathHandler getPathHandler() {
        return new CordovaPluginPathHandler(path -> {
            if (path == null) return null;
            boolean imageRequest = path.startsWith(IMAGE_WEB_PATH);
            boolean documentRequest = path.startsWith(DOCUMENT_WEB_PATH);
            if (!imageRequest && !documentRequest) return null;
            String fileName = path.substring(
                imageRequest ? IMAGE_WEB_PATH.length() : DOCUMENT_WEB_PATH.length()
            );
            boolean validImage = fileName.matches("face-[0-9a-fA-F-]+\\.jpg")
                || fileName.matches("document-[0-9a-fA-F-]+-page-[0-9]+\\.jpg");
            boolean validDocument = fileName.matches("document-[0-9a-fA-F-]+\\.pdf");
            String mimeType = imageRequest ? "image/jpeg" : "application/pdf";
            if ((imageRequest && !validImage) || (documentRequest && !validDocument)) {
                return emptyResponse(mimeType);
            }
            try {
                File cache = new File(cordova.getActivity().getCacheDir(), "mlkit-suite").getCanonicalFile();
                File file = new File(cache, fileName).getCanonicalFile();
                if (!cache.equals(file.getParentFile()) || !file.isFile()) return emptyResponse(mimeType);
                return new WebResourceResponse(mimeType, null, new FileInputStream(file));
            } catch (Exception ignored) {
                return emptyResponse(mimeType);
            }
        });
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != CAMERA_PERMISSION_REQUEST || pendingCallback == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            launchPendingActivity();
        } else {
            finishWithError("CAMERA_PERMISSION_DENIED", "Camera permission was denied.", true);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if ((requestCode != FACE_CAPTURE_REQUEST && requestCode != BARCODE_SCAN_REQUEST
            && requestCode != DOCUMENT_SCAN_REQUEST)
            || pendingCallback == null) return;
        if (resultCode == Activity.RESULT_OK && intent != null) {
            try {
                String resultJson;
                if (requestCode == FACE_CAPTURE_REQUEST) {
                    resultJson = intent.getStringExtra(FaceCaptureActivity.EXTRA_RESULT_JSON);
                } else if (requestCode == BARCODE_SCAN_REQUEST) {
                    resultJson = intent.getStringExtra(BarcodeScanActivity.EXTRA_RESULT_JSON);
                } else {
                    resultJson = intent.getStringExtra(DocumentScanActivity.EXTRA_RESULT_JSON);
                }
                pendingCallback.success(new JSONObject(resultJson));
            } catch (Exception exception) {
                pendingCallback.error(error("PROCESSING_FAILED", "The native result was invalid.", false));
            }
            clearPending();
            return;
        }

        String code = "USER_CANCELLED";
        String message = requestCode == FACE_CAPTURE_REQUEST
            ? "Face capture was cancelled by the user."
            : requestCode == BARCODE_SCAN_REQUEST
                ? "Barcode scanning was cancelled by the user."
                : "Document scanning was cancelled by the user.";
        boolean recoverable = true;
        if (intent != null) {
            String errorCode;
            String errorMessage;
            boolean nativeRecoverable;
            if (requestCode == FACE_CAPTURE_REQUEST) {
                errorCode = intent.getStringExtra(FaceCaptureActivity.EXTRA_ERROR_CODE);
                errorMessage = intent.getStringExtra(FaceCaptureActivity.EXTRA_ERROR_MESSAGE);
                nativeRecoverable = intent.getBooleanExtra(
                    FaceCaptureActivity.EXTRA_ERROR_RECOVERABLE, true
                );
            } else if (requestCode == BARCODE_SCAN_REQUEST) {
                errorCode = intent.getStringExtra(BarcodeScanActivity.EXTRA_ERROR_CODE);
                errorMessage = intent.getStringExtra(BarcodeScanActivity.EXTRA_ERROR_MESSAGE);
                nativeRecoverable = intent.getBooleanExtra(
                    BarcodeScanActivity.EXTRA_ERROR_RECOVERABLE, true
                );
            } else {
                errorCode = intent.getStringExtra(DocumentScanActivity.EXTRA_ERROR_CODE);
                errorMessage = intent.getStringExtra(DocumentScanActivity.EXTRA_ERROR_MESSAGE);
                nativeRecoverable = intent.getBooleanExtra(
                    DocumentScanActivity.EXTRA_ERROR_RECOVERABLE, true
                );
            }
            if (errorCode != null) {
                code = errorCode;
            }
            if (errorMessage != null) {
                message = errorMessage;
            }
            recoverable = nativeRecoverable;
        }
        pendingCallback.error(error(code, message, recoverable));
        clearPending();
    }

    @Override
    public void onReset() {
        clearPending();
    }

    private void launchPendingActivity() {
        if ("faceCapture".equals(pendingAction)) {
            Intent intent = new Intent(cordova.getActivity(), FaceCaptureActivity.class);
            intent.putExtra(FaceCaptureActivity.EXTRA_OPTIONS_JSON, pendingOptions.toString());
            intent.putExtra(FaceCaptureActivity.EXTRA_IMAGE_WEB_BASE_URL, imageWebBaseUrl());
            cordova.startActivityForResult(this, intent, FACE_CAPTURE_REQUEST);
            return;
        }
        if ("barcodeScan".equals(pendingAction)) {
            Intent intent = new Intent(cordova.getActivity(), BarcodeScanActivity.class);
            intent.putExtra(BarcodeScanActivity.EXTRA_OPTIONS_JSON, pendingOptions.toString());
            cordova.startActivityForResult(this, intent, BARCODE_SCAN_REQUEST);
            return;
        }
        if ("documentScan".equals(pendingAction)) {
            Intent intent = new Intent(cordova.getActivity(), DocumentScanActivity.class);
            intent.putExtra(DocumentScanActivity.EXTRA_OPTIONS_JSON, pendingOptions.toString());
            intent.putExtra(DocumentScanActivity.EXTRA_IMAGE_WEB_BASE_URL, imageWebBaseUrl());
            intent.putExtra(DocumentScanActivity.EXTRA_DOCUMENT_WEB_BASE_URL, documentWebBaseUrl());
            cordova.startActivityForResult(this, intent, DOCUMENT_SCAN_REQUEST);
        }
    }

    private JSONObject capabilities() {
        JSONObject root = new JSONObject();
        JSONObject face = new JSONObject();
        JSONObject barcode = new JSONObject();
        JSONObject document = new JSONObject();
        JSONObject text = new JSONObject();
        String modelDelivery = androidModelDelivery();
        boolean bundledModels = "bundled".equals(modelDelivery);
        try {
            root.put("platform", "android");
            root.put("offline", false);
            root.put("modelDelivery", modelDelivery);
            root.put("face", face);
            face.put("capture", true);
            face.put("frontCamera", true);
            face.put("autoCapture", true);
            face.put("bundled", bundledModels);
            root.put("barcode", barcode);
            barcode.put("scan", true);
            barcode.put("scanImage", true);
            barcode.put("bundled", bundledModels);
            barcode.put("resolution", "1920x1080");
            barcode.put("confirmationFrames", 2);
            barcode.put("formats", BarcodeScanConfig.supportedFormatsJson());
            root.put("document", document);
            document.put("scan", true);
            document.put("prepare", true);
            document.put(
                "deviceSupported",
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && documentScannerDeviceSupported()
            );
            document.put("minimumAndroidApi", 23);
            document.put("dynamicallyDelivered", true);
            document.put("minimumTotalRamMb", 1700);
            document.put("resultFormats", new JSONArray().put("JPEG").put("PDF"));
            root.put("text", text);
            text.put("recognize", true);
            text.put("bundled", bundledModels);
            text.put("scripts", new JSONArray().put("LATIN"));
        } catch (Exception ignored) { }
        return root;
    }

    private String androidModelDelivery() {
        try {
            ApplicationInfo applicationInfo = cordova.getActivity().getPackageManager()
                .getApplicationInfo(cordova.getActivity().getPackageName(), PackageManager.GET_META_DATA);
            if (applicationInfo.metaData != null) {
                String value = applicationInfo.metaData.getString(MODEL_DELIVERY_METADATA, "bundled");
                if ("play-services".equals(value)) return value;
            }
        } catch (Exception ignored) { }
        return "bundled";
    }

    private void processText(JSONArray args, CallbackContext callbackContext) {
        String imagePath = args.optString(0, "").trim();
        if (imagePath.isEmpty()) {
            callbackContext.error(error("INVALID_ARGUMENT", "imagePath must be a non-empty string.", false));
            return;
        }
        File cache = new File(cordova.getActivity().getCacheDir(), "mlkit-suite");
        cordova.getThreadPool().execute(() -> StaticImageProcessor.recognizeText(
            cordova.getActivity().getApplicationContext(), imagePath, cache, callbackContext
        ));
    }

    private void prepareDocumentScanner(CallbackContext callbackContext) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !documentScannerDeviceSupported()) {
            callbackContext.error(error(
                "UNSUPPORTED_DEVICE",
                "ML Kit Document Scanner requires Android 6.0 or newer and at least 1.7 GB of total RAM.",
                false
            ));
            return;
        }
        cordova.getActivity().runOnUiThread(() -> DocumentScannerModuleInstaller.prepare(
            cordova.getActivity(), callbackContext
        ));
    }

    private void processBarcodes(JSONArray args, CallbackContext callbackContext) {
        String imagePath = args.optString(0, "").trim();
        if (imagePath.isEmpty()) {
            callbackContext.error(error("INVALID_ARGUMENT", "imagePath must be a non-empty string.", false));
            return;
        }
        try {
            int[] formats = BarcodeScanConfig.imageFormatValuesFromJson(args.optJSONObject(1));
            File cache = new File(cordova.getActivity().getCacheDir(), "mlkit-suite");
            cordova.getThreadPool().execute(() -> StaticImageProcessor.scanBarcodes(
                cordova.getActivity().getApplicationContext(), imagePath, cache, formats, callbackContext
            ));
        } catch (IllegalArgumentException exception) {
            callbackContext.error(error("INVALID_ARGUMENT", exception.getMessage(), false));
        }
    }

    private boolean documentScannerDeviceSupported() {
        ActivityManager manager = (ActivityManager) cordova.getActivity()
            .getSystemService(Activity.ACTIVITY_SERVICE);
        if (manager == null) return true;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return info.totalMem >= DOCUMENT_SCANNER_MINIMUM_MEMORY_BYTES;
    }

    private String imageWebBaseUrl() {
        String scheme = preferences.getString("scheme", "https").toLowerCase(Locale.US);
        String hostname = preferences.getString("hostname", "localhost").toLowerCase(Locale.US);
        return scheme + "://" + hostname + "/" + IMAGE_WEB_PATH;
    }

    private String documentWebBaseUrl() {
        String scheme = preferences.getString("scheme", "https").toLowerCase(Locale.US);
        String hostname = preferences.getString("hostname", "localhost").toLowerCase(Locale.US);
        return scheme + "://" + hostname + "/" + DOCUMENT_WEB_PATH;
    }

    private WebResourceResponse emptyResponse(String mimeType) {
        return new WebResourceResponse(mimeType, null, new ByteArrayInputStream(new byte[0]));
    }

    private void finishWithError(String code, String message, boolean recoverable) {
        if (pendingCallback != null) pendingCallback.error(error(code, message, recoverable));
        clearPending();
    }

    private void clearPending() {
        pendingCallback = null;
        pendingOptions = null;
        pendingAction = null;
    }

    static JSONObject error(String code, String message, boolean recoverable) {
        JSONObject value = new JSONObject();
        try {
            value.put("code", code);
            value.put("message", message);
            value.put("recoverable", recoverable);
        } catch (Exception ignored) { }
        return value;
    }
}

