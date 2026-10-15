package com.andreszs.plugins.mlkitsuite;

public final class BarcodeConfirmationTrackerTest {
    public static void main(String[] args) {
        BarcodeConfirmationTracker tracker = new BarcodeConfirmationTracker(2);

        require(tracker.observe("first", "QR_CODE") == 1, "first frame must start a candidate");
        require(!tracker.isConfirmed(), "one frame must not confirm");
        require(tracker.observe("first", "QR_CODE") == 2, "same second frame must advance");
        require(tracker.isConfirmed(), "two equal frames must confirm");

        tracker.reset();
        tracker.observe("same-text", "QR_CODE");
        require(tracker.observe("same-text", "PDF_417") == 1,
            "a format change must reset the sequence");
        require(!tracker.isConfirmed(), "different formats must not confirm");

        tracker.observe("other-text", "PDF_417");
        require(!tracker.isConfirmed(), "a value change must reset the sequence");
        require(tracker.observe(null, null) == 0, "an empty frame must reset the sequence");
        require(!tracker.isConfirmed(), "an empty frame must break consecutiveness");

        System.out.println("Barcode confirmation tests passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
