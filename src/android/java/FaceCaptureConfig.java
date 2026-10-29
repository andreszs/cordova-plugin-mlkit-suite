package com.andreszs.plugins.mlkitsuite;

import android.content.Context;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class FaceCaptureConfig {
    static final int MIN_OUTPUT_SIZE = 320;
    static final int MAX_OUTPUT_SIZE = 2048;

    final int outputWidth;
    final int outputHeight;
    final int jpegQuality;
    final boolean showCloseButton;
    final boolean debugMode;
    final boolean beepEnabled;
    final boolean revealCapture;
    final boolean requireDeviceVertical;
    final MlKitLocalization localization;
    final long timeoutMs;
    final Checks checks;
    final List<FaceCaptureModels.Gesture> gestures;
    final int captureAfterGesture;
    final long gestureTimeoutMs;
    final long neutralTimeoutMs;
    final long retryMessageMs;

    private FaceCaptureConfig(
        int outputWidth,
        int outputHeight,
        int jpegQuality,
        boolean showCloseButton,
        boolean debugMode,
        boolean beepEnabled,
        boolean revealCapture,
        boolean requireDeviceVertical,
        MlKitLocalization localization,
        long timeoutMs,
        Checks checks,
        List<FaceCaptureModels.Gesture> gestures,
        int captureAfterGesture,
        long gestureTimeoutMs,
        long neutralTimeoutMs,
        long retryMessageMs
    ) {
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.jpegQuality = jpegQuality;
        this.showCloseButton = showCloseButton;
        this.debugMode = debugMode;
        this.beepEnabled = beepEnabled;
        this.revealCapture = revealCapture;
        this.requireDeviceVertical = requireDeviceVertical;
        this.localization = localization;
        this.timeoutMs = timeoutMs;
        this.checks = checks;
        this.gestures = gestures;
        this.captureAfterGesture = captureAfterGesture;
        this.gestureTimeoutMs = gestureTimeoutMs;
        this.neutralTimeoutMs = neutralTimeoutMs;
        this.retryMessageMs = retryMessageMs;
    }

    static FaceCaptureConfig fromJson(Context context, JSONObject options) {
        if (options == null) {
            options = new JSONObject();
        }
        MlKitLocalization localization = MlKitLocalization.from(context, options);
        rejectUnknown(options);
        int width = strictInteger(options, "outputWidth", 600, MIN_OUTPUT_SIZE, MAX_OUTPUT_SIZE);
        int height = strictInteger(options, "outputHeight", 720, MIN_OUTPUT_SIZE, MAX_OUTPUT_SIZE);
        int quality = strictInteger(options, "jpegQuality", 95, 50, 100);
        boolean close = strictBoolean(options, "showCloseButton", true);
        boolean debug = strictBoolean(options, "debugMode", false);
        boolean beep = strictBoolean(options, "beepEnabled", true);
        boolean revealCapture = strictBoolean(options, "revealCapture", true);
        boolean requireDeviceVertical = strictBoolean(options, "requireDeviceVertical", true);
        long timeout = strictLong(options, "timeoutMs", 90000L, 0L, 600000L);
        JSONObject rawChecks = null;
        if (options.has("checks") && !options.isNull("checks")) {
            Object value = options.opt("checks");
            if (!(value instanceof JSONObject)) {
                throw new IllegalArgumentException("checks must be an object.");
            }
            rawChecks = (JSONObject) value;
        }
        List<FaceCaptureModels.Gesture> gestures = parseGestures(options);
        int captureAfter = gestures.isEmpty()
            ? strictInteger(options, "captureAfterGesture", 0, 0, 0)
            : strictInteger(options, "captureAfterGesture", gestures.size(), 1, gestures.size());
        long gestureTimeout = strictLong(options, "gestureTimeoutMs", 4000L, 1000L, 15000L);
        long neutralTimeout = strictLong(options, "neutralTimeoutMs", 2000L, 500L, 5000L);
        long retryMessage = strictLong(options, "retryMessageMs", 1000L, 250L, 3000L);
        return new FaceCaptureConfig(width, height, quality, close, debug, beep, revealCapture,
            requireDeviceVertical, localization, timeout, Checks.fromJson(rawChecks), gestures, captureAfter,
            gestureTimeout, neutralTimeout, retryMessage);
    }

    private static void rejectUnknown(JSONObject options) {
        java.util.Iterator<String> keys = options.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!("outputWidth".equals(key) || "outputHeight".equals(key)
                || "jpegQuality".equals(key) || "showCloseButton".equals(key)
                || "debugMode".equals(key) || "beepEnabled".equals(key)
                || "revealCapture".equals(key) || "requireDeviceVertical".equals(key)
                || "timeoutMs".equals(key) || "checks".equals(key) || "gestures".equals(key)
                || "captureAfterGesture".equals(key) || "gestureTimeoutMs".equals(key)
                || "neutralTimeoutMs".equals(key) || "retryMessageMs".equals(key)
                || "moduleLoadingMessage".equals(key) || "locale".equals(key))) {
                throw new IllegalArgumentException("Unknown face capture option: " + key + ".");
            }
        }
    }

    private static List<FaceCaptureModels.Gesture> parseGestures(JSONObject options) {
        if (!options.has("gestures") || options.isNull("gestures")) {
            return Collections.emptyList();
        }
        Object raw = options.opt("gestures");
        if (!(raw instanceof JSONArray)) {
            throw new IllegalArgumentException("gestures must be an array.");
        }
        JSONArray array = (JSONArray) raw;
        if (array.length() > 5) {
            throw new IllegalArgumentException("gestures supports at most 5 entries.");
        }
        List<FaceCaptureModels.Gesture> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("gestures entries must be strings.");
            }
            String name = ((String) value).trim().toUpperCase(Locale.US);
            try {
                result.add(FaceCaptureModels.Gesture.valueOf(name));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Unsupported face gesture: " + value + ".");
            }
        }
        return Collections.unmodifiableList(result);
    }

    JSONArray gesturesJson() {
        JSONArray value = new JSONArray();
        for (FaceCaptureModels.Gesture gesture : gestures) value.put(gesture.name());
        return value;
    }

    private static int strictInteger(JSONObject object, String name, int fallback, int min, int max) {
        if (!object.has(name)) return fallback;
        Object value = object.opt(name);
        if (!(value instanceof Number)) throw new IllegalArgumentException(name + " must be an integer.");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max) {
            throw new IllegalArgumentException(name + " must be an integer between " + min + " and " + max + ".");
        }
        return (int) number;
    }

    private static long strictLong(JSONObject object, String name, long fallback, long min, long max) {
        if (!object.has(name)) return fallback;
        Object value = object.opt(name);
        if (!(value instanceof Number)) throw new IllegalArgumentException(name + " must be an integer.");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max) {
            throw new IllegalArgumentException(name + " must be an integer between " + min + " and " + max + ".");
        }
        return (long) number;
    }

    private static boolean strictBoolean(JSONObject object, String name, boolean fallback) {
        if (!object.has(name)) return fallback;
        Object value = object.opt(name);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(name + " must be boolean.");
        return (Boolean) value;
    }

    static final class Checks {
        final boolean singleFace;
        final boolean faceSize;
        final boolean centering;
        final boolean pose;
        final boolean eyesOpen;
        final boolean neutralExpression;
        final boolean stability;
        final boolean sharpness;
        final boolean brightness;

        private Checks(JSONObject source) {
            singleFace = strictBoolean(source, "singleFace", true);
            faceSize = strictBoolean(source, "faceSize", true);
            centering = strictBoolean(source, "centering", true);
            pose = strictBoolean(source, "pose", true);
            eyesOpen = strictBoolean(source, "eyesOpen", false);
            neutralExpression = strictBoolean(source, "neutralExpression", false);
            stability = strictBoolean(source, "stability", true);
            sharpness = strictBoolean(source, "sharpness", true);
            brightness = strictBoolean(source, "brightness", true);
        }

        static Checks fromJson(JSONObject source) {
            JSONObject object = source == null ? new JSONObject() : source;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!("singleFace".equals(key) || "faceSize".equals(key) || "centering".equals(key)
                    || "pose".equals(key) || "eyesOpen".equals(key)
                    || "neutralExpression".equals(key) || "stability".equals(key)
                    || "sharpness".equals(key) || "brightness".equals(key))) {
                    throw new IllegalArgumentException("Unknown face capture check: " + key + ".");
                }
            }
            return new Checks(object);
        }

        JSONObject toJson() {
            JSONObject value = new JSONObject();
            try {
                value.put("singleFace", singleFace);
                value.put("faceSize", faceSize);
                value.put("centering", centering);
                value.put("pose", pose);
                value.put("eyesOpen", eyesOpen);
                value.put("neutralExpression", neutralExpression);
                value.put("stability", stability);
                value.put("sharpness", sharpness);
                value.put("brightness", brightness);
            } catch (Exception ignored) { }
            return value;
        }
    }
}

