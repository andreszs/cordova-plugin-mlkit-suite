package com.andreszs.plugins.mlkitsuite;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    private static final int CAMERA_PERMISSION_REQUEST = 8611;
    private static final int FACE_CAPTURE_REQUEST = 8612;
    private static final int BARCODE_SCAN_REQUEST = 8613;

    private CallbackContext pendingCallback;
    private JSONObject pendingOptions;
    private String pendingAction;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        if ("getCapabilities".equals(action)) {
            callbackContext.success(capabilities());
            return true;
        }
        if (!("faceCapture".equals(action) || "barcodeScan".equals(action))) {
            return false;
        }
        if (pendingCallback != null) {
            callbackContext.error(error("BUSY", "Another ML Kit camera session is already active.", true));
            return true;
        }

        JSONObject options = args.optJSONObject(0);
        if (options == null) options = new JSONObject();
        try {
            if ("faceCapture".equals(action)) {
                FaceCaptureConfig.fromJson(options);
            } else {
                BarcodeScanConfig.fromJson(options);
            }
        } catch (IllegalArgumentException exception) {
            callbackContext.error(error("INVALID_ARGUMENT", exception.getMessage(), false));
            return true;
        }

        pendingCallback = callbackContext;
        pendingOptions = options;
        pendingAction = action;
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
            if (path == null || !path.startsWith(IMAGE_WEB_PATH)) return null;
            String fileName = path.substring(IMAGE_WEB_PATH.length());
            if (!fileName.matches("face-[0-9a-fA-F-]+\\.jpg")) return emptyImageResponse();
            try {
                File cache = new File(cordova.getActivity().getCacheDir(), "mlkit-suite").getCanonicalFile();
                File image = new File(cache, fileName).getCanonicalFile();
                if (!cache.equals(image.getParentFile()) || !image.isFile()) return emptyImageResponse();
                return new WebResourceResponse("image/jpeg", null, new FileInputStream(image));
            } catch (Exception ignored) {
                return emptyImageResponse();
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
        if ((requestCode != FACE_CAPTURE_REQUEST && requestCode != BARCODE_SCAN_REQUEST)
            || pendingCallback == null) return;
        if (resultCode == Activity.RESULT_OK && intent != null) {
            try {
                String resultJson = requestCode == FACE_CAPTURE_REQUEST
                    ? intent.getStringExtra(FaceCaptureActivity.EXTRA_RESULT_JSON)
                    : intent.getStringExtra(BarcodeScanActivity.EXTRA_RESULT_JSON);
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
            : "Barcode scanning was cancelled by the user.";
        boolean recoverable = true;
        if (intent != null) {
            String errorCode = requestCode == FACE_CAPTURE_REQUEST
                ? intent.getStringExtra(FaceCaptureActivity.EXTRA_ERROR_CODE)
                : intent.getStringExtra(BarcodeScanActivity.EXTRA_ERROR_CODE);
            String errorMessage = requestCode == FACE_CAPTURE_REQUEST
                ? intent.getStringExtra(FaceCaptureActivity.EXTRA_ERROR_MESSAGE)
                : intent.getStringExtra(BarcodeScanActivity.EXTRA_ERROR_MESSAGE);
            if (errorCode != null) {
                code = errorCode;
            }
            if (errorMessage != null) {
                message = errorMessage;
            }
            recoverable = requestCode == FACE_CAPTURE_REQUEST
                ? intent.getBooleanExtra(FaceCaptureActivity.EXTRA_ERROR_RECOVERABLE, true)
                : intent.getBooleanExtra(BarcodeScanActivity.EXTRA_ERROR_RECOVERABLE, true);
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
        }
    }

    private JSONObject capabilities() {
        JSONObject root = new JSONObject();
        JSONObject face = new JSONObject();
        JSONObject barcode = new JSONObject();
        try {
            root.put("platform", "android");
            root.put("offline", true);
            root.put("face", face);
            face.put("capture", true);
            face.put("frontCamera", true);
            face.put("autoCapture", true);
            root.put("barcode", barcode);
            barcode.put("scan", true);
            barcode.put("bundled", true);
            barcode.put("resolution", "1920x1080");
            barcode.put("confirmationFrames", 2);
            barcode.put("formats", BarcodeScanConfig.supportedFormatsJson());
        } catch (Exception ignored) { }
        return root;
    }

    private String imageWebBaseUrl() {
        String scheme = preferences.getString("scheme", "https").toLowerCase(Locale.US);
        String hostname = preferences.getString("hostname", "localhost").toLowerCase(Locale.US);
        return scheme + "://" + hostname + "/" + IMAGE_WEB_PATH;
    }

    private WebResourceResponse emptyImageResponse() {
        return new WebResourceResponse("image/jpeg", null, new ByteArrayInputStream(new byte[0]));
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

