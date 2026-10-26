package com.andreszs.plugins.mlkitsuite;

final class BarcodeScanRegion {
    private BarcodeScanRegion() { }

    static boolean contains(
        float viewWidth,
        float viewHeight,
        float guideLeft,
        float guideTop,
        float guideRight,
        float guideBottom,
        int imageWidth,
        int imageHeight,
        float barcodeLeft,
        float barcodeTop,
        float barcodeRight,
        float barcodeBottom
    ) {
        float[] mapped = map(viewWidth, viewHeight, imageWidth, imageHeight,
            barcodeLeft, barcodeTop, barcodeRight, barcodeBottom);
        return mapped != null
            && mapped[0] >= guideLeft && mapped[1] >= guideTop
            && mapped[2] <= guideRight && mapped[3] <= guideBottom;
    }

    static float[] map(
        float viewWidth,
        float viewHeight,
        int imageWidth,
        int imageHeight,
        float barcodeLeft,
        float barcodeTop,
        float barcodeRight,
        float barcodeBottom
    ) {
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0
            || barcodeRight <= barcodeLeft || barcodeBottom <= barcodeTop) {
            return null;
        }
        float scale = Math.max(viewWidth / imageWidth, viewHeight / imageHeight);
        float offsetX = (viewWidth - imageWidth * scale) / 2f;
        float offsetY = (viewHeight - imageHeight * scale) / 2f;
        return new float[] {
            barcodeLeft * scale + offsetX,
            barcodeTop * scale + offsetY,
            barcodeRight * scale + offsetX,
            barcodeBottom * scale + offsetY
        };
    }
}
