package com.andreszs.plugins.mlkitsuite;

import java.util.Collections;

public final class FaceGestureTrackerTest {
    private static final long FRAME_MS = 40L; // ~25 fps, typical for 480x640 FAST mode
    private static long now;

    public static void main(String[] args) {
        nodDownFirstCompletesAndCaptures();
        nodUpFirstCompletes();
        naturalContinuousShakeCompletes();
        singleNoisyFrameDoesNotResetHold();
        jitterAloneNeverCompletesNod();
        captureWaitsForAbsoluteFrontalPose();
        captureWaitsWhileDeviceTilted();
        quickBlinkIsAccepted();
        smileRequiresRelaxingBeforeCapture();
        System.out.println("Face gesture tracker tests passed.");
    }

    private static FaceCaptureModels.GestureTracker tracker(FaceCaptureModels.Gesture gesture) {
        now = 1000L;
        return new FaceCaptureModels.GestureTracker(Collections.singletonList(gesture), 1,
            6000L, 3000L, 1000L, true);
    }

    private static FaceCaptureModels.GestureProgress frame(
        FaceCaptureModels.GestureTracker tracker, float yaw, float pitch) {
        return frame(tracker, yaw, pitch, 0.9f, 0.9f, 0.1f, 0.01f, true);
    }

    private static FaceCaptureModels.GestureProgress frame(
        FaceCaptureModels.GestureTracker tracker, float yaw, float pitch, float leftEye,
        float rightEye, float smile, float mouth, boolean captureAllowed) {
        now += FRAME_MS;
        return tracker.update(yaw, pitch, 0f, leftEye, rightEye, smile, mouth, now,
            captureAllowed);
    }

    private static FaceCaptureModels.GestureProgress hold(
        FaceCaptureModels.GestureTracker tracker, float yaw, float pitch, long ms) {
        FaceCaptureModels.GestureProgress last = null;
        for (long t = 0; t < ms; t += FRAME_MS) last = frame(tracker, yaw, pitch);
        return last;
    }

    private static void arm(FaceCaptureModels.GestureTracker tracker, float yaw, float pitch) {
        FaceCaptureModels.GestureProgress progress = hold(tracker, yaw, pitch, 700L);
        assertStage(progress, FaceCaptureModels.GestureStage.PERFORM, "arming");
    }

    private static void nodDownFirstCompletesAndCaptures() {
        FaceCaptureModels.GestureTracker t = tracker(FaceCaptureModels.Gesture.NOD);
        arm(t, 0f, 2f);
        hold(t, 0f, -9f, 200L);
        FaceCaptureModels.GestureProgress p = t.progress();
        check(p.debug != null && p.debug.firstSide == -1, "down accepted first");
        hold(t, 0f, 9f, 200L);
        p = hold(t, 0f, 2f, 800L);
        assertStage(p, FaceCaptureModels.GestureStage.CAPTURE, "nod down-first capture");
    }

    private static void nodUpFirstCompletes() {
        FaceCaptureModels.GestureTracker t = tracker(FaceCaptureModels.Gesture.NOD);
        arm(t, 0f, 0f);
        hold(t, 0f, 11f, 200L);
        hold(t, 0f, -6f, 200L);
        FaceCaptureModels.GestureProgress p = hold(t, 0f, 0f, 800L);
        assertStage(p, FaceCaptureModels.GestureStage.CAPTURE, "nod up-first capture");
    }

    private static void naturalContinuousShakeCompletes() {
        FaceCaptureModels.GestureTracker t = tracker(FaceCaptureModels.Gesture.SHAKE);
        arm(t, 0f, 0f);
        // One continuous head turn left-right-center over ~1.2 s, never pausing.
        FaceCaptureModels.GestureProgress p = null;
        for (long ms = 0; ms <= 1200; ms += FRAME_MS) {
            float yaw = (float) (20.0 * Math.sin(2.0 * Math.PI * ms / 1200.0));
            p = frame(t, yaw, 0f);
        }
        p = hold(t, 0f, 0f, 900L);
        assertStage(p, FaceCaptureModels.GestureStage.CAPTURE, "continuous shake");
    }

