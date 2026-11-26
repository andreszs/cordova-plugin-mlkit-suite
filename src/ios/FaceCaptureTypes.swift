import CoreGraphics
import CoreFoundation
import Foundation

enum MlKitSuiteError: Error {
    case invalidArgument(String)
    case cameraUnavailable(String, Bool)
    case captureFailed(String, Bool)
    case processingFailed(String, Bool)
    case timeout(String)

    var payload: [String: Any] {
        switch self {
        case .invalidArgument(let message):
            return ["code": "INVALID_ARGUMENT", "message": message, "recoverable": false]
        case .cameraUnavailable(let message, let recoverable):
            return ["code": "CAMERA_UNAVAILABLE", "message": message, "recoverable": recoverable]
        case .captureFailed(let message, let recoverable):
            return ["code": "CAPTURE_FAILED", "message": message, "recoverable": recoverable]
        case .processingFailed(let message, let recoverable):
            return ["code": "PROCESSING_FAILED", "message": message, "recoverable": recoverable]
        case .timeout(let message):
            return ["code": "TIMEOUT", "message": message, "recoverable": true]
        }
    }
}

struct FaceCaptureChecks {
    let singleFace: Bool
    let faceSize: Bool
    let centering: Bool
    let pose: Bool
    let eyesOpen: Bool
    let neutralExpression: Bool
    let stability: Bool
    let sharpness: Bool
    let brightness: Bool

    init(dictionary: [String: Any]?) throws {
        let source = dictionary ?? [:]
        let known = Set([
            "singleFace", "faceSize", "centering", "pose", "eyesOpen",
            "neutralExpression", "stability", "sharpness", "brightness"
        ])
        for (key, value) in source {
            guard known.contains(key) else {
                throw MlKitSuiteError.invalidArgument("Unknown face capture check: \(key).")
            }
            guard let number = value as? NSNumber,
                  CFGetTypeID(number) == CFBooleanGetTypeID() else {
                throw MlKitSuiteError.invalidArgument("checks.\(key) must be boolean.")
            }
        }
        func bool(_ key: String, _ fallback: Bool) -> Bool {
            guard let number = source[key] as? NSNumber,
                  CFGetTypeID(number) == CFBooleanGetTypeID() else { return fallback }
            return number.boolValue
        }
        singleFace = bool("singleFace", true)
        faceSize = bool("faceSize", true)
        centering = bool("centering", true)
        pose = bool("pose", true)
        eyesOpen = bool("eyesOpen", false)
        neutralExpression = bool("neutralExpression", false)
        stability = bool("stability", true)
        sharpness = bool("sharpness", true)
        brightness = bool("brightness", true)
    }

    var dictionary: [String: Any] {
        return [
            "singleFace": singleFace,
            "faceSize": faceSize,
            "centering": centering,
            "pose": pose,
            "eyesOpen": eyesOpen,
            "neutralExpression": neutralExpression,
            "stability": stability,
            "sharpness": sharpness,
            "brightness": brightness
        ]
    }
}

enum FaceGesture: String {
    case openMouth = "OPEN_MOUTH"
    case blink = "BLINK"
    case smile = "SMILE"
    case lookUp = "LOOK_UP"
    case lookDown = "LOOK_DOWN"
    case nod = "NOD"
    case shake = "SHAKE"
}

enum FaceGestureStage { case waitingNeutral, perform, returnNeutral, capture, retry, complete }

enum FaceGestureMetric { case none, neutral, yaw, pitch, mouth, smile, eyes }

struct FaceGestureDebug {
    let phase: Int
    let phaseCount: Int
    let metric: FaceGestureMetric
    let baselineSet: Bool
    let rawPrimary: CGFloat
    let rawSecondary: CGFloat?
    let baseline: CGFloat
    let relative: CGFloat
    let target: CGFloat
    let lessThan: Bool
    let satisfied: Bool
    let heldMs: Int
    let requiredHoldMs: Int
    let relativeYaw: CGFloat
    let relativePitch: CGFloat
    let relativeRoll: CGFloat
    /// Side accepted for the first half of NOD/SHAKE: +1, -1, or 0 while none was accepted.
    let firstSide: Int
}

struct FaceGestureProgress {
    let gesture: FaceGesture?
    let stage: FaceGestureStage
    let index: Int
    let count: Int
    let debug: FaceGestureDebug?
    let phaseFeedback: Bool
}

