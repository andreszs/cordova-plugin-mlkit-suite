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
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            return false;
        }
        float scale = Math.max(viewWidth / imageWidth, viewHeight / imageHeight);
        float offsetX = (viewWidth - imageWidth * scale) / 2f;
        float offsetY = (viewHeight - imageHeight * scale) / 2f;
        float left = barcodeLeft * scale + offsetX;
        float top = barcodeTop * scale + offsetY;
        float right = barcodeRight * scale + offsetX;
        float bottom = barcodeBottom * scale + offsetY;
        return left >= guideLeft && top >= guideTop
            && right <= guideRight && bottom <= guideBottom;
    }
}