    private static void singleNoisyFrameDoesNotResetHold() {
        FaceCaptureModels.GestureTracker t = tracker(FaceCaptureModels.Gesture.LOOK_UP);
        arm(t, 0f, 0f);
        FaceCaptureModels.GestureProgress p = null;
        for (int i = 0; i < 5; i++) {
            p = frame(t, 0f, 14f);
            if (p.debug != null && p.debug.heldMs > 0) break;
        }
        check(p.debug != null && p.debug.heldMs > 0, "hold started");
        long heldBefore = p.debug.heldMs;
        // One outlier frame at 6 degrees: below both the 12 degree entry and the 8.4 degree
        // hold thresholds when read raw. Smoothing plus hysteresis must absorb it.
        p = frame(t, 0f, 6f);
        check(p.debug != null && p.debug.heldMs > heldBefore, "outlier frame keeps the hold");
        for (int i = 0; i < 3 && !p.phaseFeedback; i++) p = frame(t, 0f, 14f);
        check(p.phaseFeedback, "look up accepted despite a noisy frame");
    }

    private static void jitterAloneNeverCompletesNod() {
        FaceCaptureModels.GestureTracker t = tracker(FaceCaptureModels.Gesture.NOD);
        arm(t, 0f, 0f);
        FaceCaptureModels.GestureProgress p = null;
        for (int i = 0; i < 160; i++) {
            float jitter = (i % 2 == 0 ? 1f : -1f) * 3.5f;
            p = frame(t, jitter, jitter);
            if (p.stage == FaceCaptureModels.GestureStage.RETRY) break;
        }
        assertStage(p, FaceCaptureModels.GestureStage.RETRY, "jitter must time out");
    }

    private static void captureWaitsForAbsoluteFrontalPose() {
        FaceCaptureModels.GestureTracker t = tracker(FaceCaptureModels.Gesture.LOOK_DOWN);
        arm(t, 0f, 8f); // natural rest pitch is +8 degrees
        hold(t, 0f, -5f, 300L); // -13 relative
        // Back to +17 absolute: within 11 degrees of the +8 baseline, but the still
        // validator would reject it, so the tracker must keep waiting.
        FaceCaptureModels.GestureProgress p = hold(t, 0f, 17f, 900L);
        assertStage(p, FaceCaptureModels.GestureStage.RETURN_NEUTRAL, "blocked off-axis capture");
        p = hold(t, 0f, 6f, 600L);
        assertStage(p, FaceCaptureModels.GestureStage.CAPTURE, "capture once frontal");
    }

    private static void captureWaitsWhileDeviceTilted() {
        FaceCaptureModels.GestureTracker t = tracker(FaceCaptureModels.Gesture.LOOK_UP);
        arm(t, 0f, 0f);
        hold(t, 0f, 15f, 300L);
        FaceCaptureModels.GestureProgress p = null;
        for (int i = 0; i < 20; i++) p = frame(t, 0f, 0f, 0.9f, 0.9f, 0.1f, 0.01f, false);
        assertStage(p, FaceCaptureModels.GestureStage.RETURN_NEUTRAL, "tilted device blocks capture");
        p = hold(t, 0f, 0f, 500L);
        assertStage(p, FaceCaptureModels.GestureStage.CAPTURE, "upright device captures");
    }

    private static void quickBlinkIsAccepted() {
        FaceCaptureModels.GestureTracker t = tracker(FaceCaptureModels.Gesture.BLINK);
        arm(t, 0f, 0f);
        frame(t, 0f, 0f, 0.2f, 0.25f, 0.1f, 0.01f, true);
        FaceCaptureModels.GestureProgress p = frame(t, 0f, 0f, 0.3f, 0.2f, 0.1f, 0.01f, true);
        check(p.phaseFeedback, "two-frame blink accepted");
    }

    private static void smileRequiresRelaxingBeforeCapture() {
        FaceCaptureModels.GestureTracker t = tracker(FaceCaptureModels.Gesture.SMILE);
        arm(t, 0f, 0f);
        FaceCaptureModels.GestureProgress p = null;
        for (int i = 0; i < 6; i++) p = frame(t, 0f, 0f, 0.9f, 0.9f, 0.9f, 0.01f, true);
        check(p.phaseFeedback, "smile accepted");
        for (int i = 0; i < 25; i++) p = frame(t, 0f, 0f, 0.9f, 0.9f, 0.8f, 0.01f, true);
        assertStage(p, FaceCaptureModels.GestureStage.RETURN_NEUTRAL, "still smiling");
        for (int i = 0; i < 12; i++) p = frame(t, 0f, 0f, 0.9f, 0.9f, 0.2f, 0.01f, true);
        assertStage(p, FaceCaptureModels.GestureStage.CAPTURE, "relaxed smile captures");
    }

    private static void assertStage(FaceCaptureModels.GestureProgress progress,
                                    FaceCaptureModels.GestureStage expected, String label) {
        if (progress == null || progress.stage != expected) {
            throw new AssertionError(label + ": expected " + expected + " but was "
                + (progress == null ? "null" : progress.stage));
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
