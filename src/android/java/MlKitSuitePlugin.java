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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class MlKitSuitePlugin extends CordovaPlugin {
    private static final String IMAGE_WEB_PATH = "_mlkit_suite_image_/";
    private static final String DOCUMENT_WEB_PATH = "_mlkit_suite_document_/";
    private static final int CAMERA_PERMISSION_REQUEST = 8611;
    private static final int FACE_CAPTURE_REQUEST = 8612;
    private static final int BARCODE_SCAN_REQUEST = 8613;
    private static final int DOCUMENT_SCAN_REQUEST = 8614;
    private static final long DOCUMENT_SCANNER_MINIMUM_MEMORY_BYTES = 1_700_000_000L;
    private static final String DEFAULT_MODULE_LOADING_MESSAGE =
        "Loading Google Play services module...";
    private static final String FACE_MODE_METADATA =
        "com.andreszs.plugins.mlkitsuite.FACE_MODE";
    private static final String BARCODE_MODE_METADATA =
        "com.andreszs.plugins.mlkitsuite.BARCODE_MODE";
    private static final String OCR_MODE_METADATA =
        "com.andreszs.plugins.mlkitsuite.OCR_MODE";

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
        if ("prepareModels".equals(action)) {
            prepareModels(args, callbackContext);
            return true;
        }
        if ("prepareDocumentScanner".equals(action)) {
            prepareDocumentScanner(args, callbackContext);
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
        final String loadingMessage;
        try {
            if ("faceCapture".equals(action)) {
                FaceCaptureConfig.fromJson(options);
            } else if ("barcodeScan".equals(action)) {
                BarcodeScanConfig.fromJson(options);
            } else {
                DocumentScanConfig.fromJson(options);
            }
            loadingMessage = moduleLoadingMessage(options);
        } catch (IllegalArgumentException exception) {
            callbackContext.error(error("INVALID_ARGUMENT", exception.getMessage(), false));
            return true;
        }

        pendingCallback = callbackContext;
        pendingOptions = options;
        pendingAction = action;
        if ("documentScan".equals(action)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !documentScannerDeviceSupported()) {
                finishWithError(
                    "UNSUPPORTED_DEVICE",
                    "ML Kit Document Scanner requires Android 7.0 or newer and at least 1.7 GB of total RAM.",
                    false
                );
                return true;
            }
            preparePendingDocumentScanner(loadingMessage, callbackContext);
            return true;
        }
        if (cordova.hasPermission(Manifest.permission.CAMERA)) {
            preparePendingCameraModule(loadingMessage, callbackContext);
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
            try {
                preparePendingCameraModule(
                    moduleLoadingMessage(pendingOptions), pendingCallback
                );
            } catch (IllegalArgumentException exception) {
                finishWithError("INVALID_ARGUMENT", exception.getMessage(), false);
            }
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
        String faceMode = androidModuleMode(FACE_MODE_METADATA);
        String barcodeMode = androidModuleMode(BARCODE_MODE_METADATA);
        String ocrMode = androidModuleMode(OCR_MODE_METADATA);
        String modelDelivery = aggregateDelivery(faceMode, barcodeMode, ocrMode);
        String modelDownloadStrategy = aggregateMode(faceMode, barcodeMode, ocrMode);
        try {
            root.put("platform", "android");
            root.put("offline", false);
            root.put("modelDelivery", modelDelivery);
            root.put("modelDownloadStrategy", modelDownloadStrategy);
            root.put("face", face);
            face.put("capture", true);
            face.put("frontCamera", true);
            face.put("autoCapture", true);
            face.put("mode", faceMode);
            face.put("bundled", "bundled".equals(faceMode));
            root.put("barcode", barcode);
            barcode.put("scan", true);
            barcode.put("scanImage", true);
            barcode.put("mode", barcodeMode);
            barcode.put("bundled", "bundled".equals(barcodeMode));
            barcode.put("resolution", "1920x1080");
            barcode.put("confirmationFrames", 2);
            barcode.put("formats", BarcodeScanConfig.supportedFormatsJson());
            root.put("document", document);
            document.put("scan", true);
            document.put("prepare", true);
            document.put(
                "deviceSupported",
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && documentScannerDeviceSupported()
            );
            document.put("minimumAndroidApi", 24);
            document.put("dynamicallyDelivered", true);
            document.put("minimumTotalRamMb", 1700);
            document.put("resultFormats", new JSONArray().put("JPEG").put("PDF"));
            root.put("text", text);
            text.put("recognize", true);
            text.put("mode", ocrMode);
            text.put("bundled", "bundled".equals(ocrMode));
            text.put("scripts", new JSONArray().put("LATIN"));
        } catch (Exception ignored) { }
        return root;
    }

    private String androidModuleMode(String metadataName) {
        try {
            ApplicationInfo applicationInfo = cordova.getActivity().getPackageManager()
                .getApplicationInfo(cordova.getActivity().getPackageName(), PackageManager.GET_META_DATA);
            if (applicationInfo.metaData != null) {
                String value = applicationInfo.metaData.getString(metadataName, "install-time");
                if ("bundled".equals(value) || "install-time".equals(value)
                    || "first-use".equals(value)) {
                    return value;
                }
            }
        } catch (Exception ignored) { }
        return "install-time";
    }

    private static String aggregateDelivery(String faceMode, String barcodeMode, String ocrMode) {
        boolean faceBundled = "bundled".equals(faceMode);
        boolean barcodeBundled = "bundled".equals(barcodeMode);
        boolean ocrBundled = "bundled".equals(ocrMode);
        if (faceBundled && barcodeBundled && ocrBundled) return "bundled";
        if (!faceBundled && !barcodeBundled && !ocrBundled) return "play-services";
        return "mixed";
    }

    private static String aggregateMode(String faceMode, String barcodeMode, String ocrMode) {
        return faceMode.equals(barcodeMode) && faceMode.equals(ocrMode) ? faceMode : "mixed";
    }

    private void processText(JSONArray args, CallbackContext callbackContext) {
        String imagePath = args.optString(0, "").trim();
        if (imagePath.isEmpty()) {
            callbackContext.error(error("INVALID_ARGUMENT", "imagePath must be a non-empty string.", false));
            return;
        }
        try {
            JSONObject options = optionsAt(args, 1, "options");
            String loadingMessage = moduleLoadingMessage(options);
            ensureModelsAvailable(false, false, true, loadingMessage, callbackContext, () -> {
                File cache = new File(cordova.getActivity().getCacheDir(), "mlkit-suite");
                cordova.getThreadPool().execute(() -> StaticImageProcessor.recognizeText(
                    cordova.getActivity().getApplicationContext(), imagePath, cache, callbackContext
                ));
            });
        } catch (IllegalArgumentException exception) {
            callbackContext.error(error("INVALID_ARGUMENT", exception.getMessage(), false));
        }
    }

    private void prepareDocumentScanner(JSONArray args, CallbackContext callbackContext) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !documentScannerDeviceSupported()) {
            callbackContext.error(error(
                "UNSUPPORTED_DEVICE",
                "ML Kit Document Scanner requires Android 7.0 or newer and at least 1.7 GB of total RAM.",
                false
            ));
            return;
        }
        try {
            String loadingMessage = moduleLoadingMessage(optionsAt(args, 0, "options"));
            cordova.getActivity().runOnUiThread(() -> DocumentScannerModuleInstaller.prepare(
                cordova.getActivity(), loadingMessage, new DocumentScannerModuleInstaller.Callback() {
                    @Override
                    public void onReady(JSONObject result) {
                        callbackContext.success(result);
                    }

                    @Override
                    public void onError(JSONObject errorValue) {
                        callbackContext.error(errorValue);
                    }
                }
            ));
        } catch (IllegalArgumentException exception) {
            callbackContext.error(error("INVALID_ARGUMENT", exception.getMessage(), false));
        }
    }

    private void prepareModels(JSONArray args, CallbackContext callbackContext) {
        final Set<String> requestedModules;
        final String loadingMessage;
        try {
            requestedModules = requestedModules(args);
            loadingMessage = moduleLoadingMessage(optionsAt(args, 1, "options"));
        } catch (IllegalArgumentException exception) {
            callbackContext.error(error("INVALID_ARGUMENT", exception.getMessage(), false));
            return;
        }

        boolean prepareFace = requestedModules.contains("face")
            && !"bundled".equals(androidModuleMode(FACE_MODE_METADATA));
        boolean prepareBarcode = requestedModules.contains("barcode")
            && !"bundled".equals(androidModuleMode(BARCODE_MODE_METADATA));
        boolean prepareOcr = requestedModules.contains("ocr")
            && !"bundled".equals(androidModuleMode(OCR_MODE_METADATA));
        if (!prepareFace && !prepareBarcode && !prepareOcr) {
            JSONObject result = new JSONObject();
            try {
                result.put("status", "ready");
                result.put("alreadyAvailable", true);
            } catch (Exception ignored) { }
            callbackContext.success(result);
            return;
        }
        cordova.getActivity().runOnUiThread(() -> MlKitModelsModuleInstaller.prepare(
            cordova.getActivity(), prepareFace, prepareBarcode, prepareOcr, loadingMessage,
            new MlKitModelsModuleInstaller.Callback() {
                @Override
                public void onReady(JSONObject result) {
                    callbackContext.success(result);
                }

                @Override
                public void onError(JSONObject errorValue) {
                    callbackContext.error(errorValue);
                }
            }
        ));
    }

    private static Set<String> requestedModules(JSONArray args) {
        Set<String> result = new LinkedHashSet<>();
        Object requested = args.opt(0);
        if (requested == null || requested == JSONObject.NULL) {
            result.add("face");
            result.add("barcode");
            result.add("ocr");
            return result;
        }
        if (requested instanceof String) {
            addRequestedModule(result, (String) requested);
            return result;
        }
        if (requested instanceof JSONArray) {
            JSONArray array = (JSONArray) requested;
            if (array.length() == 0) {
                throw new IllegalArgumentException("modules must not be an empty array.");
            }
            for (int index = 0; index < array.length(); index++) {
                Object value = array.opt(index);
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException(
                        "modules may contain only face, barcode, or ocr."
                    );
                }
                addRequestedModule(result, (String) value);
            }
            return result;
        }
        throw new IllegalArgumentException(
            "modules must be face, barcode, ocr, or an array containing those names."
        );
    }

    private static void addRequestedModule(Set<String> modules, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim().toLowerCase(Locale.US);
        if (!("face".equals(value) || "barcode".equals(value) || "ocr".equals(value))) {
            throw new IllegalArgumentException(
                "Unknown module '" + rawValue + "'. Use face, barcode, or ocr."
            );
        }
        modules.add(value);
    }

    private void processBarcodes(JSONArray args, CallbackContext callbackContext) {
        String imagePath = args.optString(0, "").trim();
        if (imagePath.isEmpty()) {
            callbackContext.error(error("INVALID_ARGUMENT", "imagePath must be a non-empty string.", false));
            return;
        }
        try {
            JSONObject options = optionsAt(args, 1, "options");
            int[] formats = BarcodeScanConfig.imageFormatValuesFromJson(options);
            String loadingMessage = moduleLoadingMessage(options);
            ensureModelsAvailable(false, true, false, loadingMessage, callbackContext, () -> {
                File cache = new File(cordova.getActivity().getCacheDir(), "mlkit-suite");
                cordova.getThreadPool().execute(() -> StaticImageProcessor.scanBarcodes(
                    cordova.getActivity().getApplicationContext(), imagePath, cache, formats,
                    callbackContext
                ));
            });
        } catch (IllegalArgumentException exception) {
            callbackContext.error(error("INVALID_ARGUMENT", exception.getMessage(), false));
        }
    }

    private void preparePendingCameraModule(String loadingMessage, CallbackContext owner) {
        boolean face = "faceCapture".equals(pendingAction);
        ensureModelsAvailable(face, !face, false, loadingMessage, owner, () -> {
            if (pendingCallback != owner) return;
            launchPendingActivity();
        }, () -> {
            if (pendingCallback == owner) clearPending();
        });
    }

    private void preparePendingDocumentScanner(String loadingMessage, CallbackContext owner) {
        cordova.getActivity().runOnUiThread(() -> DocumentScannerModuleInstaller.prepare(
            cordova.getActivity(), loadingMessage, new DocumentScannerModuleInstaller.Callback() {
                @Override
                public void onReady(JSONObject result) {
                    if (pendingCallback == owner) launchPendingActivity();
                }

                @Override
                public void onError(JSONObject errorValue) {
                    if (pendingCallback != owner) return;
                    owner.error(errorValue);
                    clearPending();
                }
            }
        ));
    }

    private void ensureModelsAvailable(boolean face, boolean barcode, boolean ocr,
                                       String loadingMessage, CallbackContext callbackContext,
                                       Runnable onReady) {
        ensureModelsAvailable(
            face, barcode, ocr, loadingMessage, callbackContext, onReady, null
        );
    }

    private void ensureModelsAvailable(boolean face, boolean barcode, boolean ocr,
                                       String loadingMessage, CallbackContext callbackContext,
                                       Runnable onReady, Runnable onErrorCleanup) {
        boolean prepareFace = face && !"bundled".equals(androidModuleMode(FACE_MODE_METADATA));
        boolean prepareBarcode = barcode
            && !"bundled".equals(androidModuleMode(BARCODE_MODE_METADATA));
        boolean prepareOcr = ocr && !"bundled".equals(androidModuleMode(OCR_MODE_METADATA));
        if (!prepareFace && !prepareBarcode && !prepareOcr) {
            onReady.run();
            return;
        }
        cordova.getActivity().runOnUiThread(() -> MlKitModelsModuleInstaller.prepare(
            cordova.getActivity(), prepareFace, prepareBarcode, prepareOcr, loadingMessage,
            new MlKitModelsModuleInstaller.Callback() {
                @Override
                public void onReady(JSONObject result) {
                    onReady.run();
                }

                @Override
                public void onError(JSONObject errorValue) {
                    callbackContext.error(errorValue);
                    if (onErrorCleanup != null) onErrorCleanup.run();
                }
            }
        ));
    }

    private static JSONObject optionsAt(JSONArray args, int index, String name) {
        Object raw = args.opt(index);
        if (raw == null || raw == JSONObject.NULL) return new JSONObject();
        if (!(raw instanceof JSONObject)) {
            throw new IllegalArgumentException(name + " must be an object.");
        }
        return (JSONObject) raw;
    }

    private static String moduleLoadingMessage(JSONObject options) {
        if (options == null || !options.has("moduleLoadingMessage")
            || options.isNull("moduleLoadingMessage")) {
            return DEFAULT_MODULE_LOADING_MESSAGE;
        }
        Object value = options.opt("moduleLoadingMessage");
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("moduleLoadingMessage must be a string or null.");
        }
        return (String) value;
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