/// Liveness gesture state machine. Equivalent 1:1 to Android's
/// `FaceCaptureModels.GestureTracker`:
/// - Head pose is smoothed with an exponential moving average so a single noisy
///   ML Kit frame can no longer reset an in-progress hold.
/// - Every action uses hysteresis: a stricter threshold to start a hold and a looser
///   one to keep it.
/// - NOD and SHAKE accept either direction first and are measured as a swing: the
///   second phase only needs to cross to the other side of the neutral pose while
///   covering enough total range from the first peak.
/// - Accepting the first half of a NOD/SHAKE no longer freezes input.
/// - The neutral pose required before a gesture only checks what matters for that
///   gesture; the neutral pose required before the photo also enforces the absolute
///   pose limits the still validator applies.
/// - The baseline is the mean of the neutral frames instead of a single frame.
final class FaceGestureTracker {
    static let armNeutralHold: CFTimeInterval = 0.45
    static let returnNeutralHold: CFTimeInterval = 0.25
    static let actionHold: CFTimeInterval = 0.10
    static let swingHold: CFTimeInterval = 0.06
    static let blinkHold: CFTimeInterval = 0
    static let phaseFeedbackDuration: CFTimeInterval = 0.35
    static let minNeutralFrames = 3
    static let minActionFrames = 2

    /// Weight of the newest pose sample in the moving average.
    static let poseSmoothing: CGFloat = 0.6
    /// Fraction of the entry threshold that keeps an action hold alive.
    static let holdHysteresis: CGFloat = 0.7

    static let neutralYawPitchDegrees: CGFloat = 11
    static let neutralRollDegrees: CGFloat = 9
    /// Absolute pose accepted for the photo; the still validator allows 13/13/11.
    static let captureYawPitchDegrees: CGFloat = 11
    static let captureRollDegrees: CGFloat = 9

    static let nodDegrees: CGFloat = 8
    static let shakeDegrees: CGFloat = 12
    /// Second NOD/SHAKE phase: minimum excursion past neutral on the opposite side.
    static let swingOppositeFactor: CGFloat = 0.55
    /// Second NOD/SHAKE phase: minimum total travel from the first peak.
    static let swingRangeFactor: CGFloat = 1.7
    static let lookUpDegrees: CGFloat = 12
    static let lookDownDegrees: CGFloat = 10

    static let mouthOpenRatio: CGFloat = 0.05
    static let mouthClosedRatio: CGFloat = 0.035
    static let mouthCaptureRatio: CGFloat = 0.045
    static let smileOn: CGFloat = 0.65
    static let smileOff: CGFloat = 0.45
    static let smileCapture: CGFloat = 0.55
    static let eyesClosed: CGFloat = 0.35
    static let eyesOpen: CGFloat = 0.5
    static let eyesCapture: CGFloat = 0.4

    private let gestures: [FaceGesture]
    private let captureAfterGesture: Int
    private let gestureTimeout: CFTimeInterval
    private let neutralTimeout: CFTimeInterval
    private let retryMessageDuration: CFTimeInterval
    private let enforceCapturePose: Bool
    private var index = 0
    private var phase = 0
    private var firstSide = 0
    private var swingPeak: CGFloat = 0
    private var neutralSince: CFTimeInterval = 0
    private var neutralFrames = 0
    private var neutralSumYaw: CGFloat = 0
    private var neutralSumPitch: CGFloat = 0
    private var neutralSumRoll: CGFloat = 0
    private var actionSince: CFTimeInterval = 0
    private var actionFrames = 0
    private var actionSide = 0
    private var armed = false
    private var returning = false
    private var pausedForCapture = false
    private var captureDone = false
    private var stageStartedAt: CFTimeInterval = 0
    private var retryUntil: CFTimeInterval = 0
    private var phaseFeedbackUntil: CFTimeInterval = 0
    private var phaseFeedbackCompletesGesture = false
    private var baselineSet = false
    private var baselineYaw: CGFloat = 0
    private var baselinePitch: CGFloat = 0
    private var baselineRoll: CGFloat = 0
    private var smoothingReady = false
    private var lastNow: CFTimeInterval = 0
    private var lastYaw: CGFloat = 0
    private var lastPitch: CGFloat = 0
    private var lastRoll: CGFloat = 0
    private var lastLeftEye: CGFloat?
    private var lastRightEye: CGFloat?
    private var lastSmile: CGFloat?
    private var lastMouthOpenRatio: CGFloat = 0
    private var lastCaptureAllowed = true

