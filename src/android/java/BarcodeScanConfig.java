package com.andreszs.plugins.mlkitsuite;

import android.content.Context;
import android.graphics.Color;

import com.google.mlkit.vision.barcode.common.Barcode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BarcodeScanConfig {
    static final int TARGET_WIDTH = 1920;
    static final int TARGET_HEIGHT = 1080;
    static final int CONFIRMATION_FRAMES = 2;

    private static final LinkedHashMap<String, Integer> FORMATS = new LinkedHashMap<>();

    static {
        FORMATS.put("AZTEC", Barcode.FORMAT_AZTEC);
        FORMATS.put("CODABAR", Barcode.FORMAT_CODABAR);
        FORMATS.put("CODE_39", Barcode.FORMAT_CODE_39);
        FORMATS.put("CODE_93", Barcode.FORMAT_CODE_93);
        FORMATS.put("CODE_128", Barcode.FORMAT_CODE_128);
        FORMATS.put("DATA_MATRIX", Barcode.FORMAT_DATA_MATRIX);
        FORMATS.put("EAN_8", Barcode.FORMAT_EAN_8);
        FORMATS.put("EAN_13", Barcode.FORMAT_EAN_13);
        FORMATS.put("ITF", Barcode.FORMAT_ITF);
        FORMATS.put("PDF_417", Barcode.FORMAT_PDF417);
        FORMATS.put("QR_CODE", Barcode.FORMAT_QR_CODE);
        FORMATS.put("UPC_A", Barcode.FORMAT_UPC_A);
        FORMATS.put("UPC_E", Barcode.FORMAT_UPC_E);
    }

    final List<String> formatNames;
    final int[] formatValues;
    final boolean showCloseButton;
    final boolean showTorchButton;
    final boolean beepEnabled;
    final String instructionText;
    final MlKitLocalization localization;
    final Integer guideColor;
    final long timeoutMs;

    private BarcodeScanConfig(List<String> formatNames,
                              boolean showCloseButton, boolean showTorchButton,
                              boolean beepEnabled,
                              String instructionText, MlKitLocalization localization,
                              Integer guideColor, long timeoutMs) {
        this.formatNames = Collections.unmodifiableList(formatNames);
        this.formatValues = new int[formatNames.size()];
        for (int index = 0; index < formatNames.size(); index++) {
            this.formatValues[index] = FORMATS.get(formatNames.get(index));
        }
        this.showCloseButton = showCloseButton;
        this.showTorchButton = showTorchButton;
        this.beepEnabled = beepEnabled;
        this.instructionText = instructionText;
        this.localization = localization;
        this.guideColor = guideColor;
        this.timeoutMs = timeoutMs;
    }

    static BarcodeScanConfig fromJson(Context context, JSONObject options) {
        JSONObject value = options == null ? new JSONObject() : options;
        MlKitLocalization localization = MlKitLocalization.from(context, value);
        rejectUnknown(value);
        List<String> formats = parseFormats(value.opt("formats"));
        boolean close = strictBoolean(value, "showCloseButton", true);
        boolean torch = strictBoolean(value, "showTorchButton", true);
        boolean beep = strictBoolean(value, "beepEnabled", true);
        String instruction = strictString(value, "instructionText",
            localization.text("barcode_instruction"));
        Integer guideColor = strictColor(value, "guideColor");
        long timeout = strictLong(value, "timeoutMs", 0L, 0L, 600000L);
        return new BarcodeScanConfig(formats, close, torch, beep, instruction, localization,
            guideColor, timeout);
    }

    static JSONArray supportedFormatsJson() {
        JSONArray values = new JSONArray();
        for (String format : FORMATS.keySet()) values.put(format);
        return values;
    }

    static int[] imageFormatValuesFromJson(JSONObject options) {
        JSONObject value = options == null ? new JSONObject() : options;
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!("formats".equals(key) || "moduleLoadingMessage".equals(key)
                || "locale".equals(key))) {
                throw new IllegalArgumentException("Unknown barcode image option: " + key + ".");
            }
        }
        List<String> names = parseFormats(value.opt("formats"));
        int[] values = new int[names.size()];
        for (int index = 0; index < names.size(); index++) values[index] = FORMATS.get(names.get(index));
        return values;
    }

    static String nameForFormat(int format) {
        for (Map.Entry<String, Integer> entry : FORMATS.entrySet()) {
            if (entry.getValue() == format) return entry.getKey();
        }
        return "UNKNOWN";
    }

    private static List<String> parseFormats(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return new ArrayList<>(FORMATS.keySet());
        ArrayList<String> requested = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int index = 0; index < array.length(); index++) {
                Object item = array.opt(index);
                if (!(item instanceof String)) {
                    throw new IllegalArgumentException("formats must contain only strings.");
                }
                addFormat(requested, (String) item);
            }
        } else if (raw instanceof String) {
            String[] values = ((String) raw).split(",");
            for (String value : values) addFormat(requested, value);
        } else {
            throw new IllegalArgumentException("formats must be an array of strings or a comma-separated string.");
        }
        if (requested.isEmpty()) throw new IllegalArgumentException("formats must not be empty.");
        return requested;
    }

    private static void addFormat(List<String> requested, String raw) {
        String name = raw == null ? "" : raw.trim().toUpperCase(Locale.US).replace('-', '_');
        if ("PDF417".equals(name)) name = "PDF_417";
        if (!FORMATS.containsKey(name)) {
            throw new IllegalArgumentException("Unsupported barcode format: " + raw + ".");
        }
        if (!requested.contains(name)) requested.add(name);
    }

    private static void rejectUnknown(JSONObject options) {
        Iterator<String> keys = options.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!("formats".equals(key)
                || "showCloseButton".equals(key) || "showTorchButton".equals(key)
                || "beepEnabled".equals(key)
                || "instructionText".equals(key) || "guideColor".equals(key)
                || "timeoutMs".equals(key)
                || "moduleLoadingMessage".equals(key) || "locale".equals(key))) {
                throw new IllegalArgumentException("Unknown barcode scan option: " + key + ".");
            }
        }
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

    private static Integer strictColor(JSONObject object, String name) {
        if (!object.has(name) || object.isNull(name)) return null;
        Object value = object.opt(name);
        if (!(value instanceof String) || !((String) value).matches("^#[0-9A-Fa-f]{6}$")) {
            throw new IllegalArgumentException(name + " must be a color in #RRGGBB format.");
        }
        return Color.parseColor((String) value);
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
}
