package com.andreszs.plugins.mlkitsuite;

import android.graphics.RectF;

final class FaceCentering {
    static final float MAX_HORIZONTAL_OFFSET = 0.42f;
    static final float MAX_VERTICAL_OFFSET = 0.38f;

    static final class Result {
        final float horizontalOffset;
        final float verticalOffset;
        final boolean available;
        final boolean accepted;

        Result(float horizontalOffset, float verticalOffset,
               boolean available, boolean accepted) {
            this.horizontalOffset = horizontalOffset;
            this.verticalOffset = verticalOffset;
            this.available = available;
            this.accepted = accepted;
        }

        static Result unavailable() {
            return new Result(Float.NaN, Float.NaN, false, false);
        }
    }

    private FaceCentering() {}

    static Result evaluate(RectF normalizedFaceBounds, int previewWidth,
                           int previewHeight, RectF guide) {
        if (normalizedFaceBounds == null || previewWidth <= 0 || previewHeight <= 0
            || guide == null || guide.width() <= 0f || guide.height() <= 0f) {
            return Result.unavailable();
        }
        float centerX = normalizedFaceBounds.centerX() * previewWidth;
        float centerY = normalizedFaceBounds.centerY() * previewHeight;
        float horizontal = (centerX - guide.centerX()) / (guide.width() / 2f);
        float vertical = (centerY - guide.centerY()) / (guide.height() / 2f);
        boolean finite = Float.isFinite(horizontal) && Float.isFinite(vertical);
        return finite
            ? new Result(horizontal, vertical, true,
                Math.abs(horizontal) <= MAX_HORIZONTAL_OFFSET
                    && Math.abs(vertical) <= MAX_VERTICAL_OFFSET)
            : Result.unavailable();
    }
}