    init(gestures: [FaceGesture], captureAfterGesture: Int, gestureTimeoutMs: Int,
         neutralTimeoutMs: Int, retryMessageMs: Int, enforceCapturePose: Bool = true) {
        self.gestures = gestures
        self.captureAfterGesture = captureAfterGesture
        gestureTimeout = Double(gestureTimeoutMs) / 1000
        neutralTimeout = Double(neutralTimeoutMs) / 1000
        retryMessageDuration = Double(retryMessageMs) / 1000
        self.enforceCapturePose = enforceCapturePose
    }

    var isComplete: Bool { index >= gestures.count && !pausedForCapture && retryUntil == 0 }
    var hasStarted: Bool { index > 0 || armed || returning || pausedForCapture || captureDone }

    var progress: FaceGestureProgress {
        if retryUntil != 0 {
            return FaceGestureProgress(gesture: nil, stage: .retry, index: 0,
                                       count: gestures.count, debug: nil,
                                       phaseFeedback: false)
        }
        if pausedForCapture {
            return FaceGestureProgress(gesture: index < gestures.count ? gestures[index] : nil,
                                       stage: .capture, index: index, count: gestures.count,
                                       debug: nil, phaseFeedback: false)
        }
        if isComplete {
            return FaceGestureProgress(gesture: nil, stage: .complete,
                                       index: gestures.count, count: gestures.count, debug: nil,
                                       phaseFeedback: false)
        }
        let stage: FaceGestureStage = returning ? .returnNeutral
            : (armed ? .perform : .waitingNeutral)
        let gesture = gestures[index]
        return FaceGestureProgress(gesture: gesture, stage: stage,
                                   index: index, count: gestures.count,
                                   debug: debugSnapshot(gesture: gesture, stage: stage),
                                   phaseFeedback: phaseFeedbackUntil != 0)
    }

    /// - Parameter captureAllowed: false while an external condition (for example, the
    ///   device not being held upright) must block the neutral pose that precedes the
    ///   photo. It never interrupts a gesture in progress.
    func update(yaw: CGFloat, pitch: CGFloat, roll: CGFloat, leftEye: CGFloat?,
                rightEye: CGFloat?, smile: CGFloat?, mouthOpenRatio: CGFloat,
                now: CFTimeInterval, captureAllowed: Bool = true) -> FaceGestureProgress {
        lastNow = now
        if !smoothingReady {
            lastYaw = yaw
            lastPitch = pitch
            lastRoll = roll
            smoothingReady = true
        } else {
            lastYaw += Self.poseSmoothing * (yaw - lastYaw)
            lastPitch += Self.poseSmoothing * (pitch - lastPitch)
            lastRoll += Self.poseSmoothing * (roll - lastRoll)
        }
        lastLeftEye = leftEye
        lastRightEye = rightEye
        lastSmile = smile
        lastMouthOpenRatio = mouthOpenRatio
        lastCaptureAllowed = captureAllowed

        let timed = tick(now: now)
        if timed.stage == .retry { return timed }
        // Only the feedback that completes a gesture pauses input; the feedback shown
        // after the first half of a NOD/SHAKE is purely visual.
        if phaseFeedbackUntil != 0 && phaseFeedbackCompletesGesture { return timed }
        if pausedForCapture { return progress }
        guard !isComplete else { return progress }

        let relativeYaw = relative(lastYaw, baselineYaw)
        let relativePitch = relative(lastPitch, baselinePitch)
        let relativeRoll = relative(lastRoll, baselineRoll)
        let gesture = gestures[index]

        if !armed || returning {
            if returning && now - stageStartedAt > neutralTimeout {
                beginRetry(now: now)
                return progress
            }
            let neutral = returning
                ? captureNeutral(gesture, yaw: relativeYaw, pitch: relativePitch,
                                 roll: relativeRoll) && captureAllowed
                : armNeutral(gesture, yaw: relativeYaw, pitch: relativePitch,
                             roll: relativeRoll)
            let requiredNeutralHold = returning
                ? Self.returnNeutralHold : Self.armNeutralHold
            if holdNeutral(neutral, now: now, requiredHold: requiredNeutralHold) {
                if returning {
                    index += 1
                    phase = 0
                    firstSide = 0
                    returning = false
                    armed = false
                    stageStartedAt = 0
                    if !captureDone && index == captureAfterGesture { pausedForCapture = true }
                } else {
                    let frames = CGFloat(neutralFrames)
                    baselineYaw = neutralSumYaw / frames
                    baselinePitch = neutralSumPitch / frames
                    baselineRoll = neutralSumRoll / frames
                    baselineSet = true
                    armed = true
                    phase = 0
                    firstSide = 0
                    stageStartedAt = now
                }
                clearNeutral()
                clearAction()
            }
            return progress
        }

        if now - stageStartedAt > gestureTimeout {
            beginRetry(now: now)
            return progress
        }

        if gesture == .nod || gesture == .shake {
            performSwing(angle: gesture == .nod ? relativePitch : relativeYaw,
                         threshold: gesture == .nod ? Self.nodDegrees : Self.shakeDegrees,
                         now: now)
        } else {
            let holding = actionFrames > 0
            let condition = holding
                ? sustains(gesture, pitch: relativePitch, leftEye: leftEye,
                           rightEye: rightEye, smile: smile, mouthOpenRatio: mouthOpenRatio)
                : starts(gesture, pitch: relativePitch, leftEye: leftEye,
                         rightEye: rightEye, smile: smile, mouthOpenRatio: mouthOpenRatio)
            let hold = gesture == .blink ? Self.blinkHold : Self.actionHold
            if holdAction(condition, now: now, requiredHold: hold) {
                beginPhaseFeedback(now: now, completesGesture: true)
            }
        }
        return progress
    }

