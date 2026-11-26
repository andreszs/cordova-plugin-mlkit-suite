package com.andreszs.plugins.mlkitsuite;

import android.graphics.RectF;

import java.util.List;

final class FaceCaptureModels {
    enum State {
        SEARCHING, MULTIPLE_FACES, MOVE_CLOSER, MOVE_AWAY, CENTER_FACE,
        LOOK_STRAIGHT, OPEN_EYES, NEUTRAL_EXPRESSION, IMPROVE_LIGHTING,
        REDUCE_LIGHTING, HOLD_STILL, DEVICE_TILTED, GESTURE, READY, CAPTURING
    }

    enum Gesture {
        OPEN_MOUTH, BLINK, SMILE, LOOK_UP, LOOK_DOWN, NOD, SHAKE
    }

    enum GestureStage { WAITING_NEUTRAL, PERFORM, RETURN_NEUTRAL, CAPTURE, RETRY, COMPLETE }

    enum GestureMetric { NONE, NEUTRAL, YAW, PITCH, MOUTH, SMILE, EYES }

    static final class GestureDebug {
        final int phase;
        final int phaseCount;
        final GestureMetric metric;
        final boolean baselineSet;
        final float rawPrimary;
        final float rawSecondary;
        final float baseline;
        final float relative;
        final float target;
        final boolean lessThan;
        final boolean satisfied;
        final long heldMs;
        final long requiredHoldMs;
        final float relativeYaw;
        final float relativePitch;
        final float relativeRoll;
        /**
         * Direction of the first accepted swing of a two-phase NOD or SHAKE: +1 when the
         * first excursion went to positive angles (up / left), -1 for negative angles
         * (down / right) and 0 while no side has been accepted yet.
         */
        final int firstSide;

        GestureDebug(int phase, int phaseCount, GestureMetric metric,
                     boolean baselineSet, float rawPrimary, float rawSecondary,
                     float baseline, float relative, float target, boolean lessThan,
                     boolean satisfied, long heldMs, long requiredHoldMs,
                     float relativeYaw, float relativePitch, float relativeRoll,
                     int firstSide) {
            this.phase = phase;
            this.phaseCount = phaseCount;
            this.metric = metric;
            this.baselineSet = baselineSet;
            this.rawPrimary = rawPrimary;
            this.rawSecondary = rawSecondary;
            this.baseline = baseline;
            this.relative = relative;
            this.target = target;
            this.lessThan = lessThan;
            this.satisfied = satisfied;
            this.heldMs = heldMs;
            this.requiredHoldMs = requiredHoldMs;
            this.relativeYaw = relativeYaw;
            this.relativePitch = relativePitch;
            this.relativeRoll = relativeRoll;
            this.firstSide = firstSide;
        }
    }

    static final class GestureProgress {
        final Gesture gesture;
        final GestureStage stage;
        final int index;
        final int count;
        final GestureDebug debug;
        final boolean phaseFeedback;

        GestureProgress(Gesture gesture, GestureStage stage, int index, int count,
                        GestureDebug debug, boolean phaseFeedback) {
            this.gesture = gesture;
            this.stage = stage;
            this.index = index;
            this.count = count;
            this.debug = debug;
            this.phaseFeedback = phaseFeedback;
        }
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
        final float mouthOpenRatio;
        final float brightness;
        final float sharpness;
        final long stableDurationMs;
        final GestureProgress gestureProgress;
        final float centeringHorizontalOffset;
        final float centeringVerticalOffset;
        final boolean centeringAvailable;
        final boolean centeringAccepted;

        Analysis(State state, RectF bounds, float yaw, float pitch, float roll,
                 float leftEye, float rightEye, float smile, float brightness,
                 float sharpness, long stableDurationMs, float mouthOpenRatio,
                 GestureProgress gestureProgress, float centeringHorizontalOffset,
                 float centeringVerticalOffset, boolean centeringAvailable,
                 boolean centeringAccepted) {
            this.state = state;
            this.normalizedBounds = bounds;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.leftEye = leftEye;
            this.rightEye = rightEye;
            this.smile = smile;
            this.mouthOpenRatio = mouthOpenRatio;
            this.brightness = brightness;
            this.sharpness = sharpness;
            this.stableDurationMs = stableDurationMs;
            this.gestureProgress = gestureProgress;
            this.centeringHorizontalOffset = centeringHorizontalOffset;
            this.centeringVerticalOffset = centeringVerticalOffset;
            this.centeringAvailable = centeringAvailable;
            this.centeringAccepted = centeringAccepted;
        }

