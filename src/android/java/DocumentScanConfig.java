package com.andreszs.plugins.mlkitsuite;

import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

final class DocumentScanConfig {
    final int pageLimit;
    final boolean galleryImportAllowed;
    final int[] resultFormats;
    final int scannerMode;

    private DocumentScanConfig(int pageLimit, boolean galleryImportAllowed,
                               int[] resultFormats, int scannerMode) {
        this.pageLimit = pageLimit;
        this.galleryImportAllowed = galleryImportAllowed;
        this.resultFormats = resultFormats;
        this.scannerMode = scannerMode;
    }

    static DocumentScanConfig fromJson(JSONObject options) {
        JSONObject value = options == null ? new JSONObject() : options;
        rejectUnknown(value);
        int pageLimit = strictInteger(value, "pageLimit", 1, 1, 100);
        boolean gallery = strictBoolean(value, "galleryImportAllowed", true);
        int[] formats = parseResultFormats(value.opt("resultFormats"));
        int mode = parseScannerMode(value.opt("scannerMode"));
        return new DocumentScanConfig(pageLimit, gallery, formats, mode);
    }

    GmsDocumentScannerOptions toNativeOptions() {
        int[] additional = new int[Math.max(0, resultFormats.length - 1)];
        if (additional.length > 0) {
            System.arraycopy(resultFormats, 1, additional, 0, additional.length);
        }
        return new GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(galleryImportAllowed)
            .setPageLimit(pageLimit)
            .setResultFormats(resultFormats[0], additional)
            .setScannerMode(scannerMode)
            .build();
    }

    private static int[] parseResultFormats(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return new int[] {
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            };
        }
        List<Integer> formats = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int index = 0; index < array.length(); index++) {
                Object item = array.opt(index);
                if (!(item instanceof String)) {
                    throw new IllegalArgumentException("resultFormats must contain only strings.");
                }
                addResultFormat(formats, (String) item);
            }
        } else if (raw instanceof String) {
            for (String item : ((String) raw).split(",")) addResultFormat(formats, item);
        } else {
            throw new IllegalArgumentException(
                "resultFormats must be an array of strings or a comma-separated string."
            );
        }
        if (formats.isEmpty()) throw new IllegalArgumentException("resultFormats must not be empty.");
        int[] values = new int[formats.size()];
        for (int index = 0; index < formats.size(); index++) values[index] = formats.get(index);
        return values;
    }

    private static void addResultFormat(List<Integer> formats, String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.US);
        int format;
        if ("JPEG".equals(value) || "JPG".equals(value)) {
            format = GmsDocumentScannerOptions.RESULT_FORMAT_JPEG;
        } else if ("PDF".equals(value)) {
            format = GmsDocumentScannerOptions.RESULT_FORMAT_PDF;
        } else {
            throw new IllegalArgumentException("Unsupported document result format: " + raw + ".");
        }
        if (!formats.contains(format)) formats.add(format);
    }

    private static int parseScannerMode(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return GmsDocumentScannerOptions.SCANNER_MODE_FULL;
        if (!(raw instanceof String)) throw new IllegalArgumentException("scannerMode must be a string.");
        String value = ((String) raw).trim().toUpperCase(Locale.US).replace('-', '_');
        if ("BASE".equals(value)) return GmsDocumentScannerOptions.SCANNER_MODE_BASE;
        if ("BASE_WITH_FILTER".equals(value)) {
            return GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER;
        }
        if ("FULL".equals(value)) return GmsDocumentScannerOptions.SCANNER_MODE_FULL;
        throw new IllegalArgumentException("scannerMode must be BASE, BASE_WITH_FILTER, or FULL.");
    }

    private static void rejectUnknown(JSONObject options) {
        Iterator<String> keys = options.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!("pageLimit".equals(key) || "galleryImportAllowed".equals(key)
                || "resultFormats".equals(key) || "scannerMode".equals(key))) {
                throw new IllegalArgumentException("Unknown document scan option: " + key + ".");
            }
        }
    }

    private static boolean strictBoolean(JSONObject object, String name, boolean fallback) {
        if (!object.has(name)) return fallback;
        Object value = object.opt(name);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(name + " must be boolean.");
        return (Boolean) value;
    }

    private static int strictInteger(JSONObject object, String name, int fallback, int min, int max) {
        if (!object.has(name)) return fallback;
        Object value = object.opt(name);
        if (!(value instanceof Number)) throw new IllegalArgumentException(name + " must be an integer.");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max) {
            throw new IllegalArgumentException(
                name + " must be an integer between " + min + " and " + max + "."
            );
        }
        return (int) number;
    }
}
