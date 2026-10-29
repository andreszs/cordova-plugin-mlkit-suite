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

        GestureDebug(int phase, int phaseCount, GestureMetric metric,
                     boolean baselineSet, float rawPrimary, float rawSecondary,
                     float baseline, float relative, float target, boolean lessThan,
                     boolean satisfied, long heldMs, long requiredHoldMs,
                     float relativeYaw, float relativePitch, float relativeRoll) {
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

    static final class GestureTracker {
        private static final long RETURN_NEUTRAL_HOLD_MS = 250L;
        private static final long ARM_NEUTRAL_HOLD_MS = 650L;
        private static final long ACTION_HOLD_MS = 160L;
        private static final long PHASE_FEEDBACK_MS = 500L;
        private static final float HORIZONTAL_TURN_DEGREES = 15f;
        private static final float VERTICAL_NOD_DEGREES = 10f;
        private static final float INITIAL_NEUTRAL_YAW_PITCH_DEGREES = 11f;
        private static final float INITIAL_NEUTRAL_ROLL_DEGREES = 9f;
        private final List<Gesture> gestures;
        private final int captureAfterGesture;
        private final long gestureTimeoutMs;
        private final long neutralTimeoutMs;
        private final long retryMessageMs;
        private int index;
        private int phase;
        private long neutralSince;
        private int neutralFrames;
        private long actionSince;
        private int actionFrames;
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
        private long lastNow;
        private float lastYaw;
        private float lastPitch;
        private float lastRoll;
        private float lastLeftEye = -1f;
        private float lastRightEye = -1f;
        private float lastSmile = -1f;
        private float lastMouthOpenRatio;

        GestureTracker(List<Gesture> gestures, int captureAfterGesture, long gestureTimeoutMs,
                       long neutralTimeoutMs, long retryMessageMs) {
            this.gestures = gestures;
            this.captureAfterGesture = captureAfterGesture;
            this.gestureTimeoutMs = gestureTimeoutMs;
            this.neutralTimeoutMs = neutralTimeoutMs;
            this.retryMessageMs = retryMessageMs;
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

        synchronized GestureProgress update(float yaw, float pitch, float roll, float leftEye,
                                             float rightEye, float smile,
                                             float mouthOpenRatio, long now) {
            lastNow = now;
            lastYaw = yaw;
            lastPitch = pitch;
            lastRoll = roll;
            lastLeftEye = leftEye;
            lastRightEye = rightEye;
            lastSmile = smile;
            lastMouthOpenRatio = mouthOpenRatio;
            GestureProgress timed = tick(now);
            if (timed.stage == GestureStage.RETRY || timed.phaseFeedback) return timed;
            if (pausedForCapture) return progress();
            if (isComplete()) return progress();
            float neutralYaw = baselineSet ? yaw - baselineYaw : yaw;
            float neutralPitch = baselineSet ? pitch - baselinePitch : pitch;
            float neutralRoll = baselineSet ? roll - baselineRoll : roll;
            boolean neutral = Math.abs(neutralYaw) < INITIAL_NEUTRAL_YAW_PITCH_DEGREES
                && Math.abs(neutralPitch) < INITIAL_NEUTRAL_YAW_PITCH_DEGREES
                && Math.abs(neutralRoll) < INITIAL_NEUTRAL_ROLL_DEGREES
                && mouthOpenRatio < 0.035f
                && (leftEye < 0f || leftEye > 0.50f)
                && (rightEye < 0f || rightEye > 0.50f)
                && (smile < 0f || smile < 0.48f);
            if (!armed || returning) {
                if (returning && now - stageStartedAt > neutralTimeoutMs) {
                    beginRetry(now);
                    return progress();
                }
                long requiredNeutralHoldMs = returning
                    ? RETURN_NEUTRAL_HOLD_MS : ARM_NEUTRAL_HOLD_MS;
                if (holdNeutral(neutral, now, requiredNeutralHoldMs)) {
                    if (returning) {
                        index++;
                        phase = 0;
                        returning = false;
                        armed = false;
                        stageStartedAt = 0;
                        if (!captureDone && index == captureAfterGesture) {
                            pausedForCapture = true;
                        }
                    } else {
                        baselineYaw = yaw;
                        baselinePitch = pitch;
                        baselineRoll = roll;
                        baselineSet = true;
                        armed = true;
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

            Gesture gesture = gestures.get(index);
            float relativeYaw = yaw - baselineYaw;
            float relativePitch = pitch - baselinePitch;
            boolean condition;
            if (gesture == Gesture.NOD) {
                float angle = relativePitch;
                if (phase == 0) {
                    condition = angle > VERTICAL_NOD_DEGREES;
                    if (holdAction(condition, now)) {
                        beginPhaseFeedback(now, false);
                        clearAction();
                    }
                } else {
                    condition = angle < -VERTICAL_NOD_DEGREES;
                    if (holdAction(condition, now)) beginPhaseFeedback(now, true);
                }
            } else if (gesture == Gesture.SHAKE) {
                float angle = relativeYaw;
                if (phase == 0) {
                    condition = angle < -HORIZONTAL_TURN_DEGREES;
                    if (holdAction(condition, now)) {
                        beginPhaseFeedback(now, false);
                        clearAction();
                    }
                } else {
                    condition = angle > HORIZONTAL_TURN_DEGREES;
                    if (holdAction(condition, now)) beginPhaseFeedback(now, true);
                }
            } else {
                condition = matches(gesture, relativeYaw, relativePitch,
                    leftEye, rightEye, smile, mouthOpenRatio);
                if (holdAction(condition, now)) beginPhaseFeedback(now, true);
            }
            return progress();
        }

        synchronized GestureProgress tick(long now) {
            lastNow = now;
            if (retryUntil != 0) {
                if (now < retryUntil) return progress();
                resetCore();
            }
            if (phaseFeedbackUntil != 0L) {
                if (now < phaseFeedbackUntil) return progress();
                boolean completesGesture = phaseFeedbackCompletesGesture;
                phaseFeedbackUntil = 0L;
                phaseFeedbackCompletesGesture = false;
                clearAction();
                if (completesGesture) completeAction(now);
                else phase = 1;
            }
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
            lastYaw = 0f;
            lastPitch = 0f;
            lastRoll = 0f;
            lastLeftEye = -1f;
            lastRightEye = -1f;
            lastSmile = -1f;
            lastMouthOpenRatio = 0f;
            clearNeutral();
            clearAction();
        }

        private GestureDebug debugSnapshot(Gesture gesture, GestureStage stage) {
            float relativeYaw = baselineSet ? lastYaw - baselineYaw : lastYaw;
            float relativePitch = baselineSet ? lastPitch - baselinePitch : lastPitch;
            float relativeRoll = baselineSet ? lastRoll - baselineRoll : lastRoll;
            int phaseCount = gesture == Gesture.NOD || gesture == Gesture.SHAKE ? 2 : 1;
            if (stage == GestureStage.WAITING_NEUTRAL
                || stage == GestureStage.RETURN_NEUTRAL) {
                boolean neutral = Math.abs(relativeYaw) < INITIAL_NEUTRAL_YAW_PITCH_DEGREES
                    && Math.abs(relativePitch) < INITIAL_NEUTRAL_YAW_PITCH_DEGREES
                    && Math.abs(relativeRoll) < INITIAL_NEUTRAL_ROLL_DEGREES
                    && lastMouthOpenRatio < 0.035f
                    && (lastLeftEye < 0f || lastLeftEye > 0.50f)
                    && (lastRightEye < 0f || lastRightEye > 0.50f)
                    && (lastSmile < 0f || lastSmile < 0.48f);
                long required = stage == GestureStage.RETURN_NEUTRAL
                    ? RETURN_NEUTRAL_HOLD_MS : ARM_NEUTRAL_HOLD_MS;
                return new GestureDebug(phase, phaseCount, GestureMetric.NEUTRAL,
                    baselineSet, 0f, Float.NaN, 0f, 0f, 0f, false, neutral,
                    elapsed(neutralSince), required, relativeYaw, relativePitch, relativeRoll);
            }

            GestureMetric metric = GestureMetric.NONE;
            float rawPrimary = 0f;
            float rawSecondary = Float.NaN;
            float baseline = 0f;
            float relative = 0f;
            float target = 0f;
            boolean lessThan = false;
            boolean satisfied = false;
            switch (gesture) {
                case NOD:
                    metric = GestureMetric.PITCH;
                    rawPrimary = lastPitch;
                    baseline = baselinePitch;
                    relative = relativePitch;
                    target = phase == 0 ? VERTICAL_NOD_DEGREES : -VERTICAL_NOD_DEGREES;
                    lessThan = phase != 0;
                    satisfied = lessThan ? relative < target : relative > target;
                    break;
                case SHAKE:
                    metric = GestureMetric.YAW;
                    rawPrimary = lastYaw;
                    baseline = baselineYaw;
                    relative = relativeYaw;
                    target = phase == 0 ? -HORIZONTAL_TURN_DEGREES : HORIZONTAL_TURN_DEGREES;
                    lessThan = phase == 0;
                    satisfied = lessThan ? relative < target : relative > target;
                    break;
                case LOOK_UP:
                case LOOK_DOWN:
                    metric = GestureMetric.PITCH;
                    rawPrimary = lastPitch;
                    baseline = baselinePitch;
                    relative = relativePitch;
                    target = gesture == Gesture.LOOK_UP ? 15f : -15f;
                    lessThan = gesture == Gesture.LOOK_DOWN;
                    satisfied = lessThan ? relative < target : relative > target;
                    break;
                case OPEN_MOUTH:
                    metric = GestureMetric.MOUTH;
                    rawPrimary = lastMouthOpenRatio;
                    relative = rawPrimary;
                    target = 0.055f;
                    satisfied = relative > target;
                    break;
                case SMILE:
                    metric = GestureMetric.SMILE;
                    rawPrimary = lastSmile;
                    relative = rawPrimary;
                    target = 0.72f;
                    satisfied = relative > target;
                    break;
                case BLINK:
                    metric = GestureMetric.EYES;
                    rawPrimary = lastLeftEye;
                    rawSecondary = lastRightEye;
                    relative = Math.max(rawPrimary, rawSecondary);
                    target = 0.32f;
                    lessThan = true;
                    satisfied = rawPrimary >= 0f && rawSecondary >= 0f
                        && rawPrimary < target && rawSecondary < target;
                    break;
                default:
                    break;
            }
            return new GestureDebug(phase, phaseCount, metric, baselineSet,
                rawPrimary, rawSecondary, baseline, relative, target, lessThan,
                satisfied, elapsed(actionSince), ACTION_HOLD_MS,
                relativeYaw, relativePitch, relativeRoll);
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
            return neutralFrames >= 3 && now - neutralSince >= requiredHoldMs;
        }

        private boolean holdAction(boolean condition, long now) {
            if (!condition) {
                clearAction();
                return false;
            }
            if (actionFrames++ == 0) actionSince = now;
            return actionFrames >= 2 && now - actionSince >= ACTION_HOLD_MS;
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
            if (!completesGesture && stageStartedAt > 0L) {
                stageStartedAt += PHASE_FEEDBACK_MS;
            }
        }

        private static boolean matches(Gesture gesture, float yaw, float pitch,
                                       float leftEye, float rightEye, float smile,
                                       float mouthOpenRatio) {
            switch (gesture) {
                case OPEN_MOUTH: return mouthOpenRatio > 0.055f;
                case BLINK: return leftEye >= 0f && rightEye >= 0f
                    && leftEye < 0.32f && rightEye < 0.32f;
                case SMILE: return smile > 0.72f;
                case LOOK_UP: return pitch > 15f;
                case LOOK_DOWN: return pitch < -15f;
                default: return false;
            }
        }

        private void clearNeutral() {
            neutralSince = 0;
            neutralFrames = 0;
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