    private func performSwing(angle: CGFloat, threshold: CGFloat, now: CFTimeInterval) {
        if phase == 0 {
            if actionFrames == 0 { actionSide = angle >= 0 ? 1 : -1 }
            let side = CGFloat(actionSide)
            let condition = actionFrames == 0
                ? abs(angle) > threshold
                : side * angle > threshold * Self.holdHysteresis
            if actionFrames > 0 && side * angle > side * swingPeak {
                swingPeak = angle
            } else if actionFrames == 0 {
                swingPeak = angle
            }
            if holdAction(condition, now: now, requiredHold: Self.swingHold) {
                firstSide = actionSide
                phase = 1
                clearAction()
                beginPhaseFeedback(now: now, completesGesture: false)
            }
            return
        }
        // Keep following the first peak while the head is still travelling that way.
        let side = CGFloat(firstSide)
        if side * angle > side * swingPeak { swingPeak = angle }
        let opposite = -side * angle
        let travel = abs(swingPeak - angle)
        let factor: CGFloat = actionFrames > 0 ? Self.holdHysteresis : 1
        let condition = opposite > threshold * Self.swingOppositeFactor * factor
            && travel > threshold * Self.swingRangeFactor * factor
        if holdAction(condition, now: now, requiredHold: Self.swingHold) {
            beginPhaseFeedback(now: now, completesGesture: true)
        }
    }

    func tick(now: CFTimeInterval) -> FaceGestureProgress {
        lastNow = now
        if retryUntil != 0 {
            if now < retryUntil { return progress }
            resetCore()
        }
        if phaseFeedbackUntil != 0 && now >= phaseFeedbackUntil {
            let completesGesture = phaseFeedbackCompletesGesture
            phaseFeedbackUntil = 0
            phaseFeedbackCompletesGesture = false
            if completesGesture { completeAction(now: now) }
        }
        if phaseFeedbackUntil != 0 && phaseFeedbackCompletesGesture { return progress }
        if returning && now - stageStartedAt > neutralTimeout { beginRetry(now: now) }
        else if armed && !returning && now - stageStartedAt > gestureTimeout {
            beginRetry(now: now)
        }
        return progress
    }

    func resumeAfterCapture() {
        captureDone = true
        pausedForCapture = false
        armed = false
        returning = false
        phase = 0
        firstSide = 0
        phaseFeedbackUntil = 0
        phaseFeedbackCompletesGesture = false
        stageStartedAt = 0
        clearNeutral()
        clearAction()
    }

    func resumeAfterFaceLoss(duration: CFTimeInterval) {
        guard duration > 0 else { return }
        if stageStartedAt > 0 { stageStartedAt += duration }
        if neutralSince > 0 { neutralSince += duration }
        if actionSince > 0 { actionSince += duration }
        if phaseFeedbackUntil > 0 { phaseFeedbackUntil += duration }
    }

    func retry(now: CFTimeInterval) { lastNow = now; beginRetry(now: now) }

    func reset() {
        retryUntil = 0
        resetCore()
    }

    private func resetCore() {
        index = 0
        phase = 0
        firstSide = 0
        swingPeak = 0
        phaseFeedbackUntil = 0
        phaseFeedbackCompletesGesture = false
        armed = false
        returning = false
        pausedForCapture = false
        captureDone = false
        stageStartedAt = 0
        retryUntil = 0
        baselineSet = false
        baselineYaw = 0
        baselinePitch = 0
        baselineRoll = 0
        smoothingReady = false
        lastYaw = 0
        lastPitch = 0
        lastRoll = 0
        lastLeftEye = nil
        lastRightEye = nil
        lastSmile = nil
        lastMouthOpenRatio = 0
        lastCaptureAllowed = true
        clearNeutral()
        clearAction()
    }

