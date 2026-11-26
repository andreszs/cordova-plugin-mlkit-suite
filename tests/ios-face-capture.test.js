'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');
var controller = fs.readFileSync(path.join(root, 'src/ios/FaceCaptureViewController.swift'), 'utf8');
var types = fs.readFileSync(path.join(root, 'src/ios/FaceCaptureTypes.swift'), 'utf8');
var overlay = fs.readFileSync(path.join(root, 'src/ios/FaceOverlayView.swift'), 'utf8');

assert(controller.includes('checks: options.checks'));
assert(controller.includes('bufferHeight >= bufferWidth ? .up : .leftMirrored'));
assert(controller.includes('previewLayer.connection, mirrored: true'));
assert(controller.includes('beginCapture(analysis, pixelBuffer: pixelBuffer, candidate: true)'));
assert(controller.includes('beginCapture(analysis, pixelBuffer: pixelBuffer, candidate: false)'));
assert(controller.includes('pendingCandidate'));
assert(controller.includes('gestureTracker.resumeAfterCapture()'));
assert(controller.includes('pixelBuffer: pixelBuffer'));
assert(types.includes('beepEnabled = try boolean("beepEnabled", fallback: true)'));
assert(types.includes('revealCapture = try boolean("revealCapture", fallback: true)'));
assert(types.includes('requireDeviceVertical = try boolean("requireDeviceVertical", fallback: true)'));
assert(types.includes('timeoutMs = try integer("timeoutMs", fallback: 90_000'));
assert(types.includes('eyesOpen = bool("eyesOpen", false)'));
assert(types.includes('neutralExpression = bool("neutralExpression", false)'));
// Gesture tracker constants must stay equivalent to Android's GestureTracker.
[
    'static let armNeutralHold: CFTimeInterval = 0.45',
    'static let returnNeutralHold: CFTimeInterval = 0.25',
    'static let actionHold: CFTimeInterval = 0.10',
    'static let swingHold: CFTimeInterval = 0.06',
    'static let blinkHold: CFTimeInterval = 0',
    'static let phaseFeedbackDuration: CFTimeInterval = 0.35',
    'static let minNeutralFrames = 3',
    'static let minActionFrames = 2',
    'static let poseSmoothing: CGFloat = 0.6',
    'static let holdHysteresis: CGFloat = 0.7',
    'static let neutralYawPitchDegrees: CGFloat = 11',
    'static let neutralRollDegrees: CGFloat = 9',
    'static let captureYawPitchDegrees: CGFloat = 11',
    'static let captureRollDegrees: CGFloat = 9',
    'static let nodDegrees: CGFloat = 8',
    'static let shakeDegrees: CGFloat = 12',
    'static let swingOppositeFactor: CGFloat = 0.55',
    'static let swingRangeFactor: CGFloat = 1.7',
    'static let lookUpDegrees: CGFloat = 12',
    'static let lookDownDegrees: CGFloat = 10',
    'static let mouthOpenRatio: CGFloat = 0.05',
    'static let mouthClosedRatio: CGFloat = 0.035',
    'static let mouthCaptureRatio: CGFloat = 0.045',
    'static let smileOn: CGFloat = 0.65',
    'static let smileOff: CGFloat = 0.45',
    'static let smileCapture: CGFloat = 0.55',
    'static let eyesClosed: CGFloat = 0.35',
    'static let eyesOpen: CGFloat = 0.5',
    'static let eyesCapture: CGFloat = 0.4'
].forEach(function (constant) {
    assert(types.includes(constant), 'Missing tracker constant: ' + constant);
});
assert(!types.includes('horizontalTurnDegrees'));
assert(!types.includes('verticalNodDegrees'));
assert(types.includes('static let maximumHorizontalOffset: CGFloat = 0.42'));
assert(types.includes('static let maximumVerticalOffset: CGFloat = 0.38'));
assert(types.includes('let scale = max(previewSize.width / imageSize.width'));
// Smoothed pose (EMA) restarted by resetCore.
assert(types.includes('lastYaw += Self.poseSmoothing * (yaw - lastYaw)'));
assert(types.includes('lastPitch += Self.poseSmoothing * (pitch - lastPitch)'));
assert(types.includes('lastRoll += Self.poseSmoothing * (roll - lastRoll)'));
assert(/private func resetCore\(\) \{[\s\S]*?smoothingReady = false[\s\S]*?\n    \}/.test(types));
// Bidirectional NOD/SHAKE swing with hysteresis.
assert(types.includes('performSwing(angle: gesture == .nod ? relativePitch : relativeYaw'));
assert(types.includes('? abs(angle) > threshold'));
assert(types.includes(': side * angle > threshold * Self.holdHysteresis'));
assert(types.includes('opposite > threshold * Self.swingOppositeFactor * factor'));
assert(types.includes('travel > threshold * Self.swingRangeFactor * factor'));
assert(types.includes('let firstSide: Int'));
assert(types.includes('relativeRoll: relativeRoll, firstSide: firstSide'));
// The first-half feedback is visual only; only completing feedback pauses input.
assert(types.includes('if phaseFeedbackUntil != 0 && phaseFeedbackCompletesGesture { return timed }'));
assert(types.includes('beginPhaseFeedback(now: now, completesGesture: false)'));
assert(types.includes('? max(0, phase - 1) : phase'));
assert(!types.includes('stageStartedAt += Self.phaseFeedbackDuration'));
// Hysteresis for every action gesture.
assert(types.includes('? sustains(gesture, pitch: relativePitch'));
assert(types.includes(': starts(gesture, pitch: relativePitch'));
assert(types.includes('let hold = gesture == .blink ? Self.blinkHold : Self.actionHold'));
// Neutral before the gesture vs. before the photo.
assert(types.includes('private func armNeutral(_ gesture: FaceGesture'));
assert(types.includes('private func captureNeutral(_ gesture: FaceGesture'));
assert(types.includes('roll: relativeRoll) && captureAllowed'));
assert(types.includes('if enforceCapturePose && (abs(lastYaw) >= Self.captureYawPitchDegrees'));
assert(types.includes('return gesture != .smile || lastSmile == nil || lastSmile! < Self.smileCapture'));
assert(types.includes('now: CFTimeInterval, captureAllowed: Bool = true) -> FaceGestureProgress'));
assert(types.includes('neutralTimeoutMs: Int, retryMessageMs: Int, enforceCapturePose: Bool = true)'));
// Baseline is the mean of the neutral frames.
assert(types.includes('baselineYaw = neutralSumYaw / frames'));
assert(types.includes('baselinePitch = neutralSumPitch / frames'));
assert(types.includes('baselineRoll = neutralSumRoll / frames'));
assert(types.includes('var deviceUpright: Bool = true'));
assert(!types.includes('TURN_LEFT'));
assert(!types.includes('TURN_RIGHT'));
assert(types.includes('private var baselinePitch: CGFloat = 0'));
assert(types.includes('let relativePitch = relative(lastPitch, baselinePitch)'));
assert(types.includes('requiredHold: requiredNeutralHold'));
assert(controller.includes('if options.beepEnabled'));
assert(controller.includes('if options.revealCapture'));
assert(controller.includes('motionManager.startDeviceMotionUpdates'));
assert(controller.includes('private lazy var motionQueue: OperationQueue'));
assert(controller.includes('queue.maxConcurrentOperationCount = 1'));
assert(controller.includes('queue.underlyingQueue = analysisQueue'));
assert(controller.includes('motionManager.startDeviceMotionUpdates(to: motionQueue)'));
assert(!controller.includes('motionManager.startDeviceMotionUpdates(to: analysisQueue)'));
// NOD/SHAKE instructions follow the side accepted first. iOS yaw is negative toward
// the user's left, the inverse of Android.
assert(controller.includes('let firstSide = progress.debug?.firstSide ?? 0'));
assert(controller.includes(
    'firstSide == 0 ? "face_gesture_nod"\n'
    + '                        : firstSide > 0 ? "face_gesture_nod_down" : "face_gesture_nod_up_now"'
));
assert(controller.includes(
    'firstSide == 0 ? "face_gesture_shake"\n'
    + '                        : firstSide < 0 ? "face_gesture_shake_right_now" : "face_gesture_shake_left"'
));
assert(!controller.includes('phase == 0 ? "face_gesture_shake'));
assert(!controller.includes('phase == 0 ? "face_gesture_nod'));
assert(controller.includes('if analysis?.deviceUpright == false {'));
assert(controller.includes('enforceCapturePose: options.checks.pose'));
assert(controller.includes('captureAllowed: deviceUpright'));
assert(controller.includes('if !deviceUpright && (deviceSeverelyTilted || !gestureInMotion) {'));
assert(controller.includes('let gestureInMotion = currentStage == .perform || currentStage == .returnNeutral'));
assert(controller.includes('self.deviceSeverelyTilted = tilt > Self.deviceSevereTiltDegrees'));
assert(controller.includes('deviceUpright: deviceUpright'));
assert(controller.includes('private static let faceLossGrace: CFTimeInterval = 0.70'));
assert(controller.includes('self.session.sessionPreset = .photo'));
assert(controller.includes('return emptyAnalysis(.deviceTilted'));
assert(controller.includes('options.localization.text("face_lower_head")'));
assert(controller.includes('options.localization.text("face_raise_head")'));
assert(controller.includes('deviceVerticalHold: CFTimeInterval = 0.4'));
assert(controller.includes('deviceVerticalEnterDegrees: Double = 10'));
assert(controller.includes('deviceVerticalExitDegrees: Double = 16'));
assert(controller.includes('deviceSevereTiltDegrees: Double = 28'));
assert(controller.includes('candidateCapture && self.pendingCandidate != nil'));
assert(controller.includes('successBeep.play()'));
assert(!controller.includes('AVCapturePhotoOutput'));
assert(!controller.includes('AVCapturePhotoCaptureDelegate'));
assert(!controller.includes('fileDataRepresentation'));
assert(!controller.includes('[MlKitSuite][Face]'));
var stillProcessor = fs.readFileSync(path.join(root, 'src/ios/FaceStillProcessor.swift'), 'utf8');
assert(stillProcessor.includes('func process(pixelBuffer: CVPixelBuffer'));
assert(stillProcessor.includes('CIImage(cvPixelBuffer: pixelBuffer)'));
assert(stillProcessor.includes('UIImage(cgImage: cgImage, scale: 1, orientation: .up)'));
assert(stillProcessor.includes('analysis.normalizedBounds'));
assert(!stillProcessor.includes('VisionImage'));
assert(!stillProcessor.includes('FaceDetector'));
assert(!stillProcessor.includes('debug-face-capture.jpg'));
assert(!stillProcessor.includes('orientations: [UIImage.Orientation]'));
assert(overlay.includes('private let checks: FaceCaptureChecks'));
assert(overlay.includes('size %.2f'));
assert(overlay.includes('center x %+.2f y %+.2f'));
assert(overlay.includes('eye L '));
assert(overlay.includes('smile '));
assert(overlay.includes('light %.2f'));
assert(overlay.includes('sharp %.2f'));
assert(overlay.includes('stable \\(analysis.stableDurationMs)ms'));
assert(overlay.includes('guard enabled else { return }'));
assert(overlay.includes('accepted == true ? valid : pending'));
assert(overlay.includes('deviceVerticalCheckEnabled'));
assert(overlay.includes('String(format: "tilt %.1f°"'));
assert(overlay.includes('box.width >= 0.28 && box.width <= 0.62'));
assert(overlay.includes('analysis.centeringAccepted'));
assert(overlay.includes('analysis.sharpness >= 0.10'));
assert(overlay.includes('analysis.stableDurationMs >= 650'));
assert(!overlay.includes('let face = UIBezierPath(rect: mapped)'));
assert(overlay.includes('private let progressSegments: Int'));
assert(overlay.includes('private func drawProgress(centerY: CGFloat)'));
assert(overlay.includes('private func overlayLayout() -> OverlayLayout'));
assert(overlay.includes('max(0, availableGuideHeight - guideHeight) * 0.65'));
assert(overlay.includes('monospacedSystemFont(ofSize: 10'));
assert(overlay.includes('let allProgressComplete = progressCompleted == progressSegments'));
assert(overlay.includes('? green : index == progressCompleted ? yellow : future'));
assert(controller.includes('progressSegments: options.gestures.count + 2'));
assert(controller.includes('else if activeGesture'));
assert(controller.includes('return action'));
assert(!controller.includes('face_gesture_progress", action'));

process.stdout.write('iOS face capture overlay contract tests passed.\n');
