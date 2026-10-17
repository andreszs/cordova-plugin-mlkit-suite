package com.andreszs.plugins.mlkitsuite;

import org.json.JSONObject;

final class FaceCaptureConfig {
    static final int MIN_OUTPUT_SIZE = 320;
    static final int MAX_OUTPUT_SIZE = 2048;

    final int outputWidth;
    final int outputHeight;
    final int jpegQuality;
    final boolean showCloseButton;
    final boolean debugMode;
    final String instructionText;
    final long timeoutMs;
    final Checks checks;

    private FaceCaptureConfig(
        int outputWidth,
        int outputHeight,
        int jpegQuality,
        boolean showCloseButton,
        boolean debugMode,
        String instructionText,
        long timeoutMs,
        Checks checks
    ) {
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.jpegQuality = jpegQuality;
        this.showCloseButton = showCloseButton;
        this.debugMode = debugMode;
        this.instructionText = instructionText;
        this.timeoutMs = timeoutMs;
        this.checks = checks;
    }

    static FaceCaptureConfig fromJson(JSONObject options) {
        if (options == null) {
            options = new JSONObject();
        }
        rejectUnknown(options);
        int width = strictInteger(options, "outputWidth", 600, MIN_OUTPUT_SIZE, MAX_OUTPUT_SIZE);
        int height = strictInteger(options, "outputHeight", 720, MIN_OUTPUT_SIZE, MAX_OUTPUT_SIZE);
        int quality = strictInteger(options, "jpegQuality", 95, 50, 100);
        boolean close = strictBoolean(options, "showCloseButton", true);
        boolean debug = strictBoolean(options, "debugMode", false);
        String instruction = strictString(options, "instructionText", "Place your face inside the oval");
        long timeout = strictLong(options, "timeoutMs", 0L, 0L, 600000L);
        JSONObject rawChecks = null;
        if (options.has("checks") && !options.isNull("checks")) {
            Object value = options.opt("checks");
            if (!(value instanceof JSONObject)) {
                throw new IllegalArgumentException("checks must be an object.");
            }
            rawChecks = (JSONObject) value;
        }
        return new FaceCaptureConfig(width, height, quality, close, debug, instruction, timeout,
            Checks.fromJson(rawChecks));
    }

    private static void rejectUnknown(JSONObject options) {
        java.util.Iterator<String> keys = options.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!("outputWidth".equals(key) || "outputHeight".equals(key)
                || "jpegQuality".equals(key) || "showCloseButton".equals(key)
                || "debugMode".equals(key) || "instructionText".equals(key)
                || "timeoutMs".equals(key) || "checks".equals(key)
                || "moduleLoadingMessage".equals(key))) {
                throw new IllegalArgumentException("Unknown face capture option: " + key + ".");
            }
        }
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

    private static String strictString(JSONObject object, String name, String fallback) {
        if (!object.has(name) || object.isNull(name)) return fallback;
        Object value = object.opt(name);
        if (!(value instanceof String)) throw new IllegalArgumentException(name + " must be a string.");
        return ((String) value).trim();
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
            eyesOpen = strictBoolean(source, "eyesOpen", true);
            neutralExpression = strictBoolean(source, "neutralExpression", true);
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