    private func relative(_ value: CGFloat, _ baseline: CGFloat) -> CGFloat {
        return baselineSet ? value - baseline : value
    }

    private static func poseNeutral(yaw: CGFloat, pitch: CGFloat, roll: CGFloat) -> Bool {
        return abs(yaw) < neutralYawPitchDegrees
            && abs(pitch) < neutralYawPitchDegrees
            && abs(roll) < neutralRollDegrees
    }

    /// Neutral pose required before a gesture starts: pose plus the gesture's own rest state.
    private func armNeutral(_ gesture: FaceGesture, yaw: CGFloat, pitch: CGFloat,
                            roll: CGFloat) -> Bool {
        guard Self.poseNeutral(yaw: yaw, pitch: pitch, roll: roll) else { return false }
        switch gesture {
        case .openMouth: return lastMouthOpenRatio < Self.mouthClosedRatio
        case .smile: return lastSmile == nil || lastSmile! < Self.smileOff
        case .blink:
            return (lastLeftEye == nil || lastLeftEye! > Self.eyesOpen)
                && (lastRightEye == nil || lastRightEye! > Self.eyesOpen)
        default: return true
        }
    }

    /// Neutral pose required before the photo; mirrors the still validator limits.
    private func captureNeutral(_ gesture: FaceGesture, yaw: CGFloat, pitch: CGFloat,
                                roll: CGFloat) -> Bool {
        guard Self.poseNeutral(yaw: yaw, pitch: pitch, roll: roll) else { return false }
        if enforceCapturePose && (abs(lastYaw) >= Self.captureYawPitchDegrees
            || abs(lastPitch) >= Self.captureYawPitchDegrees
            || abs(lastRoll) >= Self.captureRollDegrees) { return false }
        if lastMouthOpenRatio >= Self.mouthCaptureRatio { return false }
        if let left = lastLeftEye, left < Self.eyesCapture { return false }
        if let right = lastRightEye, right < Self.eyesCapture { return false }
        return gesture != .smile || lastSmile == nil || lastSmile! < Self.smileCapture
    }

    private func starts(_ gesture: FaceGesture, pitch: CGFloat, leftEye: CGFloat?,
                        rightEye: CGFloat?, smile: CGFloat?,
                        mouthOpenRatio: CGFloat) -> Bool {
        switch gesture {
        case .openMouth: return mouthOpenRatio > Self.mouthOpenRatio
        case .blink:
            guard let left = leftEye, let right = rightEye else { return false }
            return left < Self.eyesClosed && right < Self.eyesClosed
        case .smile: return smile != nil && smile! > Self.smileOn
        case .lookUp: return pitch > Self.lookUpDegrees
        case .lookDown: return pitch < -Self.lookDownDegrees
        default: return false
        }
    }

    private func sustains(_ gesture: FaceGesture, pitch: CGFloat, leftEye: CGFloat?,
                          rightEye: CGFloat?, smile: CGFloat?,
                          mouthOpenRatio: CGFloat) -> Bool {
        switch gesture {
        case .openMouth: return mouthOpenRatio > Self.mouthClosedRatio
        case .blink:
            guard let left = leftEye, let right = rightEye else { return false }
            return left < Self.eyesOpen && right < Self.eyesOpen
        case .smile: return smile != nil && smile! > Self.smileOn * Self.holdHysteresis + 0.05
        case .lookUp: return pitch > Self.lookUpDegrees * Self.holdHysteresis
        case .lookDown: return pitch < -Self.lookDownDegrees * Self.holdHysteresis
        default: return false
        }
    }

