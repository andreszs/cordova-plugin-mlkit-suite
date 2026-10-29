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
}

struct FaceGestureProgress {
    let gesture: FaceGesture?
    let stage: FaceGestureStage
    let index: Int
    let count: Int
    let debug: FaceGestureDebug?
    let phaseFeedback: Bool
}

final class FaceGestureTracker {
    private static let returnNeutralHold: CFTimeInterval = 0.25
    private static let armNeutralHold: CFTimeInterval = 0.65
    private static let phaseFeedbackDuration: CFTimeInterval = 0.5
    private static let horizontalTurnDegrees: CGFloat = 15
    private static let verticalNodDegrees: CGFloat = 10
    private static let initialNeutralYawPitchDegrees: CGFloat = 11
    private static let initialNeutralRollDegrees: CGFloat = 9
    private let gestures: [FaceGesture]
    private let captureAfterGesture: Int
    private let gestureTimeout: CFTimeInterval
    private let neutralTimeout: CFTimeInterval
    private let retryMessageDuration: CFTimeInterval
    private var index = 0
    private var phase = 0
    private var neutralSince: CFTimeInterval = 0
    private var neutralFrames = 0
    private var actionSince: CFTimeInterval = 0
    private var actionFrames = 0
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
    private var lastNow: CFTimeInterval = 0
    private var lastYaw: CGFloat = 0
    private var lastPitch: CGFloat = 0
    private var lastRoll: CGFloat = 0
    private var lastLeftEye: CGFloat?
    private var lastRightEye: CGFloat?
    private var lastSmile: CGFloat?
    private var lastMouthOpenRatio: CGFloat = 0