        Analysis withState(State next, long stableMs) {
            return new Analysis(next, normalizedBounds, yaw, pitch, roll, leftEye, rightEye,
                smile, brightness, sharpness, stableMs, mouthOpenRatio, gestureProgress,
                centeringHorizontalOffset, centeringVerticalOffset, centeringAvailable,
                centeringAccepted);
        }
    }

    /**
     * Liveness gesture state machine.
     *
     * <p>Design notes (v2):
     * <ul>
     *   <li>Head pose is smoothed with an exponential moving average so a single noisy
     *       ML Kit frame can no longer reset an in-progress hold.</li>
     *   <li>Every action uses hysteresis: a stricter threshold to start a hold and a looser
     *       one to keep it.</li>
     *   <li>NOD and SHAKE accept either direction first and are measured as a swing: the
     *       second phase only needs to cross to the other side of the neutral pose while
     *       covering enough total range from the first peak.</li>
     *   <li>Accepting the first half of a NOD/SHAKE no longer freezes input, so a natural,
     *       continuous head movement is recognized in one go.</li>
     *   <li>The neutral pose required before a gesture only checks what matters for that
     *       gesture; the neutral pose required before the photo also enforces the absolute
     *       pose limits the still validator applies, avoiding late full retries.</li>
     *   <li>The baseline is the mean of the neutral frames instead of a single frame.</li>
     * </ul>
     */
    static final class GestureTracker {
        static final long ARM_NEUTRAL_HOLD_MS = 450L;
        static final long RETURN_NEUTRAL_HOLD_MS = 250L;
        static final long ACTION_HOLD_MS = 100L;
        static final long SWING_HOLD_MS = 60L;
        static final long BLINK_HOLD_MS = 0L;
        static final long PHASE_FEEDBACK_MS = 350L;
        static final int MIN_NEUTRAL_FRAMES = 3;
        static final int MIN_ACTION_FRAMES = 2;

        /** Weight of the newest pose sample in the moving average. */
        static final float POSE_SMOOTHING = 0.6f;
        /** Fraction of the entry threshold that keeps an action hold alive. */
        static final float HOLD_HYSTERESIS = 0.7f;

        static final float NEUTRAL_YAW_PITCH_DEGREES = 11f;
        static final float NEUTRAL_ROLL_DEGREES = 9f;
        /** Absolute pose accepted for the photo; the still validator allows 13/13/11. */
        static final float CAPTURE_YAW_PITCH_DEGREES = 11f;
        static final float CAPTURE_ROLL_DEGREES = 9f;

        static final float NOD_DEGREES = 8f;
        static final float SHAKE_DEGREES = 12f;
        /** Second NOD/SHAKE phase: minimum excursion past neutral on the opposite side. */
        static final float SWING_OPPOSITE_FACTOR = 0.55f;
        /** Second NOD/SHAKE phase: minimum total travel from the first peak. */
        static final float SWING_RANGE_FACTOR = 1.7f;
        static final float LOOK_UP_DEGREES = 12f;
        static final float LOOK_DOWN_DEGREES = 10f;

        static final float MOUTH_OPEN_RATIO = 0.05f;
        static final float MOUTH_CLOSED_RATIO = 0.035f;
        static final float MOUTH_CAPTURE_RATIO = 0.045f;
        static final float SMILE_ON = 0.65f;
        static final float SMILE_OFF = 0.45f;
        static final float SMILE_CAPTURE = 0.55f;
        static final float EYES_CLOSED = 0.35f;
        static final float EYES_OPEN = 0.5f;
        static final float EYES_CAPTURE = 0.4f;