    private func debugSnapshot(gesture: FaceGesture,
                               stage: FaceGestureStage) -> FaceGestureDebug {
        let relativeYaw = relative(lastYaw, baselineYaw)
        let relativePitch = relative(lastPitch, baselinePitch)
        let relativeRoll = relative(lastRoll, baselineRoll)
        let phaseCount = gesture == .nod || gesture == .shake ? 2 : 1
        // While the first-half feedback is visible keep reporting phase 0 so the overlay
        // marks that half as accepted, even though detection already moved on.
        let reportedPhase = phaseFeedbackUntil != 0 && !phaseFeedbackCompletesGesture
            ? max(0, phase - 1) : phase
        if stage == .waitingNeutral || stage == .returnNeutral {
            let neutral = stage == .returnNeutral
                ? captureNeutral(gesture, yaw: relativeYaw, pitch: relativePitch,
                                 roll: relativeRoll) && lastCaptureAllowed
                : armNeutral(gesture, yaw: relativeYaw, pitch: relativePitch,
                             roll: relativeRoll)
            let required = stage == .returnNeutral
                ? Self.returnNeutralHold : Self.armNeutralHold
            return FaceGestureDebug(
                phase: reportedPhase, phaseCount: phaseCount, metric: .neutral,
                baselineSet: baselineSet, rawPrimary: 0, rawSecondary: nil,
                baseline: 0, relative: 0, target: 0, lessThan: false,
                satisfied: neutral, heldMs: elapsedMs(since: neutralSince),
                requiredHoldMs: Int((required * 1000).rounded()), relativeYaw: relativeYaw,
                relativePitch: relativePitch, relativeRoll: relativeRoll,
                firstSide: firstSide
            )
        }

        var metric: FaceGestureMetric = .none
        var rawPrimary: CGFloat = 0
        var rawSecondary: CGFloat?
        var baseline: CGFloat = 0
        var relative: CGFloat = 0
        var target: CGFloat = 0
        var lessThan = false
        var satisfied = false
        var requiredHold = Self.actionHold
        switch gesture {
        case .nod, .shake:
            let nod = gesture == .nod
            let threshold = nod ? Self.nodDegrees : Self.shakeDegrees
            metric = nod ? .pitch : .yaw
            rawPrimary = nod ? lastPitch : lastYaw
            baseline = nod ? baselinePitch : baselineYaw
            relative = nod ? relativePitch : relativeYaw
            requiredHold = Self.swingHold
            if firstSide == 0 {
                let side: CGFloat = relative >= 0 ? 1 : -1
                target = side * threshold
                lessThan = side < 0
                satisfied = abs(relative) > threshold
            } else {
                let side = CGFloat(firstSide)
                target = -side * threshold * Self.swingOppositeFactor
                lessThan = firstSide > 0
                satisfied = -side * relative > threshold * Self.swingOppositeFactor
                    && abs(swingPeak - relative) > threshold * Self.swingRangeFactor
            }
        case .lookUp, .lookDown:
            metric = .pitch
            rawPrimary = lastPitch
            baseline = baselinePitch
            relative = relativePitch
            target = gesture == .lookUp ? Self.lookUpDegrees : -Self.lookDownDegrees
            lessThan = gesture == .lookDown
            satisfied = lessThan ? relative < target : relative > target
        case .openMouth:
            metric = .mouth
            rawPrimary = lastMouthOpenRatio
            relative = rawPrimary
            target = Self.mouthOpenRatio
            satisfied = relative > target
        case .smile:
            metric = .smile
            rawPrimary = lastSmile ?? -1
            relative = rawPrimary
            target = Self.smileOn
            satisfied = relative > target
        case .blink:
            metric = .eyes
            rawPrimary = lastLeftEye ?? -1
            rawSecondary = lastRightEye
            relative = max(rawPrimary, rawSecondary ?? -1)
            target = Self.eyesClosed
            lessThan = true
            requiredHold = Self.blinkHold
            satisfied = rawPrimary >= 0 && rawSecondary != nil
                && rawPrimary < target && rawSecondary! < target
        }
        return FaceGestureDebug(
            phase: reportedPhase, phaseCount: phaseCount, metric: metric,
            baselineSet: baselineSet, rawPrimary: rawPrimary,
            rawSecondary: rawSecondary, baseline: baseline, relative: relative,
            target: target, lessThan: lessThan, satisfied: satisfied,
            heldMs: elapsedMs(since: actionSince),
            requiredHoldMs: Int((requiredHold * 1000).rounded()),
            relativeYaw: relativeYaw, relativePitch: relativePitch,
            relativeRoll: relativeRoll, firstSide: firstSide
        )
    }

    private func elapsedMs(since: CFTimeInterval) -> Int {
        guard since > 0, lastNow > since else { return 0 }
        return Int((lastNow - since) * 1000)
    }

    private func beginRetry(now: CFTimeInterval) {
        resetCore()
        retryUntil = now + retryMessageDuration
    }

    private func holdNeutral(_ condition: Bool, now: CFTimeInterval,
                             requiredHold: CFTimeInterval) -> Bool {
        guard condition else { clearNeutral(); return false }
        if neutralFrames == 0 { neutralSince = now }
        neutralFrames += 1
        neutralSumYaw += lastYaw
        neutralSumPitch += lastPitch
        neutralSumRoll += lastRoll
        return neutralFrames >= Self.minNeutralFrames && now - neutralSince >= requiredHold
    }

