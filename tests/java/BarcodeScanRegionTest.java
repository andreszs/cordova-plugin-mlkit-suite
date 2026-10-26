package com.andreszs.plugins.mlkitsuite;

public final class BarcodeScanRegionTest {
    public static void main(String[] args) {
        require(BarcodeScanRegion.contains(
            1080, 2340, 108, 622, 972, 1486,
            1080, 1920, 270, 600, 810, 1200
        ), "a centered barcode must be accepted");

        require(!BarcodeScanRegion.contains(
            1080, 2340, 108, 622, 972, 1486,
            1080, 1920, 0, 600, 540, 1200
        ), "a barcode crossing the left guide edge must be rejected");

        require(!BarcodeScanRegion.contains(
            1080, 2340, 108, 622, 972, 1486,
            1080, 1920, 270, 600, 810, 1500
        ), "a barcode crossing the bottom guide edge must be rejected");

        float[] mapped = BarcodeScanRegion.map(
            1080, 2340, 1080, 1920, 270, 600, 810, 1200
        );
        require(mapped != null, "a valid barcode must map to preview coordinates");
        require(closeTo(mapped[0], 210.9375f) && closeTo(mapped[1], 731.25f)
            && closeTo(mapped[2], 869.0625f) && closeTo(mapped[3], 1462.5f),
            "mapped coordinates must use the preview's center-crop transform");

        System.out.println("Barcode scan region tests passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean closeTo(float actual, float expected) {
        return Math.abs(actual - expected) < 0.01f;
    }
}
