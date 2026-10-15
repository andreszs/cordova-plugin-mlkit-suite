package com.andreszs.plugins.mlkitsuite;

final class BarcodeConfirmationTracker {
    private final int requiredFrames;
    private String text;
    private String format;
    private int consecutiveFrames;

    BarcodeConfirmationTracker(int requiredFrames) {
        if (requiredFrames < 1) throw new IllegalArgumentException("requiredFrames must be positive.");
        this.requiredFrames = requiredFrames;
    }

    int observe(String nextText, String nextFormat) {
        if (nextText == null || nextText.isEmpty() || nextFormat == null || nextFormat.isEmpty()) {
            reset();
            return 0;
        }
        if (nextText.equals(text) && nextFormat.equals(format)) {
            consecutiveFrames++;
        } else {
            text = nextText;
            format = nextFormat;
            consecutiveFrames = 1;
        }
        return consecutiveFrames;
    }

    boolean isConfirmed() {
        return consecutiveFrames >= requiredFrames;
    }

    void reset() {
        text = null;
        format = null;
        consecutiveFrames = 0;
    }
}