    init(gestures: [FaceGesture], captureAfterGesture: Int, gestureTimeoutMs: Int,
         neutralTimeoutMs: Int, retryMessageMs: Int) {
        self.gestures = gestures
        self.captureAfterGesture = captureAfterGesture
        gestureTimeout = Double(gestureTimeoutMs) / 1000
        neutralTimeout = Double(neutralTimeoutMs) / 1000
        retryMessageDuration = Double(retryMessageMs) / 1000
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

    func update(yaw: CGFloat, pitch: CGFloat, roll: CGFloat, leftEye: CGFloat?,
                rightEye: CGFloat?, smile: CGFloat?, mouthOpenRatio: CGFloat,
                now: CFTimeInterval) -> FaceGestureProgress {
        lastNow = now
        lastYaw = yaw
        lastPitch = pitch
        lastRoll = roll
        lastLeftEye = leftEye
        lastRightEye = rightEye
        lastSmile = smile
        lastMouthOpenRatio = mouthOpenRatio
        let timed = tick(now: now)
        if timed.stage == .retry || timed.phaseFeedback { return timed }
        if pausedForCapture { return progress }
        guard !isComplete else { return progress }
        let neutralYaw = baselineSet ? yaw - baselineYaw : yaw
        let neutralPitch = baselineSet ? pitch - baselinePitch : pitch
        let neutralRoll = baselineSet ? roll - baselineRoll : roll
        let neutral = abs(neutralYaw) < Self.initialNeutralYawPitchDegrees
            && abs(neutralPitch) < Self.initialNeutralYawPitchDegrees
            && abs(neutralRoll) < Self.initialNeutralRollDegrees
            && mouthOpenRatio < 0.035
            && (leftEye == nil || leftEye! > 0.50)
            && (rightEye == nil || rightEye! > 0.50)
            && (smile == nil || smile! < 0.48)
        if !armed || returning {
            if returning && now - stageStartedAt > neutralTimeout {
                beginRetry(now: now)
                return progress
            }
            let requiredNeutralHold = returning
                ? Self.returnNeutralHold : Self.armNeutralHold
            if holdNeutral(neutral, now: now, requiredHold: requiredNeutralHold) {
                if returning {
                    index += 1
                    phase = 0
                    returning = false
                    armed = false
                    stageStartedAt = 0
                    if !captureDone && index == captureAfterGesture { pausedForCapture = true }
                } else {
                    baselineYaw = yaw
                    baselinePitch = pitch
                    baselineRoll = roll
                    baselineSet = true
                    armed = true
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

        let gesture = gestures[index]
        let relativeYaw = yaw - baselineYaw
        let relativePitch = pitch - baselinePitch
        if gesture == .nod {
            let angle = relativePitch
            if phase == 0 {
                if holdAction(angle > Self.verticalNodDegrees, now: now) {
                    beginPhaseFeedback(now: now, completesGesture: false)
                    clearAction()
                }
            } else if holdAction(angle < -Self.verticalNodDegrees, now: now) {
                beginPhaseFeedback(now: now, completesGesture: true)
            }
        } else if gesture == .shake {
            let angle = relativeYaw
            if phase == 0 {
                if holdAction(angle < -Self.horizontalTurnDegrees, now: now) {
                    beginPhaseFeedback(now: now, completesGesture: false)
                    clearAction()
                }
            } else if holdAction(
                angle > Self.horizontalTurnDegrees, now: now
            ) {
                beginPhaseFeedback(now: now, completesGesture: true)
            }
        } else if holdAction(matches(gesture, yaw: relativeYaw, pitch: relativePitch,
                                     leftEye: leftEye, rightEye: rightEye, smile: smile,
                                     mouthOpenRatio: mouthOpenRatio), now: now) {
            beginPhaseFeedback(now: now, completesGesture: true)
        }
        return progress
    }

    func tick(now: CFTimeInterval) -> FaceGestureProgress {
        lastNow = now
        if retryUntil != 0 {
            if now < retryUntil { return progress }
            resetCore()
        }
        if phaseFeedbackUntil != 0 {
            if now < phaseFeedbackUntil { return progress }
            let completesGesture = phaseFeedbackCompletesGesture
            phaseFeedbackUntil = 0
            phaseFeedbackCompletesGesture = false
            clearAction()
            if completesGesture { completeAction(now: now) }
            else { phase = 1 }
        }
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
        lastYaw = 0
        lastPitch = 0
        lastRoll = 0
        lastLeftEye = nil
        lastRightEye = nil
        lastSmile = nil
        lastMouthOpenRatio = 0
        clearNeutral()
        clearAction()
    }

    private func debugSnapshot(gesture: FaceGesture,
                               stage: FaceGestureStage) -> FaceGestureDebug {
        let relativeYaw = baselineSet ? lastYaw - baselineYaw : lastYaw
        let relativePitch = baselineSet ? lastPitch - baselinePitch : lastPitch
        let relativeRoll = baselineSet ? lastRoll - baselineRoll : lastRoll
        let phaseCount = gesture == .nod || gesture == .shake ? 2 : 1
        if stage == .waitingNeutral || stage == .returnNeutral {
            let neutral = abs(relativeYaw) < Self.initialNeutralYawPitchDegrees
                && abs(relativePitch) < Self.initialNeutralYawPitchDegrees
                && abs(relativeRoll) < Self.initialNeutralRollDegrees
                && lastMouthOpenRatio < 0.035
                && (lastLeftEye == nil || lastLeftEye! > 0.50)
                && (lastRightEye == nil || lastRightEye! > 0.50)
                && (lastSmile == nil || lastSmile! < 0.48)
            let required = stage == .returnNeutral
                ? Self.returnNeutralHold : Self.armNeutralHold
            return FaceGestureDebug(
                phase: phase, phaseCount: phaseCount, metric: .neutral,
                baselineSet: baselineSet, rawPrimary: 0, rawSecondary: nil,
                baseline: 0, relative: 0, target: 0, lessThan: false,
                satisfied: neutral, heldMs: elapsedMs(since: neutralSince),
                requiredHoldMs: Int(required * 1000), relativeYaw: relativeYaw,
                relativePitch: relativePitch, relativeRoll: relativeRoll
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
        switch gesture {
        case .nod:
            metric = .pitch
            rawPrimary = lastPitch
            baseline = baselinePitch
            relative = relativePitch
            target = phase == 0 ? Self.verticalNodDegrees : -Self.verticalNodDegrees
            lessThan = phase != 0
            satisfied = lessThan ? relative < target : relative > target
        case .shake:
            metric = .yaw
            rawPrimary = lastYaw
            baseline = baselineYaw
            relative = relativeYaw
            target = phase == 0 ? -Self.horizontalTurnDegrees : Self.horizontalTurnDegrees
            lessThan = phase == 0
            satisfied = lessThan ? relative < target : relative > target
        case .lookUp, .lookDown:
            metric = .pitch
            rawPrimary = lastPitch
            baseline = baselinePitch
            relative = relativePitch
            target = gesture == .lookUp ? 15 : -15
            lessThan = gesture == .lookDown
            satisfied = lessThan ? relative < target : relative > target
        case .openMouth:
            metric = .mouth
            rawPrimary = lastMouthOpenRatio
            relative = rawPrimary
            target = 0.055
            satisfied = relative > target
        case .smile:
            metric = .smile
            rawPrimary = lastSmile ?? -1
            relative = rawPrimary
            target = 0.72
            satisfied = relative > target
        case .blink:
            metric = .eyes
            rawPrimary = lastLeftEye ?? -1
            rawSecondary = lastRightEye
            relative = max(rawPrimary, rawSecondary ?? -1)
            target = 0.32
            lessThan = true
            satisfied = rawPrimary >= 0 && rawSecondary != nil
                && rawPrimary < target && rawSecondary! < target
        }
        return FaceGestureDebug(
            phase: phase, phaseCount: phaseCount, metric: metric,
            baselineSet: baselineSet, rawPrimary: rawPrimary,
            rawSecondary: rawSecondary, baseline: baseline, relative: relative,
            target: target, lessThan: lessThan, satisfied: satisfied,
            heldMs: elapsedMs(since: actionSince), requiredHoldMs: 160,
            relativeYaw: relativeYaw, relativePitch: relativePitch,
            relativeRoll: relativeRoll
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
        return neutralFrames >= 3 && now - neutralSince >= requiredHold
    }

    private func holdAction(_ condition: Bool, now: CFTimeInterval) -> Bool {
        guard condition else { clearAction(); return false }
        if actionFrames == 0 { actionSince = now }
        actionFrames += 1
        return actionFrames >= 2 && now - actionSince >= 0.16
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
        if !completesGesture && stageStartedAt > 0 {
            stageStartedAt += Self.phaseFeedbackDuration
        }
    }

    private func matches(_ gesture: FaceGesture, yaw: CGFloat, pitch: CGFloat,
                         leftEye: CGFloat?, rightEye: CGFloat?, smile: CGFloat?,
                         mouthOpenRatio: CGFloat) -> Bool {
        switch gesture {
        case .openMouth: return mouthOpenRatio > 0.055
        case .blink:
            return leftEye != nil && rightEye != nil && leftEye! < 0.32 && rightEye! < 0.32
        case .smile: return smile != nil && smile! > 0.72
        case .lookUp: return pitch > 15
        case .lookDown: return pitch < -15
        default: return false
        }
    }

    private func clearNeutral() { neutralSince = 0; neutralFrames = 0 }
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
        gestureTimeoutMs = try integer("gestureTimeoutMs", fallback: 4000,
                                       minimum: 1000, maximum: 15_000)
        neutralTimeoutMs = try integer("neutralTimeoutMs", fallback: 2000,
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

