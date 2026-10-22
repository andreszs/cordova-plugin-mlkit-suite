package com.andreszs.plugins.mlkitsuite;

import android.app.Activity;

import com.google.android.gms.common.moduleinstall.InstallStatusListener;
import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallClient;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusCodes;
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate;
import com.google.android.gms.common.api.OptionalModuleApi;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class MlKitModelsModuleInstaller {
    interface Callback {
        void onReady(JSONObject result);
        void onError(JSONObject error);
    }

    interface AvailabilityCallback {
        void onResult(JSONObject result);
    }

    private MlKitModelsModuleInstaller() { }

    static void checkAvailability(Activity activity, boolean includeFace, boolean includeBarcode,
                                  boolean includeText, boolean includeImageLabeling,
                                  AvailabilityCallback callback) {
        JSONObject result = new JSONObject();
        int requestedCount = (includeFace ? 1 : 0) + (includeBarcode ? 1 : 0)
            + (includeText ? 1 : 0) + (includeImageLabeling ? 1 : 0);
        if (requestedCount == 0) {
            callback.onResult(result);
            return;
        }

        ModuleInstallClient client = ModuleInstall.getClient(activity);
        AtomicInteger remaining = new AtomicInteger(requestedCount);
        if (includeFace) {
            FaceDetector face = FaceDetection.getClient(new FaceDetectorOptions.Builder().build());
            checkOne(client, "face", (OptionalModuleApi) face, face::close,
                result, remaining, callback);
        }
        if (includeBarcode) {
            BarcodeScanner barcode = BarcodeScanning.getClient();
            checkOne(client, "barcode", (OptionalModuleApi) barcode, barcode::close,
                result, remaining, callback);
        }
        if (includeText) {
            TextRecognizer text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            checkOne(client, "text", (OptionalModuleApi) text, text::close,
                result, remaining, callback);
        }
        if (includeImageLabeling) {
            ImageLabeler imageLabeling = ImageLabeling.getClient(
                ImageLabelerOptions.DEFAULT_OPTIONS
            );
            checkOne(client, "imageLabeling", (OptionalModuleApi) imageLabeling,
                imageLabeling::close, result, remaining, callback);
        }
    }

    private static void checkOne(ModuleInstallClient client, String name, OptionalModuleApi module,
                                 Runnable closeAction, JSONObject result,
                                 AtomicInteger remaining, AvailabilityCallback callback) {
        client.areModulesAvailable(module)
            .addOnSuccessListener(response -> finishAvailabilityCheck(
                name, response.areModulesAvailable(), closeAction, result, remaining, callback
            ))
            .addOnFailureListener(error -> finishAvailabilityCheck(
                name, null, closeAction, result, remaining, callback
            ));
    }

    private static void finishAvailabilityCheck(String name, Boolean ready, Runnable closeAction,
                                                JSONObject result, AtomicInteger remaining,
                                                AvailabilityCallback callback) {
        closeAction.run();
        synchronized (result) {
            try {
                JSONObject availability = new JSONObject();
                availability.put("ready", ready == null ? JSONObject.NULL : ready);
                availability.put("modelStatus", ready == null
                    ? "unknown" : ready ? "downloaded" : "not-ready");
                result.put(name, availability);
            } catch (Exception ignored) { }
            if (remaining.decrementAndGet() == 0) callback.onResult(result);
        }
    }

    static void prepare(Activity activity, boolean includeFace, boolean includeBarcode,
                        boolean includeText, boolean includeImageLabeling, String loadingMessage,
                        MlKitLocalization localization, Callback callback) {
        final FaceDetector face = includeFace
            ? FaceDetection.getClient(new FaceDetectorOptions.Builder().build()) : null;
        final BarcodeScanner barcode = includeBarcode ? BarcodeScanning.getClient() : null;
        final TextRecognizer text = includeText
            ? TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) : null;
        final ImageLabeler imageLabeling = includeImageLabeling
            ? ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) : null;
        List<OptionalModuleApi> selectedModules = new ArrayList<>();
        if (face != null) selectedModules.add((OptionalModuleApi) face);
        if (barcode != null) selectedModules.add((OptionalModuleApi) barcode);
        if (text != null) selectedModules.add((OptionalModuleApi) text);
        if (imageLabeling != null) selectedModules.add((OptionalModuleApi) imageLabeling);
        OptionalModuleApi[] modules = selectedModules.toArray(new OptionalModuleApi[0]);
        ModuleInstallClient client = ModuleInstall.getClient(activity);

        client.areModulesAvailable(modules)
            .addOnSuccessListener(response -> {
                if (response.areModulesAvailable()) {
                    close(face, barcode, text, imageLabeling);
                    callback.onReady(readyResult(true));
                } else {
                    MlKitLoadingDialog loadingDialog = new MlKitLoadingDialog(
                        activity, loadingMessage
                    );
                    loadingDialog.show();
                    install(client, modules, face, barcode, text, imageLabeling, loadingDialog,
                        localization, callback);
                }
            })
            .addOnFailureListener(error -> {
                close(face, barcode, text, imageLabeling);
                callback.onError(MlKitSuitePlugin.error(
                    "MODULE_DOWNLOAD_FAILED",
                    localization.text("models_check_failed"),
                    true
                ));
            });
    }

    private static void install(ModuleInstallClient client, OptionalModuleApi[] modules,
                                FaceDetector face, BarcodeScanner barcode, TextRecognizer text,
                                ImageLabeler imageLabeling,
                                MlKitLoadingDialog loadingDialog,
                                MlKitLocalization localization, Callback callback) {
        AtomicBoolean finished = new AtomicBoolean(false);
        InstallStatusListener[] listenerHolder = new InstallStatusListener[1];
        listenerHolder[0] = update -> {
            int state = update.getInstallState();
            if (state == ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED) {
                finishSuccess(client, listenerHolder[0], face, barcode, text, imageLabeling,
                    loadingDialog, callback, finished, false);
            } else if (state == ModuleInstallStatusUpdate.InstallState.STATE_CANCELED) {
                finishError(client, listenerHolder[0], face, barcode, text, imageLabeling, loadingDialog,
                    callback, finished,
                    "MODULE_DOWNLOAD_CANCELLED",
                    localization.text("models_download_cancelled"));
            } else if (state == ModuleInstallStatusUpdate.InstallState.STATE_FAILED) {
                String status = ModuleInstallStatusCodes.getStatusCodeString(update.getErrorCode());
                finishError(client, listenerHolder[0], face, barcode, text, imageLabeling, loadingDialog,
                    callback, finished,
                    "MODULE_DOWNLOAD_FAILED",
                    localization.format("models_download_failed", status));
            }
        };

        ModuleInstallRequest.Builder requestBuilder = ModuleInstallRequest.newBuilder();
        for (OptionalModuleApi module : modules) requestBuilder.addApi(module);
        ModuleInstallRequest request = requestBuilder.setListener(listenerHolder[0]).build();
        client.installModules(request)
            .addOnSuccessListener(response -> {
                if (response.areModulesAlreadyInstalled()) {
                    finishSuccess(client, listenerHolder[0], face, barcode, text, imageLabeling,
                        loadingDialog, callback, finished, true);
                }
            })
            .addOnFailureListener(error -> finishError(
                client, listenerHolder[0], face, barcode, text, imageLabeling, loadingDialog, callback, finished,
                "MODULE_DOWNLOAD_FAILED", localization.text("models_download_start_failed")
            ));
    }

    private static void finishSuccess(ModuleInstallClient client, InstallStatusListener listener,
                                      FaceDetector face, BarcodeScanner barcode,
                                      TextRecognizer text, ImageLabeler imageLabeling,
                                      MlKitLoadingDialog loadingDialog,
                                      Callback callback,
                                      AtomicBoolean finished, boolean alreadyAvailable) {
        if (!finished.compareAndSet(false, true)) return;
        client.unregisterListener(listener);
        loadingDialog.dismiss();
        close(face, barcode, text, imageLabeling);
        callback.onReady(readyResult(alreadyAvailable));
    }

    private static void finishError(ModuleInstallClient client, InstallStatusListener listener,
                                    FaceDetector face, BarcodeScanner barcode,
                                    TextRecognizer text, ImageLabeler imageLabeling,
                                    MlKitLoadingDialog loadingDialog,
                                    Callback callback,
                                    AtomicBoolean finished, String code, String message) {
        if (!finished.compareAndSet(false, true)) return;
        client.unregisterListener(listener);
        loadingDialog.dismiss();
        close(face, barcode, text, imageLabeling);
        callback.onError(MlKitSuitePlugin.error(code, message, true));
    }

    private static void close(FaceDetector face, BarcodeScanner barcode, TextRecognizer text,
                              ImageLabeler imageLabeling) {
        if (face != null) face.close();
        if (barcode != null) barcode.close();
        if (text != null) text.close();
        if (imageLabeling != null) imageLabeling.close();
    }

    private static JSONObject readyResult(boolean alreadyAvailable) {
        JSONObject result = new JSONObject();
        try {
            result.put("status", "ready");
            result.put("alreadyAvailable", alreadyAvailable);
        } catch (Exception ignored) { }
        return result;
    }
}