        private final List<Gesture> gestures;
        private final int captureAfterGesture;
        private final long gestureTimeoutMs;
        private final long neutralTimeoutMs;
        private final long retryMessageMs;
        private final boolean enforceCapturePose;
        private int index;
        private int phase;
        private int firstSide;
        private float swingPeak;
        private long neutralSince;
        private int neutralFrames;
        private float neutralSumYaw;
        private float neutralSumPitch;
        private float neutralSumRoll;
        private long actionSince;
        private int actionFrames;
        private int actionSide;
        private boolean armed;
        private boolean returning;
        private boolean pausedForCapture;
        private boolean captureDone;
        private long stageStartedAt;
        private long retryUntil;
        private long phaseFeedbackUntil;
        private boolean phaseFeedbackCompletesGesture;
        private boolean baselineSet;
        private float baselineYaw;
        private float baselinePitch;
        private float baselineRoll;
        private boolean smoothingReady;
        private long lastNow;
        private float lastYaw;
        private float lastPitch;
        private float lastRoll;
        private float lastLeftEye = -1f;
        private float lastRightEye = -1f;
        private float lastSmile = -1f;
        private float lastMouthOpenRatio;
        private boolean lastCaptureAllowed = true;

        GestureTracker(List<Gesture> gestures, int captureAfterGesture, long gestureTimeoutMs,
                       long neutralTimeoutMs, long retryMessageMs) {
            this(gestures, captureAfterGesture, gestureTimeoutMs, neutralTimeoutMs,
                retryMessageMs, true);
        }

        GestureTracker(List<Gesture> gestures, int captureAfterGesture, long gestureTimeoutMs,
                       long neutralTimeoutMs, long retryMessageMs,
                       boolean enforceCapturePose) {
            this.gestures = gestures;
            this.captureAfterGesture = captureAfterGesture;
            this.gestureTimeoutMs = gestureTimeoutMs;
            this.neutralTimeoutMs = neutralTimeoutMs;
            this.retryMessageMs = retryMessageMs;
            this.enforceCapturePose = enforceCapturePose;
        }

        synchronized boolean isComplete() {
            return index >= gestures.size() && !pausedForCapture && retryUntil == 0;
        }

        synchronized boolean hasStarted() {
            return index > 0 || armed || returning || pausedForCapture || captureDone;
        }

        synchronized GestureProgress progress() {
            if (retryUntil != 0) return new GestureProgress(null, GestureStage.RETRY,
                0, gestures.size(), null, false);
            if (pausedForCapture) return new GestureProgress(
                index < gestures.size() ? gestures.get(index) : null,
                GestureStage.CAPTURE, index, gestures.size(), null, false);
            if (isComplete()) return new GestureProgress(null, GestureStage.COMPLETE,
                gestures.size(), gestures.size(), null, false);
            GestureStage stage = returning ? GestureStage.RETURN_NEUTRAL
                : (armed ? GestureStage.PERFORM : GestureStage.WAITING_NEUTRAL);
            Gesture gesture = gestures.get(index);
            return new GestureProgress(gesture, stage, index, gestures.size(),
                debugSnapshot(gesture, stage), phaseFeedbackUntil != 0L);
        }

        GestureProgress update(float yaw, float pitch, float roll, float leftEye,
                               float rightEye, float smile, float mouthOpenRatio, long now) {
            return update(yaw, pitch, roll, leftEye, rightEye, smile, mouthOpenRatio, now, true);
        }

