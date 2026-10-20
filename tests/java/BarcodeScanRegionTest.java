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

        System.out.println("Barcode scan region tests passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
