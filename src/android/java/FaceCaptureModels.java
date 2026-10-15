package com.andreszs.plugins.mlkitsuite;

import android.graphics.RectF;

final class FaceCaptureModels {
    enum State {
        SEARCHING, MULTIPLE_FACES, MOVE_CLOSER, MOVE_AWAY, CENTER_FACE,
        LOOK_STRAIGHT, OPEN_EYES, NEUTRAL_EXPRESSION, IMPROVE_LIGHTING,
        REDUCE_LIGHTING, HOLD_STILL, READY, CAPTURING
    }

    static final class Analysis {
        final State state;
        final RectF normalizedBounds;
        final float yaw;
        final float pitch;
        final float roll;
        final float leftEye;
        final float rightEye;
        final float smile;
        final float brightness;
        final float sharpness;
        final long stableDurationMs;

        Analysis(State state, RectF bounds, float yaw, float pitch, float roll,
                 float leftEye, float rightEye, float smile, float brightness,
                 float sharpness, long stableDurationMs) {
            this.state = state;
            this.normalizedBounds = bounds;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.leftEye = leftEye;
            this.rightEye = rightEye;
            this.smile = smile;
            this.brightness = brightness;
            this.sharpness = sharpness;
            this.stableDurationMs = stableDurationMs;
        }

        Analysis withState(State next, long stableMs) {
            return new Analysis(next, normalizedBounds, yaw, pitch, roll, leftEye, rightEye,
                smile, brightness, sharpness, stableMs);
        }
    }

    static final class StabilityTracker {
        private RectF previous;
        private long stableSince;
        private int frames;

        long update(RectF bounds, long now) {
            if (previous == null) {
                reset(bounds, now);
                return 0;
            }
            float centerMotion = (float) Math.hypot(
                bounds.centerX() - previous.centerX(), bounds.centerY() - previous.centerY());
            float area = bounds.width() * bounds.height();
            float previousArea = previous.width() * previous.height();
            float areaChange = previousArea <= 0 ? 1f : Math.abs(area - previousArea) / previousArea;
            if (centerMotion > 0.015f || areaChange > 0.05f) {
                reset(bounds, now);
                return 0;
            }
            frames++;
            previous = new RectF(bounds);
            return frames >= 4 ? now - stableSince : 0;
        }

        void clear() {
            previous = null;
            stableSince = 0;
            frames = 0;
        }

        private void reset(RectF bounds, long now) {
            previous = new RectF(bounds);
            stableSince = now;
            frames = 1;
        }
    }

    private FaceCaptureModels() { }
}