        /**
         * @param captureAllowed false while an external condition (for example, the device
         *                       not being held upright) must block the neutral pose that
         *                       precedes the photo. It never interrupts a gesture in progress.
         */
        synchronized GestureProgress update(float yaw, float pitch, float roll, float leftEye,
                                             float rightEye, float smile,
                                             float mouthOpenRatio, long now,
                                             boolean captureAllowed) {
            lastNow = now;
            if (!smoothingReady) {
                lastYaw = yaw;
                lastPitch = pitch;
                lastRoll = roll;
                smoothingReady = true;
            } else {
                lastYaw += POSE_SMOOTHING * (yaw - lastYaw);
                lastPitch += POSE_SMOOTHING * (pitch - lastPitch);
                lastRoll += POSE_SMOOTHING * (roll - lastRoll);
            }
            lastLeftEye = leftEye;
            lastRightEye = rightEye;
            lastSmile = smile;
            lastMouthOpenRatio = mouthOpenRatio;
            lastCaptureAllowed = captureAllowed;

            GestureProgress timed = tick(now);
            if (timed.stage == GestureStage.RETRY) return timed;
            // Only the feedback that completes a gesture pauses input; the feedback shown
            // after the first half of a NOD/SHAKE is purely visual.
            if (phaseFeedbackUntil != 0L && phaseFeedbackCompletesGesture) return timed;
            if (pausedForCapture) return progress();
            if (isComplete()) return progress();

            float relativeYaw = relative(lastYaw, baselineYaw);
            float relativePitch = relative(lastPitch, baselinePitch);
            float relativeRoll = relative(lastRoll, baselineRoll);
            Gesture gesture = gestures.get(index);

            if (!armed || returning) {
                if (returning && now - stageStartedAt > neutralTimeoutMs) {
                    beginRetry(now);
                    return progress();
                }
                boolean neutral = returning
                    ? captureNeutral(gesture, relativeYaw, relativePitch, relativeRoll)
                        && captureAllowed
                    : armNeutral(gesture, relativeYaw, relativePitch, relativeRoll);
                long requiredNeutralHoldMs = returning
                    ? RETURN_NEUTRAL_HOLD_MS : ARM_NEUTRAL_HOLD_MS;
                if (holdNeutral(neutral, now, requiredNeutralHoldMs)) {
                    if (returning) {
                        index++;
                        phase = 0;
                        firstSide = 0;
                        returning = false;
                        armed = false;
                        stageStartedAt = 0;
                        if (!captureDone && index == captureAfterGesture) {
                            pausedForCapture = true;
                        }
                    } else {
                        baselineYaw = neutralSumYaw / neutralFrames;
                        baselinePitch = neutralSumPitch / neutralFrames;
                        baselineRoll = neutralSumRoll / neutralFrames;
                        baselineSet = true;
                        armed = true;
                        phase = 0;
                        firstSide = 0;
                        stageStartedAt = now;
                    }
                    clearNeutral();
                    clearAction();
                }
                return progress();
            }

            if (now - stageStartedAt > gestureTimeoutMs) {
                beginRetry(now);
                return progress();
            }

            if (gesture == Gesture.NOD || gesture == Gesture.SHAKE) {
                performSwing(gesture == Gesture.NOD ? relativePitch : relativeYaw,
                    gesture == Gesture.NOD ? NOD_DEGREES : SHAKE_DEGREES, now);
            } else {
                boolean holding = actionFrames > 0;
                boolean condition = holding
                    ? sustains(gesture, relativePitch, leftEye, rightEye, smile, mouthOpenRatio)
                    : starts(gesture, relativePitch, leftEye, rightEye, smile, mouthOpenRatio);
                long hold = gesture == Gesture.BLINK ? BLINK_HOLD_MS : ACTION_HOLD_MS;
                if (holdAction(condition, now, hold)) beginPhaseFeedback(now, true);
            }
            return progress();
        }

        private void performSwing(float angle, float threshold, long now) {
            if (phase == 0) {
                if (actionFrames == 0) actionSide = angle >= 0f ? 1 : -1;
                boolean condition = actionFrames == 0
                    ? Math.abs(angle) > threshold
                    : actionSide * angle > threshold * HOLD_HYSTERESIS;
                if (actionFrames > 0 && actionSide * angle > actionSide * swingPeak) {
                    swingPeak = angle;
                } else if (actionFrames == 0) {
                    swingPeak = angle;
                }
                if (holdAction(condition, now, SWING_HOLD_MS)) {
                    firstSide = actionSide;
                    phase = 1;
                    clearAction();
                    beginPhaseFeedback(now, false);
                }
                return;
            }
            // Keep following the first peak while the head is still travelling that way.
            if (firstSide * angle > firstSide * swingPeak) swingPeak = angle;
            float opposite = -firstSide * angle;
            float travel = Math.abs(swingPeak - angle);
            boolean holding = actionFrames > 0;
            float factor = holding ? HOLD_HYSTERESIS : 1f;
            boolean condition = opposite > threshold * SWING_OPPOSITE_FACTOR * factor
                && travel > threshold * SWING_RANGE_FACTOR * factor;
            if (holdAction(condition, now, SWING_HOLD_MS)) beginPhaseFeedback(now, true);
        }

