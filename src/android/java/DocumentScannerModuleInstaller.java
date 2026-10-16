package com.andreszs.plugins.mlkitsuite;

import android.app.Activity;

import com.google.android.gms.common.moduleinstall.InstallStatusListener;
import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallClient;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusCodes;
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;

import org.apache.cordova.CallbackContext;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

final class DocumentScannerModuleInstaller {
    private DocumentScannerModuleInstaller() { }

    static void prepare(Activity activity, CallbackContext callback) {
        GmsDocumentScanner scanner = GmsDocumentScanning.getClient(
            new GmsDocumentScannerOptions.Builder().build()
        );
        ModuleInstallClient client = ModuleInstall.getClient(activity);
        client.areModulesAvailable(scanner)
            .addOnSuccessListener(response -> {
                if (response.areModulesAvailable()) {
                    callback.success(readyResult(true));
                } else {
                    install(client, scanner, callback);
                }
            })
            .addOnFailureListener(error -> callback.error(MlKitSuitePlugin.error(
                "SCANNER_UNAVAILABLE",
                "Unable to check the Document Scanner component.",
                true
            )));
    }

    private static void install(ModuleInstallClient client, GmsDocumentScanner scanner,
                                CallbackContext callback) {
        AtomicBoolean finished = new AtomicBoolean(false);
        InstallStatusListener[] listenerHolder = new InstallStatusListener[1];
        listenerHolder[0] = update -> {
            int state = update.getInstallState();
            if (state == ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED) {
                finishSuccess(client, listenerHolder[0], callback, finished, false);
            } else if (state == ModuleInstallStatusUpdate.InstallState.STATE_CANCELED) {
                finishError(
                    client, listenerHolder[0], callback, finished,
                    "MODULE_DOWNLOAD_CANCELLED",
                    "Document Scanner component download was cancelled.",
                    true
                );
            } else if (state == ModuleInstallStatusUpdate.InstallState.STATE_FAILED) {
                String status = ModuleInstallStatusCodes.getStatusCodeString(update.getErrorCode());
                finishError(
                    client, listenerHolder[0], callback, finished,
                    "MODULE_DOWNLOAD_FAILED",
                    "Unable to download the Document Scanner component (" + status + ").",
                    true
                );
            }
        };

        ModuleInstallRequest request = ModuleInstallRequest.newBuilder()
            .addApi(scanner)
            .setListener(listenerHolder[0])
            .build();
        client.installModules(request)
            .addOnSuccessListener(response -> {
                if (response.areModulesAlreadyInstalled()) {
                    finishSuccess(client, listenerHolder[0], callback, finished, true);
                }
            })
            .addOnFailureListener(error -> finishError(
                client, listenerHolder[0], callback, finished,
                "MODULE_DOWNLOAD_FAILED",
                "Unable to start the Document Scanner component download.",
                true
            ));
    }

    private static void finishSuccess(ModuleInstallClient client, InstallStatusListener listener,
                                      CallbackContext callback, AtomicBoolean finished,
                                      boolean alreadyAvailable) {
        if (!finished.compareAndSet(false, true)) return;
        client.unregisterListener(listener);
        callback.success(readyResult(alreadyAvailable));
    }

    private static void finishError(ModuleInstallClient client, InstallStatusListener listener,
                                    CallbackContext callback, AtomicBoolean finished,
                                    String code, String message, boolean recoverable) {
        if (!finished.compareAndSet(false, true)) return;
        client.unregisterListener(listener);
        callback.error(MlKitSuitePlugin.error(code, message, recoverable));
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
