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
    private static final String FACE_MODE_METADATA =
        "com.andreszs.plugins.mlkitsuite.FACE_MODE";
    private static final String BARCODE_MODE_METADATA =
        "com.andreszs.plugins.mlkitsuite.BARCODE_MODE";
    private static final String OCR_MODE_METADATA =
        "com.andreszs.plugins.mlkitsuite.OCR_MODE";
    private static final String IMAGE_LABELING_MODE_METADATA =
        "com.andreszs.plugins.mlkitsuite.IMAGE_LABELING_MODE";

    private CallbackContext pendingCallback;
    private JSONObject pendingOptions;
    private String pendingAction;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        if ("getCapabilities".equals(action)) {
            getCapabilities(callbackContext);
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
        if ("labelImage".equals(action)) {
            processImageLabels(args, callbackContext);
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
            try {
                MlKitLocalization localization = MlKitLocalization.from(
                    cordova.getActivity(), args.optJSONObject(0));
                callbackContext.error(error("BUSY", localization.text("busy"), true));
            } catch (IllegalArgumentException exception) {
                callbackContext.error(error(
                    "INVALID_ARGUMENT", exception.getMessage(), false));
            }
            return true;
        }

        JSONObject options = args.optJSONObject(0);
        if (options == null) options = new JSONObject();
        final String loadingMessage;
        try {
            if ("faceCapture".equals(action)) {
                FaceCaptureConfig.fromJson(cordova.getActivity(), options);
            } else if ("barcodeScan".equals(action)) {
                BarcodeScanConfig.fromJson(cordova.getActivity(), options);
            } else {
                DocumentScanConfig.fromJson(cordova.getActivity(), options);
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
                    localization(options).text("document_requirements"),
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
            finishWithError("CAMERA_PERMISSION_DENIED",
                localization(pendingOptions).text("camera_permission_denied"), true);
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
        MlKitLocalization localization = localization(pendingOptions);
        String message = requestCode == FACE_CAPTURE_REQUEST
            ? localization.text("face_cancelled")
            : requestCode == BARCODE_SCAN_REQUEST
                ? localization.text("barcode_cancelled")
                : localization.text("document_cancelled");
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
        JSONObject imageLabeling = new JSONObject();
        JSONObject text = new JSONObject();
        JSONObject localization = new JSONObject();
        String faceMode = androidModuleMode(FACE_MODE_METADATA);
        String barcodeMode = androidModuleMode(BARCODE_MODE_METADATA);
        String ocrMode = androidModuleMode(OCR_MODE_METADATA);
        String imageLabelingMode = androidModuleMode(IMAGE_LABELING_MODE_METADATA);
        String modelDelivery = aggregateDelivery(
            faceMode, barcodeMode, ocrMode, imageLabelingMode
        );
        String modelDownloadStrategy = aggregateMode(
            faceMode, barcodeMode, ocrMode, imageLabelingMode
        );
        try {
            root.put("platform", "android");
            root.put("offline", false);
            root.put("modelDelivery", modelDelivery);
            root.put("modelDownloadStrategy", modelDownloadStrategy);
            root.put("localization", localization);
            localization.put("default", "auto");
            localization.put("supportedLocales", new JSONArray().put("en").put("es"));
            Locale deviceLocale = cordova.getActivity().getResources()
                .getConfiguration().getLocales().get(0);
            localization.put("deviceLocale", deviceLocale.toLanguageTag());
            localization.put("resolvedLocale",
                MlKitLocalization.from(cordova.getActivity(), (Object) null).locale());
            root.put("face", face);
            face.put("capture", true);
            face.put("frontCamera", true);
            face.put("autoCapture", true);
            face.put("mode", faceMode);
            face.put("bundled", "bundled".equals(faceMode));
            putInitialModelStatus(face, faceMode);
            root.put("barcode", barcode);
            barcode.put("scan", true);
            barcode.put("scanImage", true);
            barcode.put("mode", barcodeMode);
            barcode.put("bundled", "bundled".equals(barcodeMode));
            putInitialModelStatus(barcode, barcodeMode);
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
            document.put("mode", "play-services");
            document.put("minimumTotalRamMb", 1700);
            document.put("resultFormats", new JSONArray().put("JPEG").put("PDF"));
            if (document.optBoolean("deviceSupported")) {
                putRuntimeModelStatus(document, null);
            } else {
                document.put("ready", false);
                document.put("modelStatus", "unsupported");
            }
            root.put("imageLabeling", imageLabeling);
            imageLabeling.put("label", true);
            imageLabeling.put("mode", imageLabelingMode);
            imageLabeling.put("bundled", "bundled".equals(imageLabelingMode));
            putInitialModelStatus(imageLabeling, imageLabelingMode);
            imageLabeling.put("defaultModel", true);
            imageLabeling.put("labels", "400+");
            root.put("text", text);
            text.put("recognize", true);
            text.put("mode", ocrMode);
            text.put("bundled", "bundled".equals(ocrMode));
            putInitialModelStatus(text, ocrMode);
            text.put("scripts", new JSONArray().put("LATIN"));
        } catch (Exception ignored) { }
        return root;
    }

    private void getCapabilities(CallbackContext callbackContext) {
        JSONObject result = capabilities();
        JSONObject face = result.optJSONObject("face");
        JSONObject barcode = result.optJSONObject("barcode");
        JSONObject imageLabeling = result.optJSONObject("imageLabeling");
        JSONObject text = result.optJSONObject("text");
        boolean checkFace = face != null && !face.optBoolean("bundled");
        boolean checkBarcode = barcode != null && !barcode.optBoolean("bundled");
        boolean checkText = text != null && !text.optBoolean("bundled");
        boolean checkImageLabeling = imageLabeling != null
            && !imageLabeling.optBoolean("bundled");
        Activity activity = cordova.getActivity();

        activity.runOnUiThread(() -> MlKitModelsModuleInstaller.checkAvailability(
            activity, checkFace, checkBarcode, checkText, checkImageLabeling, availability -> {
                mergeRuntimeModelStatus(face, availability.optJSONObject("face"));
                mergeRuntimeModelStatus(barcode, availability.optJSONObject("barcode"));
                mergeRuntimeModelStatus(text, availability.optJSONObject("text"));
                mergeRuntimeModelStatus(
                    imageLabeling, availability.optJSONObject("imageLabeling")
                );
                JSONObject document = result.optJSONObject("document");
                if (document == null || !document.optBoolean("deviceSupported")) {
                    callbackContext.success(result);
                    return;
                }
                DocumentScannerModuleInstaller.checkAvailability(activity, ready -> {
                    putRuntimeModelStatus(document, ready);
                    callbackContext.success(result);
                });
            }
        ));
    }

    private static void putInitialModelStatus(JSONObject module, String mode) {
        if ("bundled".equals(mode)) {
            try {
                module.put("ready", true);
                module.put("modelStatus", "bundled");
            } catch (Exception ignored) { }
        } else {
            putRuntimeModelStatus(module, null);
        }
    }

    private static void mergeRuntimeModelStatus(JSONObject module, JSONObject availability) {
        if (module == null || availability == null) return;
        try {
            module.put("ready", availability.opt("ready"));
            module.put("modelStatus", availability.optString("modelStatus", "unknown"));
        } catch (Exception ignored) { }
    }

    private static void putRuntimeModelStatus(JSONObject module, Boolean ready) {
        if (module == null) return;
        try {
            module.put("ready", ready == null ? JSONObject.NULL : ready);
            module.put("modelStatus", ready == null
                ? "unknown" : ready ? "downloaded" : "not-ready");
        } catch (Exception ignored) { }
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

    private static String aggregateDelivery(String faceMode, String barcodeMode, String ocrMode,
                                            String imageLabelingMode) {
        boolean faceBundled = "bundled".equals(faceMode);
        boolean barcodeBundled = "bundled".equals(barcodeMode);
        boolean ocrBundled = "bundled".equals(ocrMode);
        boolean imageLabelingBundled = "bundled".equals(imageLabelingMode);
        if (faceBundled && barcodeBundled && ocrBundled && imageLabelingBundled) {
            return "bundled";
        }
        if (!faceBundled && !barcodeBundled && !ocrBundled && !imageLabelingBundled) {
            return "play-services";
        }
        return "mixed";
    }

    private static String aggregateMode(String faceMode, String barcodeMode, String ocrMode,
                                        String imageLabelingMode) {
        return faceMode.equals(barcodeMode) && faceMode.equals(ocrMode)
            && faceMode.equals(imageLabelingMode) ? faceMode : "mixed";
    }

    private void processText(JSONArray args, CallbackContext callbackContext) {
        String imagePath = args.optString(0, "").trim();
        try {
            JSONObject options = optionsAt(args, 1, "options");
            MlKitLocalization localization = localization(options);
            if (imagePath.isEmpty()) {
                callbackContext.error(error("INVALID_ARGUMENT",
                    "imagePath must be a non-empty string.", false));
                return;
            }
            String loadingMessage = moduleLoadingMessage(options);
            ensureModelsAvailable(false, false, true, false, loadingMessage, localization,
                callbackContext, () -> {
                File cache = new File(cordova.getActivity().getCacheDir(), "mlkit-suite");
                cordova.getThreadPool().execute(() -> StaticImageProcessor.recognizeText(
                    cordova.getActivity().getApplicationContext(), imagePath, cache,
                    localization, callbackContext
                ));
            });
        } catch (IllegalArgumentException exception) {
            callbackContext.error(error("INVALID_ARGUMENT", exception.getMessage(), false));
        }
    }

    private void prepareDocumentScanner(JSONArray args, CallbackContext callbackContext) {
        try {
            JSONObject options = optionsAt(args, 0, "options");
            MlKitLocalization localization = localization(options);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                || !documentScannerDeviceSupported()) {
                callbackContext.error(error(
                    "UNSUPPORTED_DEVICE",
                    localization.text("document_requirements"),
                    false
                ));
                return;
            }
            String loadingMessage = moduleLoadingMessage(options);
            cordova.getActivity().runOnUiThread(() -> DocumentScannerModuleInstaller.prepare(
                cordova.getActivity(), loadingMessage, localization,
                new DocumentScannerModuleInstaller.Callback() {
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
        final MlKitLocalization localization;
        try {
            requestedModules = requestedModules(args);
            JSONObject options = optionsAt(args, 1, "options");
            localization = localization(options);
            loadingMessage = moduleLoadingMessage(options);
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
        boolean prepareImageLabeling = requestedModules.contains("image-labeling")
            && !"bundled".equals(androidModuleMode(IMAGE_LABELING_MODE_METADATA));
        if (!prepareFace && !prepareBarcode && !prepareOcr && !prepareImageLabeling) {
            JSONObject result = new JSONObject();
            try {
                result.put("status", "ready");
                result.put("alreadyAvailable", true);
            } catch (Exception ignored) { }
            callbackContext.success(result);
            return;
        }
        cordova.getActivity().runOnUiThread(() -> MlKitModelsModuleInstaller.prepare(
            cordova.getActivity(), prepareFace, prepareBarcode, prepareOcr,
            prepareImageLabeling, loadingMessage,
            localization,
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
            result.add("image-labeling");
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
                        "modules may contain only face, barcode, image-labeling, or ocr."
                    );
                }
                addRequestedModule(result, (String) value);
            }
            return result;
        }
        throw new IllegalArgumentException(
            "modules must be face, barcode, image-labeling, ocr, or an array containing those names."
        );
    }

    private static void addRequestedModule(Set<String> modules, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim().toLowerCase(Locale.US);
        if (!("face".equals(value) || "barcode".equals(value)
            || "image-labeling".equals(value) || "ocr".equals(value))) {
            throw new IllegalArgumentException(
                "Unknown module '" + rawValue
                    + "'. Use face, barcode, image-labeling, or ocr."
            );
        }
        modules.add(value);
    }

    private void processBarcodes(JSONArray args, CallbackContext callbackContext) {
        String imagePath = args.optString(0, "").trim();
        try {
            JSONObject options = optionsAt(args, 1, "options");
            MlKitLocalization localization = localization(options);
            if (imagePath.isEmpty()) {
                callbackContext.error(error("INVALID_ARGUMENT",
                    "imagePath must be a non-empty string.", false));
                return;
            }
            int[] formats = BarcodeScanConfig.imageFormatValuesFromJson(options);
            String loadingMessage = moduleLoadingMessage(options);
            ensureModelsAvailable(false, true, false, false, loadingMessage, localization,
                callbackContext, () -> {
                File cache = new File(cordova.getActivity().getCacheDir(), "mlkit-suite");
                cordova.getThreadPool().execute(() -> StaticImageProcessor.scanBarcodes(
                    cordova.getActivity().getApplicationContext(), imagePath, cache, formats,
                    localization, callbackContext
                ));
            });
        } catch (IllegalArgumentException exception) {
            callbackContext.error(error("INVALID_ARGUMENT", exception.getMessage(), false));
        }
    }

    private void processImageLabels(JSONArray args, CallbackContext callbackContext) {
        String imagePath = args.optString(0, "").trim();
        try {
            JSONObject options = optionsAt(args, 1, "options");
            MlKitLocalization localization = localization(options);
            if (imagePath.isEmpty()) {
                callbackContext.error(error("INVALID_ARGUMENT",
                    "imagePath must be a non-empty string.", false));
                return;
            }
            Float threshold = null;
            if (options.has("confidenceThreshold") && !options.isNull("confidenceThreshold")) {
                Object rawThreshold = options.opt("confidenceThreshold");
                if (!(rawThreshold instanceof Number)) {
                    throw new IllegalArgumentException(
                        "confidenceThreshold must be a number from 0 to 1."
                    );
                }
                float value = ((Number) rawThreshold).floatValue();
                if (Float.isNaN(value) || Float.isInfinite(value) || value < 0f || value > 1f) {
                    throw new IllegalArgumentException(
                        "confidenceThreshold must be a number from 0 to 1."
                    );
                }
                threshold = value;
            }
            Float confidenceThreshold = threshold;
            String loadingMessage = moduleLoadingMessage(options);
            ensureModelsAvailable(false, false, false, true, loadingMessage, localization,
                callbackContext, () -> {
                File cache = new File(cordova.getActivity().getCacheDir(), "mlkit-suite");
                cordova.getThreadPool().execute(() -> StaticImageProcessor.labelImage(
                    cordova.getActivity().getApplicationContext(), imagePath, cache,
                    confidenceThreshold, localization, callbackContext
                ));
            });
        } catch (IllegalArgumentException exception) {
            callbackContext.error(error("INVALID_ARGUMENT", exception.getMessage(), false));
        }
    }

    private void preparePendingCameraModule(String loadingMessage, CallbackContext owner) {
        boolean face = "faceCapture".equals(pendingAction);
        ensureModelsAvailable(face, !face, false, false, loadingMessage,
            localization(pendingOptions), owner, () -> {
            if (pendingCallback != owner) return;
            launchPendingActivity();
        }, () -> {
            if (pendingCallback == owner) clearPending();
        });
    }

    private void preparePendingDocumentScanner(String loadingMessage, CallbackContext owner) {
        cordova.getActivity().runOnUiThread(() -> DocumentScannerModuleInstaller.prepare(
            cordova.getActivity(), loadingMessage, localization(pendingOptions),
            new DocumentScannerModuleInstaller.Callback() {
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
                                       boolean imageLabeling,
                                       String loadingMessage, MlKitLocalization localization,
                                       CallbackContext callbackContext,
                                       Runnable onReady) {
        ensureModelsAvailable(
            face, barcode, ocr, imageLabeling, loadingMessage, localization,
            callbackContext, onReady, null
        );
    }

    private void ensureModelsAvailable(boolean face, boolean barcode, boolean ocr,
                                       boolean imageLabeling,
                                       String loadingMessage, MlKitLocalization localization,
                                       CallbackContext callbackContext,
                                       Runnable onReady, Runnable onErrorCleanup) {
        boolean prepareFace = face && !"bundled".equals(androidModuleMode(FACE_MODE_METADATA));
        boolean prepareBarcode = barcode
            && !"bundled".equals(androidModuleMode(BARCODE_MODE_METADATA));
        boolean prepareOcr = ocr && !"bundled".equals(androidModuleMode(OCR_MODE_METADATA));
        boolean prepareImageLabeling = imageLabeling
            && !"bundled".equals(androidModuleMode(IMAGE_LABELING_MODE_METADATA));
        if (!prepareFace && !prepareBarcode && !prepareOcr && !prepareImageLabeling) {
            onReady.run();
            return;
        }
        cordova.getActivity().runOnUiThread(() -> MlKitModelsModuleInstaller.prepare(
            cordova.getActivity(), prepareFace, prepareBarcode, prepareOcr,
            prepareImageLabeling, loadingMessage,
            localization,
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

    private String moduleLoadingMessage(JSONObject options) {
        if (options == null || !options.has("moduleLoadingMessage")
            || options.isNull("moduleLoadingMessage")) {
            return localization(options).text("loading_module");
        }
        Object value = options.opt("moduleLoadingMessage");
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("moduleLoadingMessage must be a string or null.");
        }
        return (String) value;
    }

    private MlKitLocalization localization(JSONObject options) {
        return MlKitLocalization.from(cordova.getActivity(), options);
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