        synchronized GestureProgress tick(long now) {
            lastNow = now;
            if (retryUntil != 0) {
                if (now < retryUntil) return progress();
                resetCore();
            }
            if (phaseFeedbackUntil != 0L && now >= phaseFeedbackUntil) {
                boolean completesGesture = phaseFeedbackCompletesGesture;
                phaseFeedbackUntil = 0L;
                phaseFeedbackCompletesGesture = false;
                if (completesGesture) completeAction(now);
            }
            if (phaseFeedbackUntil != 0L && phaseFeedbackCompletesGesture) return progress();
            if (returning && now - stageStartedAt > neutralTimeoutMs) beginRetry(now);
            else if (armed && !returning && now - stageStartedAt > gestureTimeoutMs) beginRetry(now);
            return progress();
        }

        synchronized void resumeAfterCapture(long now) {
            lastNow = now;
            captureDone = true;
            pausedForCapture = false;
            armed = false;
            returning = false;
            phase = 0;
            firstSide = 0;
            phaseFeedbackUntil = 0L;
            phaseFeedbackCompletesGesture = false;
            stageStartedAt = 0;
            clearNeutral();
            clearAction();
        }

        synchronized void resumeAfterFaceLoss(long durationMs) {
            if (durationMs <= 0L) return;
            if (stageStartedAt > 0L) stageStartedAt += durationMs;
            if (neutralSince > 0L) neutralSince += durationMs;
            if (actionSince > 0L) actionSince += durationMs;
            if (phaseFeedbackUntil > 0L) phaseFeedbackUntil += durationMs;
        }

        synchronized void retry(long now) {
            lastNow = now;
            beginRetry(now);
        }

        synchronized void reset() {
            retryUntil = 0;
            resetCore();
        }

        private void resetCore() {
            index = 0;
            phase = 0;
            firstSide = 0;
            swingPeak = 0f;
            phaseFeedbackUntil = 0L;
            phaseFeedbackCompletesGesture = false;
            armed = false;
            returning = false;
            pausedForCapture = false;
            captureDone = false;
            stageStartedAt = 0;
            retryUntil = 0;
            baselineSet = false;
            baselineYaw = 0f;
            baselinePitch = 0f;
            baselineRoll = 0f;
            smoothingReady = false;
            lastYaw = 0f;
            lastPitch = 0f;
            lastRoll = 0f;
            lastLeftEye = -1f;
            lastRightEye = -1f;
            lastSmile = -1f;
            lastMouthOpenRatio = 0f;
            lastCaptureAllowed = true;
            clearNeutral();
            clearAction();
        }

        private float relative(float value, float baseline) {
            return baselineSet ? value - baseline : value;
        }

        private static boolean poseNeutral(float yaw, float pitch, float roll) {
            return Math.abs(yaw) < NEUTRAL_YAW_PITCH_DEGREES
                && Math.abs(pitch) < NEUTRAL_YAW_PITCH_DEGREES
                && Math.abs(roll) < NEUTRAL_ROLL_DEGREES;
        }

        /** Neutral pose required before a gesture starts: pose plus the gesture's own rest state. */
        private boolean armNeutral(Gesture gesture, float yaw, float pitch, float roll) {
            if (!poseNeutral(yaw, pitch, roll)) return false;
            switch (gesture) {
                case OPEN_MOUTH: return lastMouthOpenRatio < MOUTH_CLOSED_RATIO;
                case SMILE: return lastSmile < 0f || lastSmile < SMILE_OFF;
                case BLINK: return (lastLeftEye < 0f || lastLeftEye > EYES_OPEN)
                    && (lastRightEye < 0f || lastRightEye > EYES_OPEN);
                default: return true;
            }
        }

        /** Neutral pose required before the photo; mirrors the still validator limits. */
        private boolean captureNeutral(Gesture gesture, float yaw, float pitch, float roll) {
            if (!poseNeutral(yaw, pitch, roll)) return false;
            if (enforceCapturePose && (Math.abs(lastYaw) >= CAPTURE_YAW_PITCH_DEGREES
                || Math.abs(lastPitch) >= CAPTURE_YAW_PITCH_DEGREES
                || Math.abs(lastRoll) >= CAPTURE_ROLL_DEGREES)) return false;
            if (lastMouthOpenRatio >= MOUTH_CAPTURE_RATIO) return false;
            if (lastLeftEye >= 0f && lastLeftEye < EYES_CAPTURE) return false;
            if (lastRightEye >= 0f && lastRightEye < EYES_CAPTURE) return false;
            return gesture != Gesture.SMILE || lastSmile < 0f || lastSmile < SMILE_CAPTURE;
        }

