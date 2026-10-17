package com.andreszs.plugins.mlkitsuite;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

final class MlKitLoadingDialog {
    private final Activity activity;
    private final String message;
    private AlertDialog dialog;

    MlKitLoadingDialog(Activity activity, String message) {
        this.activity = activity;
        this.message = message == null ? "" : message;
    }

    void show() {
        if (activity.isFinishing() || activity.isDestroyed() || dialog != null) return;

        int padding = dp(24);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        ProgressBar spinner = new ProgressBar(activity);
        int spinnerSize = dp(40);
        content.addView(spinner, new LinearLayout.LayoutParams(spinnerSize, spinnerSize));

        if (!message.trim().isEmpty()) {
            TextView label = new TextView(activity);
            label.setText(message);
            label.setTextSize(16);
            label.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            labelParams.setMarginStart(dp(18));
            content.addView(label, labelParams);
        } else {
            spinner.setContentDescription("Loading");
        }

        try {
            dialog = new AlertDialog.Builder(activity)
                .setView(content)
                .setCancelable(false)
                .create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        } catch (RuntimeException ignored) {
            dialog = null;
        }
    }

    void dismiss() {
        AlertDialog current = dialog;
        dialog = null;
        if (current == null) return;
        try {
            if (current.isShowing()) current.dismiss();
        } catch (RuntimeException ignored) { }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
