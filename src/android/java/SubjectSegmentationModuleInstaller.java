package com.andreszs.plugins.mlkitsuite;

import android.app.Activity;

import com.google.android.gms.common.moduleinstall.InstallStatusListener;
import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallClient;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusCodes;
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

final class SubjectSegmentationModuleInstaller {
    interface Callback {
        void onReady(JSONObject result);
        void onError(JSONObject error);
    }

    interface AvailabilityCallback {
        void onResult(Boolean ready);
    }

    private SubjectSegmentationModuleInstaller() { }

    static void checkAvailability(Activity activity, AvailabilityCallback callback) {
        SubjectSegmenter segmenter = segmenter();
        ModuleInstall.getClient(activity).areModulesAvailable(segmenter)
            .addOnSuccessListener(response -> {
                segmenter.close();
                callback.onResult(response.areModulesAvailable());
            })
            .addOnFailureListener(error -> {
                segmenter.close();
                callback.onResult(null);
            });
    }

    static void prepare(Activity activity, String loadingMessage,
                        MlKitLocalization localization, Callback callback) {
        SubjectSegmenter segmenter = segmenter();
        ModuleInstallClient client = ModuleInstall.getClient(activity);
        client.areModulesAvailable(segmenter)
            .addOnSuccessListener(response -> {
                if (response.areModulesAvailable()) {
                    segmenter.close();
                    callback.onReady(readyResult(true));
                } else {
                    MlKitLoadingDialog loadingDialog = new MlKitLoadingDialog(
                        activity, loadingMessage
                    );
                    loadingDialog.show();
                    install(client, segmenter, loadingDialog, localization, callback);
                }
            })
            .addOnFailureListener(error -> {
                segmenter.close();
                callback.onError(MlKitSuitePlugin.error(
                    "MODULE_UNAVAILABLE",
                    localization.text("subject_check_failed"),
                    true
                ));
            });
    }

    private static SubjectSegmenter segmenter() {
        return SubjectSegmentation.getClient(
            new SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        );
    }

    private static void install(ModuleInstallClient client, SubjectSegmenter segmenter,
                                MlKitLoadingDialog loadingDialog,
                                MlKitLocalization localization, Callback callback) {
        AtomicBoolean finished = new AtomicBoolean(false);
        InstallStatusListener[] listenerHolder = new InstallStatusListener[1];
        listenerHolder[0] = update -> {
            int state = update.getInstallState();
            if (state == ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED) {
                finishSuccess(
                    client, listenerHolder[0], segmenter, loadingDialog, callback, finished, false
                );
            } else if (state == ModuleInstallStatusUpdate.InstallState.STATE_CANCELED) {
                finishError(
                    client, listenerHolder[0], segmenter, loadingDialog, callback, finished,
                    "MODULE_DOWNLOAD_CANCELLED",
                    localization.text("subject_download_cancelled")
                );
            } else if (state == ModuleInstallStatusUpdate.InstallState.STATE_FAILED) {
                String status = ModuleInstallStatusCodes.getStatusCodeString(update.getErrorCode());
                finishError(
                    client, listenerHolder[0], segmenter, loadingDialog, callback, finished,
                    "MODULE_DOWNLOAD_FAILED",
                    localization.format("subject_download_failed", status)
                );
            }
        };

        ModuleInstallRequest request = ModuleInstallRequest.newBuilder()
            .addApi(segmenter)
            .setListener(listenerHolder[0])
            .build();
        client.installModules(request)
            .addOnSuccessListener(response -> {
                if (response.areModulesAlreadyInstalled()) {
                    finishSuccess(
                        client, listenerHolder[0], segmenter, loadingDialog,
                        callback, finished, true
                    );
                }
            })
            .addOnFailureListener(error -> finishError(
                client, listenerHolder[0], segmenter, loadingDialog, callback, finished,
                "MODULE_DOWNLOAD_FAILED",
                localization.text("subject_download_start_failed")
            ));
    }

    private static void finishSuccess(ModuleInstallClient client, InstallStatusListener listener,
                                      SubjectSegmenter segmenter,
                                      MlKitLoadingDialog loadingDialog, Callback callback,
                                      AtomicBoolean finished, boolean alreadyAvailable) {
        if (!finished.compareAndSet(false, true)) return;
        client.unregisterListener(listener);
        loadingDialog.dismiss();
        segmenter.close();
        callback.onReady(readyResult(alreadyAvailable));
    }

    private static void finishError(ModuleInstallClient client, InstallStatusListener listener,
                                    SubjectSegmenter segmenter,
                                    MlKitLoadingDialog loadingDialog, Callback callback,
                                    AtomicBoolean finished, String code, String message) {
        if (!finished.compareAndSet(false, true)) return;
        client.unregisterListener(listener);
        loadingDialog.dismiss();
        segmenter.close();
        callback.onError(MlKitSuitePlugin.error(code, message, true));
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