        private static boolean starts(Gesture gesture, float pitch, float leftEye,
                                      float rightEye, float smile, float mouthOpenRatio) {
            switch (gesture) {
                case OPEN_MOUTH: return mouthOpenRatio > MOUTH_OPEN_RATIO;
                case BLINK: return leftEye >= 0f && rightEye >= 0f
                    && leftEye < EYES_CLOSED && rightEye < EYES_CLOSED;
                case SMILE: return smile > SMILE_ON;
                case LOOK_UP: return pitch > LOOK_UP_DEGREES;
                case LOOK_DOWN: return pitch < -LOOK_DOWN_DEGREES;
                default: return false;
            }
        }

        private static boolean sustains(Gesture gesture, float pitch, float leftEye,
                                        float rightEye, float smile, float mouthOpenRatio) {
            switch (gesture) {
                case OPEN_MOUTH: return mouthOpenRatio > MOUTH_CLOSED_RATIO;
                case BLINK: return leftEye >= 0f && rightEye >= 0f
                    && leftEye < EYES_OPEN && rightEye < EYES_OPEN;
                case SMILE: return smile > SMILE_ON * HOLD_HYSTERESIS + 0.05f;
                case LOOK_UP: return pitch > LOOK_UP_DEGREES * HOLD_HYSTERESIS;
                case LOOK_DOWN: return pitch < -LOOK_DOWN_DEGREES * HOLD_HYSTERESIS;
                default: return false;
            }
        }