    private func holdAction(_ condition: Bool, now: CFTimeInterval,
                            requiredHold: CFTimeInterval) -> Bool {
        guard condition else { clearAction(); return false }
        if actionFrames == 0 { actionSince = now }
        actionFrames += 1
        return actionFrames >= Self.minActionFrames && now - actionSince >= requiredHold
    }

    private func completeAction(now: CFTimeInterval) {
        returning = true
        phaseFeedbackUntil = 0
        phaseFeedbackCompletesGesture = false
        stageStartedAt = now
        clearNeutral()
        clearAction()
    }

    private func beginPhaseFeedback(now: CFTimeInterval, completesGesture: Bool) {
        phaseFeedbackUntil = now + Self.phaseFeedbackDuration
        phaseFeedbackCompletesGesture = completesGesture
    }

    private func clearNeutral() {
        neutralSince = 0
        neutralFrames = 0
        neutralSumYaw = 0
        neutralSumPitch = 0
        neutralSumRoll = 0
    }

    private func clearAction() { actionSince = 0; actionFrames = 0 }
}

struct FaceCaptureOptions {
    let outputWidth: Int
    let outputHeight: Int
    let jpegQuality: Int
    let showCloseButton: Bool
    let debugMode: Bool
    let beepEnabled: Bool
    let revealCapture: Bool
    let requireDeviceVertical: Bool
    let localization: MlKitLocalization
    let timeoutMs: Int
    let checks: FaceCaptureChecks
    let gestures: [FaceGesture]
    let captureAfterGesture: Int
    let gestureTimeoutMs: Int
    let neutralTimeoutMs: Int
    let retryMessageMs: Int

    init(dictionary: [String: Any]) throws {
        localization = try MlKitLocalization(dictionary: dictionary)
        let known = Set([
            "outputWidth", "outputHeight", "jpegQuality", "showCloseButton",
            "debugMode", "beepEnabled", "revealCapture", "requireDeviceVertical", "timeoutMs", "checks",
            "moduleLoadingMessage", "locale", "gestures", "captureAfterGesture",
            "gestureTimeoutMs", "neutralTimeoutMs", "retryMessageMs"
        ])
        for key in dictionary.keys where !known.contains(key) {
            throw MlKitSuiteError.invalidArgument("Unknown face capture option: \(key).")
        }

        func integer(_ key: String, fallback: Int, minimum: Int, maximum: Int) throws -> Int {
            guard let value = dictionary[key] else { return fallback }
            guard let number = value as? NSNumber,
                  CFGetTypeID(number) != CFBooleanGetTypeID() else {
                throw MlKitSuiteError.invalidArgument("\(key) must be an integer.")
            }
            let double = number.doubleValue
            let integer = number.intValue
            guard double.isFinite, double == Double(integer), integer >= minimum, integer <= maximum else {
                throw MlKitSuiteError.invalidArgument(
                    "\(key) must be an integer between \(minimum) and \(maximum)."
                )
            }
            return integer
        }
        func boolean(_ key: String, fallback: Bool) throws -> Bool {
            guard let value = dictionary[key] else { return fallback }
            guard let number = value as? NSNumber,
                  CFGetTypeID(number) == CFBooleanGetTypeID() else {
                throw MlKitSuiteError.invalidArgument("\(key) must be boolean.")
            }
            return number.boolValue
        }

        outputWidth = try integer("outputWidth", fallback: 600, minimum: 320, maximum: 2048)
        outputHeight = try integer("outputHeight", fallback: 720, minimum: 320, maximum: 2048)
        jpegQuality = try integer("jpegQuality", fallback: 95, minimum: 50, maximum: 100)
        showCloseButton = try boolean("showCloseButton", fallback: true)
        debugMode = try boolean("debugMode", fallback: false)
        beepEnabled = try boolean("beepEnabled", fallback: true)
        revealCapture = try boolean("revealCapture", fallback: true)
        requireDeviceVertical = try boolean("requireDeviceVertical", fallback: true)
        timeoutMs = try integer("timeoutMs", fallback: 90_000, minimum: 0, maximum: 600_000)
        if let raw = dictionary["checks"], !(raw is NSNull) {
            guard let checks = raw as? [String: Any] else {
                throw MlKitSuiteError.invalidArgument("checks must be an object.")
            }
            self.checks = try FaceCaptureChecks(dictionary: checks)
        } else {
            self.checks = try FaceCaptureChecks(dictionary: nil)
        }
        if let raw = dictionary["gestures"], !(raw is NSNull) {
            guard let values = raw as? [Any], values.count <= 5 else {
                throw MlKitSuiteError.invalidArgument("gestures must be an array with at most 5 entries.")
            }
            gestures = try values.map { value in
                guard let name = value as? String,
                      let gesture = FaceGesture(rawValue: name.trimmingCharacters(
                        in: .whitespacesAndNewlines).uppercased()) else {
                    throw MlKitSuiteError.invalidArgument("Unsupported face gesture: \(value).")
                }
                return gesture
            }
        } else {
            gestures = []
        }
        captureAfterGesture = try integer(
            "captureAfterGesture", fallback: gestures.count,
            minimum: gestures.isEmpty ? 0 : 1, maximum: gestures.count
        )
        gestureTimeoutMs = try integer("gestureTimeoutMs", fallback: 6000,
                                       minimum: 1000, maximum: 15_000)
        neutralTimeoutMs = try integer("neutralTimeoutMs", fallback: 3000,
                                       minimum: 500, maximum: 5000)
        retryMessageMs = try integer("retryMessageMs", fallback: 1000,
                                     minimum: 250, maximum: 3000)
    }
}

