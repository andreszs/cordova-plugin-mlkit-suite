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

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

final class DocumentScannerModuleInstaller {
    interface Callback {
        void onReady(JSONObject result);
        void onError(JSONObject error);
    }

    private DocumentScannerModuleInstaller() { }

    static void prepare(Activity activity, String loadingMessage, Callback callback) {
        GmsDocumentScanner scanner = GmsDocumentScanning.getClient(
            new GmsDocumentScannerOptions.Builder().build()
        );
        ModuleInstallClient client = ModuleInstall.getClient(activity);
        client.areModulesAvailable(scanner)
            .addOnSuccessListener(response -> {
                if (response.areModulesAvailable()) {
                    callback.onReady(readyResult(true));
                } else {
                    MlKitLoadingDialog loadingDialog = new MlKitLoadingDialog(
                        activity, loadingMessage
                    );
                    loadingDialog.show();
                    install(client, scanner, loadingDialog, callback);
                }
            })
            .addOnFailureListener(error -> callback.onError(MlKitSuitePlugin.error(
                "SCANNER_UNAVAILABLE",
                "Unable to check the Document Scanner component.",
                true
            )));
    }

    private static void install(ModuleInstallClient client, GmsDocumentScanner scanner,
                                MlKitLoadingDialog loadingDialog, Callback callback) {
        AtomicBoolean finished = new AtomicBoolean(false);
        InstallStatusListener[] listenerHolder = new InstallStatusListener[1];
        listenerHolder[0] = update -> {
            int state = update.getInstallState();
            if (state == ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED) {
                finishSuccess(client, listenerHolder[0], loadingDialog, callback, finished, false);
            } else if (state == ModuleInstallStatusUpdate.InstallState.STATE_CANCELED) {
                finishError(
                    client, listenerHolder[0], loadingDialog, callback, finished,
                    "MODULE_DOWNLOAD_CANCELLED",
                    "Document Scanner component download was cancelled.",
                    true
                );
            } else if (state == ModuleInstallStatusUpdate.InstallState.STATE_FAILED) {
                String status = ModuleInstallStatusCodes.getStatusCodeString(update.getErrorCode());
                finishError(
                    client, listenerHolder[0], loadingDialog, callback, finished,
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
                    finishSuccess(client, listenerHolder[0], loadingDialog, callback, finished, true);
                }
            })
            .addOnFailureListener(error -> finishError(
                client, listenerHolder[0], loadingDialog, callback, finished,
                "MODULE_DOWNLOAD_FAILED",
                "Unable to start the Document Scanner component download.",
                true
            ));
    }

    private static void finishSuccess(ModuleInstallClient client, InstallStatusListener listener,
                                      MlKitLoadingDialog loadingDialog, Callback callback,
                                      AtomicBoolean finished,
                                      boolean alreadyAvailable) {
        if (!finished.compareAndSet(false, true)) return;
        client.unregisterListener(listener);
        loadingDialog.dismiss();
        callback.onReady(readyResult(alreadyAvailable));
    }

    private static void finishError(ModuleInstallClient client, InstallStatusListener listener,
                                    MlKitLoadingDialog loadingDialog, Callback callback,
                                    AtomicBoolean finished,
                                    String code, String message, boolean recoverable) {
        if (!finished.compareAndSet(false, true)) return;
        client.unregisterListener(listener);
        loadingDialog.dismiss();
        callback.onError(MlKitSuitePlugin.error(code, message, recoverable));
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