        private GestureDebug debugSnapshot(Gesture gesture, GestureStage stage) {
            float relativeYaw = relative(lastYaw, baselineYaw);
            float relativePitch = relative(lastPitch, baselinePitch);
            float relativeRoll = relative(lastRoll, baselineRoll);
            int phaseCount = gesture == Gesture.NOD || gesture == Gesture.SHAKE ? 2 : 1;
            // While the first-half feedback is visible keep reporting phase 0 so the overlay
            // marks that half as accepted, even though detection already moved on.
            int reportedPhase = phaseFeedbackUntil != 0L && !phaseFeedbackCompletesGesture
                ? Math.max(0, phase - 1) : phase;
            if (stage == GestureStage.WAITING_NEUTRAL
                || stage == GestureStage.RETURN_NEUTRAL) {
                boolean neutral = stage == GestureStage.RETURN_NEUTRAL
                    ? captureNeutral(gesture, relativeYaw, relativePitch, relativeRoll)
                        && lastCaptureAllowed
                    : armNeutral(gesture, relativeYaw, relativePitch, relativeRoll);
                long required = stage == GestureStage.RETURN_NEUTRAL
                    ? RETURN_NEUTRAL_HOLD_MS : ARM_NEUTRAL_HOLD_MS;
                return new GestureDebug(reportedPhase, phaseCount, GestureMetric.NEUTRAL,
                    baselineSet, 0f, Float.NaN, 0f, 0f, 0f, false, neutral,
                    elapsed(neutralSince), required, relativeYaw, relativePitch, relativeRoll,
                    firstSide);
            }

            GestureMetric metric = GestureMetric.NONE;
            float rawPrimary = 0f;
            float rawSecondary = Float.NaN;
            float baseline = 0f;
            float relative = 0f;
            float target = 0f;
            boolean lessThan = false;
            boolean satisfied = false;
            long requiredHold = ACTION_HOLD_MS;
            switch (gesture) {
                case NOD:
                case SHAKE: {
                    boolean nod = gesture == Gesture.NOD;
                    float threshold = nod ? NOD_DEGREES : SHAKE_DEGREES;
                    metric = nod ? GestureMetric.PITCH : GestureMetric.YAW;
                    rawPrimary = nod ? lastPitch : lastYaw;
                    baseline = nod ? baselinePitch : baselineYaw;
                    relative = nod ? relativePitch : relativeYaw;
                    requiredHold = SWING_HOLD_MS;
                    if (firstSide == 0) {
                        int side = relative >= 0f ? 1 : -1;
                        target = side * threshold;
                        lessThan = side < 0;
                        satisfied = Math.abs(relative) > threshold;
                    } else {
                        target = -firstSide * threshold * SWING_OPPOSITE_FACTOR;
                        lessThan = firstSide > 0;
                        satisfied = -firstSide * relative > threshold * SWING_OPPOSITE_FACTOR
                            && Math.abs(swingPeak - relative) > threshold * SWING_RANGE_FACTOR;
                    }
                    break;
                }
                case LOOK_UP:
                case LOOK_DOWN:
                    metric = GestureMetric.PITCH;
                    rawPrimary = lastPitch;
                    baseline = baselinePitch;
                    relative = relativePitch;
                    target = gesture == Gesture.LOOK_UP ? LOOK_UP_DEGREES : -LOOK_DOWN_DEGREES;
                    lessThan = gesture == Gesture.LOOK_DOWN;
                    satisfied = lessThan ? relative < target : relative > target;
                    break;
                case OPEN_MOUTH:
                    metric = GestureMetric.MOUTH;
                    rawPrimary = lastMouthOpenRatio;
                    relative = rawPrimary;
                    target = MOUTH_OPEN_RATIO;
                    satisfied = relative > target;
                    break;
                case SMILE:
                    metric = GestureMetric.SMILE;
                    rawPrimary = lastSmile;
                    relative = rawPrimary;
                    target = SMILE_ON;
                    satisfied = relative > target;
                    break;
                case BLINK:
                    metric = GestureMetric.EYES;
                    rawPrimary = lastLeftEye;
                    rawSecondary = lastRightEye;
                    relative = Math.max(rawPrimary, rawSecondary);
                    target = EYES_CLOSED;
                    lessThan = true;
                    requiredHold = BLINK_HOLD_MS;
                    satisfied = rawPrimary >= 0f && rawSecondary >= 0f
                        && rawPrimary < target && rawSecondary < target;
                    break;
                default:
                    break;
            }
            return new GestureDebug(reportedPhase, phaseCount, metric, baselineSet,
                rawPrimary, rawSecondary, baseline, relative, target, lessThan,
                satisfied, elapsed(actionSince), requiredHold,
                relativeYaw, relativePitch, relativeRoll, firstSide);
        }

        private long elapsed(long since) {
            return since <= 0 || lastNow <= since ? 0 : lastNow - since;
        }

        private void beginRetry(long now) {
            resetCore();
            retryUntil = now + retryMessageMs;
        }

        private boolean holdNeutral(boolean condition, long now, long requiredHoldMs) {
            if (!condition) {
                clearNeutral();
                return false;
            }
            if (neutralFrames++ == 0) neutralSince = now;
            neutralSumYaw += lastYaw;
            neutralSumPitch += lastPitch;
            neutralSumRoll += lastRoll;
            return neutralFrames >= MIN_NEUTRAL_FRAMES && now - neutralSince >= requiredHoldMs;
        }

        private boolean holdAction(boolean condition, long now, long requiredHoldMs) {
            if (!condition) {
                clearAction();
                return false;
            }
            if (actionFrames++ == 0) actionSince = now;
            return actionFrames >= MIN_ACTION_FRAMES && now - actionSince >= requiredHoldMs;
        }

        private void completeAction(long now) {
            returning = true;
            phaseFeedbackUntil = 0L;
            phaseFeedbackCompletesGesture = false;
            stageStartedAt = now;
            clearNeutral();
            clearAction();
        }

        private void beginPhaseFeedback(long now, boolean completesGesture) {
            phaseFeedbackUntil = now + PHASE_FEEDBACK_MS;
            phaseFeedbackCompletesGesture = completesGesture;
        }

        private void clearNeutral() {
            neutralSince = 0;
            neutralFrames = 0;
            neutralSumYaw = 0f;
            neutralSumPitch = 0f;
            neutralSumRoll = 0f;
        }

        private void clearAction() {
            actionSince = 0;
            actionFrames = 0;
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