enum FaceCaptureState {
    case searching, multipleFaces, moveCloser, moveAway, centerFace, lookStraight
    case openEyes, neutralExpression, improveLighting, reduceLighting, holdStill
    case deviceTilted, gesture, ready, capturing
}

struct FaceCenteringResult {
    let horizontalOffset: CGFloat
    let verticalOffset: CGFloat
    let available: Bool
    let accepted: Bool

    static let unavailable = FaceCenteringResult(
        horizontalOffset: .nan, verticalOffset: .nan,
        available: false, accepted: false
    )
}

enum FaceCenteringGeometry {
    static let maximumHorizontalOffset: CGFloat = 0.42
    static let maximumVerticalOffset: CGFloat = 0.38

    static func evaluate(faceBounds: CGRect, imageSize: CGSize,
                         previewSize: CGSize, guide: CGRect,
                         mirrored: Bool) -> FaceCenteringResult {
        guard imageSize.width > 0, imageSize.height > 0,
              previewSize.width > 0, previewSize.height > 0,
              guide.width > 0, guide.height > 0 else {
            return .unavailable
        }
        let scale = max(previewSize.width / imageSize.width,
                        previewSize.height / imageSize.height)
        let horizontalInset = (previewSize.width - imageSize.width * scale) / 2
        let verticalInset = (previewSize.height - imageSize.height * scale) / 2
        var centerX = faceBounds.midX * imageSize.width * scale + horizontalInset
        if mirrored { centerX = previewSize.width - centerX }
        let centerY = faceBounds.midY * imageSize.height * scale + verticalInset
        let horizontal = (centerX - guide.midX) / (guide.width / 2)
        let vertical = (centerY - guide.midY) / (guide.height / 2)
        guard horizontal.isFinite, vertical.isFinite else { return .unavailable }
        return FaceCenteringResult(
            horizontalOffset: horizontal,
            verticalOffset: vertical,
            available: true,
            accepted: abs(horizontal) <= maximumHorizontalOffset
                && abs(vertical) <= maximumVerticalOffset
        )
    }
}

struct FaceCaptureAnalysis {
    var state: FaceCaptureState
    let normalizedBounds: CGRect?
    let yaw: CGFloat
    let pitch: CGFloat
    let roll: CGFloat
    let leftEye: CGFloat?
    let rightEye: CGFloat?
    let smile: CGFloat?
    let mouthOpenRatio: CGFloat
    let brightness: CGFloat
    let sharpness: CGFloat
    var stableDurationMs: Int
    let gestureProgress: FaceGestureProgress?
    let centeringHorizontalOffset: CGFloat
    let centeringVerticalOffset: CGFloat
    let centeringAvailable: Bool
    let centeringAccepted: Bool
    /// Device verticality sampled on the analysis queue for this frame, so the main thread
    /// never reads `deviceVerticalReady` without synchronization.
    var deviceUpright: Bool = true
}

struct FaceCaptureResult {
    let fileURL: URL
    let width: Int
    let height: Int
    let brightness: CGFloat
    let sharpness: CGFloat
    let yaw: CGFloat
    let pitch: CGFloat
    let roll: CGFloat
    let leftEye: CGFloat?
    let rightEye: CGFloat?
    let smile: CGFloat?
}

