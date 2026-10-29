import AVFoundation
import CoreMotion
import MLKitFaceDetection
import MLKitVision
import UIKit

protocol FaceCaptureViewControllerDelegate: AnyObject {
    func faceCapture(_ controller: FaceCaptureViewController, didFinish result: FaceCaptureResult)
    func faceCaptureDidCancel(_ controller: FaceCaptureViewController)
    func faceCapture(_ controller: FaceCaptureViewController, didFail error: MlKitSuiteError)
}

final class FaceCaptureViewController: UIViewController {
    private static let deviceVerticalHold: CFTimeInterval = 0.7
    private static let deviceVerticalEnterDegrees: Double = 10
    private static let deviceVerticalExitDegrees: Double = 10
    private static let faceLossGrace: CFTimeInterval = 0.30
    weak var delegate: FaceCaptureViewControllerDelegate?

    private let options: FaceCaptureOptions
    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.andreszs.mlkitsuite.session")
    private let analysisQueue = DispatchQueue(label: "com.andreszs.mlkitsuite.analysis", qos: .userInitiated)
    private let processingQueue = DispatchQueue(label: "com.andreszs.mlkitsuite.processing", qos: .userInitiated)
    private let liveDetector: FaceDetector
    private let stillProcessor: FaceStillProcessor
    private let gestureTracker: FaceGestureTracker
    private let successBeep = MlKitSuccessBeep()
    private let motionManager = CMMotionManager()
    private var previewLayer: AVCaptureVideoPreviewLayer!
    private var overlayView: FaceOverlayView!
    private var videoOutput: AVCaptureVideoDataOutput?
    private var processingFrame = false
    private var capturing = false
    private var capturingCandidate = false
    private var completed = false
    private var pendingCandidate: FaceCaptureResult?
    private var lastCaptureAnalysis: FaceCaptureAnalysis?
    private var previousBounds: CGRect?
    private var stableSince: CFTimeInterval = 0
    private var stableFrames = 0
    private var timeoutItem: DispatchWorkItem?
    private var deviceVerticalReady: Bool
    private var deviceVerticalSince: CFTimeInterval = 0
    private var faceMissingSince: CFTimeInterval = 0
    private let centeringLock = NSLock()
    private var centeringPreviewSize: CGSize = .zero
    private var centeringGuide: CGRect = .zero
    init(options: FaceCaptureOptions) {
        self.options = options
        let detectorOptions = FaceDetectorOptions()
        detectorOptions.performanceMode = .fast
        detectorOptions.landmarkMode = .none
        detectorOptions.classificationMode = .all
        detectorOptions.contourMode = options.gestures.contains(.openMouth) ? .all : .none
        detectorOptions.minFaceSize = 0.15
        detectorOptions.isTrackingEnabled = true
        liveDetector = FaceDetector.faceDetector(options: detectorOptions)
        stillProcessor = FaceStillProcessor(options: options)
        gestureTracker = FaceGestureTracker(
            gestures: options.gestures, captureAfterGesture: options.captureAfterGesture,
            gestureTimeoutMs: options.gestureTimeoutMs,
            neutralTimeoutMs: options.neutralTimeoutMs,
            retryMessageMs: options.retryMessageMs
        )
        deviceVerticalReady = !options.requireDeviceVertical
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
        modalPresentationCapturesStatusBarAppearance = true
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }
    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { .portrait }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(previewLayer)
        overlayView = FaceOverlayView(
            localization: options.localization,
            debugMode: options.debugMode,
            checks: options.checks,
            initialMessage: options.localization.text("face_searching"),
            progressSegments: options.gestures.count + 2,
            captureAfterGesture: options.captureAfterGesture,
            hasGestures: !options.gestures.isEmpty,
            deviceVerticalCheckEnabled: options.requireDeviceVertical
                && motionManager.isDeviceMotionAvailable
        )
        overlayView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(overlayView)
        NSLayoutConstraint.activate([
            overlayView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            overlayView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            overlayView.topAnchor.constraint(equalTo: view.topAnchor),
            overlayView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        if options.showCloseButton { addCloseButton() }
        startVerticalityMonitoring()
        scheduleTimeout()
        configureCamera()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer.frame = view.bounds
        let previewSize = previewLayer.bounds.size
        let guide = overlayView.centeringGuide()
        centeringLock.lock()
        centeringPreviewSize = previewSize
        centeringGuide = guide
        centeringLock.unlock()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        motionManager.stopDeviceMotionUpdates()
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    func abortForWebViewReset() {
        guard !completed else { return }
        discardPendingCandidate()
        completed = true
        timeoutItem?.cancel()
        dismiss(animated: false)
    }

    private func addCloseButton() {
        let button = UIButton(type: .custom)
        button.setTitle("×", for: .normal)
        button.accessibilityLabel = options.localization.text("close_face")
        button.setTitleColor(.white, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 30, weight: .regular)
        button.backgroundColor = UIColor.black.withAlphaComponent(0.4)
        button.layer.borderColor = UIColor.white.withAlphaComponent(0.6).cgColor
        button.layer.borderWidth = 1
        button.layer.cornerRadius = 22
        button.translatesAutoresizingMaskIntoConstraints = false
        button.addTarget(self, action: #selector(cancelByUser), for: .touchUpInside)
        view.addSubview(button)
        NSLayoutConstraint.activate([
            button.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 12),
            button.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            button.widthAnchor.constraint(equalToConstant: 44),
            button.heightAnchor.constraint(equalToConstant: 44)
        ])
    }

    private func configureCamera() {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            do {
                self.session.beginConfiguration()
                self.session.sessionPreset = .photo
                guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) else {
                    throw MlKitSuiteError.cameraUnavailable(
                        self.options.localization.text("front_camera_missing"), false)
                }
                let input = try AVCaptureDeviceInput(device: camera)
                guard self.session.canAddInput(input) else {
                    throw MlKitSuiteError.cameraUnavailable(
                        self.options.localization.text("front_camera_connect_failed"), true)
                }
                self.session.addInput(input)

                let video = AVCaptureVideoDataOutput()
                video.alwaysDiscardsLateVideoFrames = true
                video.videoSettings = [
                    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
                ]
                video.setSampleBufferDelegate(self, queue: self.analysisQueue)
                guard self.session.canAddOutput(video) else {
                    throw MlKitSuiteError.cameraUnavailable(
                        self.options.localization.text("camera_analysis_config_failed"), true)
                }
                self.session.addOutput(video)
                self.videoOutput = video

                self.session.commitConfiguration()

                self.configure(connection: video.connection(with: .video), mirrored: true)
                DispatchQueue.main.async {
                    self.configure(connection: self.previewLayer.connection, mirrored: true)
                }
                self.session.startRunning()
            } catch let error as MlKitSuiteError {
                self.session.commitConfiguration()
                DispatchQueue.main.async { self.fail(error) }
            } catch {
                self.session.commitConfiguration()
                DispatchQueue.main.async {
                    self.fail(.cameraUnavailable(
                        self.options.localization.format(
                            "front_camera_start_failed", error.localizedDescription
                        ), true
                    ))
                }
            }
        }
    }

    private func configure(connection: AVCaptureConnection?, mirrored: Bool) {
        guard let connection = connection else { return }
        if connection.isVideoOrientationSupported { connection.videoOrientation = .portrait }
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = mirrored
        }
    }

    private func classify(faces: [Face], brightness: CGFloat, sharpness: CGFloat,
                          imageSize: CGSize) -> FaceCaptureAnalysis {
        let now = CACurrentMediaTime()
        if options.requireDeviceVertical && !deviceVerticalReady {
            resetStability()
            faceMissingSince = 0
            let progress = gestureTracker.progress
            if !options.gestures.isEmpty && (gestureTracker.hasStarted || progress.stage == .retry) {
                if progress.stage != .retry { gestureTracker.retry(now: now) }
                return emptyAnalysis(.gesture, brightness, sharpness,
                                     progress: gestureTracker.progress)
            }
            gestureTracker.reset()
            return emptyAnalysis(.deviceTilted, brightness, sharpness)
        }
        guard !faces.isEmpty else {
            resetStability()
            if !options.gestures.isEmpty && gestureTracker.hasStarted {
                if faceMissingSince == 0 { faceMissingSince = now }
                if now - faceMissingSince <= Self.faceLossGrace {
                    var progress = gestureTracker.progress
                    // Keep the internal capture checkpoint frozen, but never expose capture
                    // until a face is present again.
                    if progress.stage == .capture {
                        progress = FaceGestureProgress(
                            gesture: progress.gesture, stage: .returnNeutral,
                            index: progress.index, count: progress.count,
                            debug: progress.debug, phaseFeedback: progress.phaseFeedback
                        )
                    }
                    return emptyAnalysis(.gesture, brightness, sharpness,
                                         progress: progress)
                }
                gestureTracker.retry(now: now)
                faceMissingSince = 0
                return emptyAnalysis(.gesture, brightness, sharpness,
                                     progress: gestureTracker.progress)
            }
            faceMissingSince = 0
            gestureTracker.reset()
            return emptyAnalysis(.searching, brightness, sharpness)
        }
        if options.checks.singleFace && faces.count != 1 {
            resetStability()
            faceMissingSince = 0
            if !options.gestures.isEmpty && gestureTracker.hasStarted {
                gestureTracker.retry(now: now)
                return emptyAnalysis(.gesture, brightness, sharpness,
                                     progress: gestureTracker.progress)
            }
            gestureTracker.reset()
            return emptyAnalysis(.multipleFaces, brightness, sharpness)
        }
        if faceMissingSince != 0 {
            gestureTracker.resumeAfterFaceLoss(
                duration: min(Self.faceLossGrace, now - faceMissingSince)
            )
            faceMissingSince = 0
        }
        let face = faces.max { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }!
        let frame = face.frame
        let bounds = CGRect(
            x: max(0, min(1, frame.minX / imageSize.width)),
            y: max(0, min(1, frame.minY / imageSize.height)),
            width: max(0, min(1, frame.width / imageSize.width)),
            height: max(0, min(1, frame.height / imageSize.height))
        )
        centeringLock.lock()
        let previewSize = centeringPreviewSize
        let guide = centeringGuide
        centeringLock.unlock()
        let centering = FaceCenteringGeometry.evaluate(
            faceBounds: bounds, imageSize: imageSize,
            previewSize: previewSize, guide: guide, mirrored: false
        )
        let leftEye = face.hasLeftEyeOpenProbability ? face.leftEyeOpenProbability : nil
        let rightEye = face.hasRightEyeOpenProbability ? face.rightEyeOpenProbability : nil
        let smile = face.hasSmilingProbability ? face.smilingProbability : nil
        let yaw = face.hasHeadEulerAngleY ? face.headEulerAngleY : 0
        let pitch = face.hasHeadEulerAngleX ? face.headEulerAngleX : 0
        let roll = face.hasHeadEulerAngleZ ? face.headEulerAngleZ : 0
        let mouthRatio = mouthOpenRatio(face)
        var gestureProgress = gestureTracker.tick(now: now)
        let activeGesture = gestureProgress.stage == .perform
            || gestureProgress.stage == .returnNeutral
            || gestureProgress.stage == .capture
        var state: FaceCaptureState
        if gestureProgress.stage == .retry { state = .gesture }
        else if activeGesture {
            gestureProgress = gestureTracker.update(
                yaw: yaw, pitch: pitch, roll: roll, leftEye: leftEye, rightEye: rightEye,
                smile: smile, mouthOpenRatio: mouthRatio, now: now
            )
            state = .gesture
        }
        else if options.checks.faceSize && bounds.width < 0.28 { state = .moveCloser }
        else if options.checks.faceSize && bounds.width > 0.62 { state = .moveAway }
        else if options.checks.centering
            && (!centering.available || !centering.accepted) { state = .centerFace }
        else if options.checks.brightness && brightness < 0.20 { state = .improveLighting }
        else if options.checks.brightness && brightness > 0.88 { state = .reduceLighting }
        else if options.checks.sharpness && sharpness < 0.10 { state = .holdStill }
        else if !gestureTracker.isComplete {
            gestureProgress = gestureTracker.update(
                yaw: yaw, pitch: pitch, roll: roll, leftEye: leftEye, rightEye: rightEye,
                smile: smile, mouthOpenRatio: mouthRatio, now: now
            )
            state = .gesture
        } else if options.checks.pose && (
            abs(yaw) > 11 || abs(pitch) > 11 || abs(roll) > 9
        ) { state = .lookStraight }
        else if options.checks.eyesOpen && (
            leftEye == nil || rightEye == nil || leftEye! < 0.62 || rightEye! < 0.62
        ) { state = .openEyes }
        else if options.checks.neutralExpression && smile != nil && smile! > 0.38 {
            state = .neutralExpression
        }
        else { state = .ready }

        var stableMs = 0
        if state == .ready && options.checks.stability {
            stableMs = updateStability(bounds)
            if stableMs < 650 { state = .holdStill }
        } else if state != .ready {
            resetStability()
        }
        return FaceCaptureAnalysis(
            state: state, normalizedBounds: bounds,
            yaw: yaw, pitch: pitch, roll: roll,
            leftEye: leftEye, rightEye: rightEye, smile: smile, mouthOpenRatio: mouthRatio,
            brightness: brightness, sharpness: sharpness, stableDurationMs: stableMs,
            gestureProgress: gestureProgress,
            centeringHorizontalOffset: centering.horizontalOffset,
            centeringVerticalOffset: centering.verticalOffset,
            centeringAvailable: centering.available,
            centeringAccepted: centering.accepted
        )
    }

    private func updateStability(_ bounds: CGRect) -> Int {
        let now = CACurrentMediaTime()
        guard let previous = previousBounds else {
            previousBounds = bounds
            stableSince = now
            stableFrames = 1
            return 0
        }
        let movement = hypot(bounds.midX - previous.midX, bounds.midY - previous.midY)
        let oldArea = previous.width * previous.height
        let areaChange = oldArea <= 0 ? 1 : abs(bounds.width * bounds.height - oldArea) / oldArea
        if movement > 0.015 || areaChange > 0.05 {
            stableSince = now
            stableFrames = 1
        } else {
            stableFrames += 1
        }
        previousBounds = bounds
        return stableFrames >= 4 ? Int((now - stableSince) * 1000) : 0
    }

    private func resetStability() {
        previousBounds = nil
        stableSince = 0
        stableFrames = 0
    }

    private func startVerticalityMonitoring() {
        guard options.requireDeviceVertical else { return }
        guard motionManager.isDeviceMotionAvailable else {
            deviceVerticalReady = true
            return
        }
        deviceVerticalReady = false
        deviceVerticalSince = 0
        motionManager.deviceMotionUpdateInterval = 1.0 / 30.0
        motionManager.startDeviceMotionUpdates(to: analysisQueue) { [weak self] motion, _ in
            guard let self = self, let gravity = motion?.gravity else { return }
            let tilt = atan2(hypot(gravity.x, gravity.z), abs(gravity.y)) * 180 / .pi
            let limit = self.deviceVerticalReady
                ? Self.deviceVerticalExitDegrees : Self.deviceVerticalEnterDegrees
            let now = CACurrentMediaTime()
            if tilt > limit {
                self.deviceVerticalSince = 0
                self.deviceVerticalReady = false
            } else {
                if self.deviceVerticalSince == 0 { self.deviceVerticalSince = now }
                if now - self.deviceVerticalSince >= Self.deviceVerticalHold {
                    self.deviceVerticalReady = true
                }
            }
            DispatchQueue.main.async { [weak self] in
                self?.overlayView?.updateDeviceTilt(tilt, ready: self?.deviceVerticalReady ?? false)
            }
        }
    }

    private func emptyAnalysis(_ state: FaceCaptureState, _ brightness: CGFloat,
                               _ sharpness: CGFloat,
                               progress: FaceGestureProgress? = nil) -> FaceCaptureAnalysis {
        return FaceCaptureAnalysis(state: state, normalizedBounds: nil, yaw: 0, pitch: 0, roll: 0,
                                   leftEye: nil, rightEye: nil, smile: nil,
                                   mouthOpenRatio: 0, brightness: brightness, sharpness: sharpness,
                                   stableDurationMs: 0, gestureProgress: progress,
                                   centeringHorizontalOffset: .nan,
                                   centeringVerticalOffset: .nan,
                                   centeringAvailable: false, centeringAccepted: false)
    }

    private func handle(_ analysis: FaceCaptureAnalysis, pixelBuffer: CVPixelBuffer) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self, !self.completed, !self.capturing else { return }
            let candidateCapture = analysis.state == .gesture
                && analysis.gestureProgress?.stage == .capture
            // Ignore a frame queued while the tracker was paused for the candidate capture.
            guard !(candidateCapture && self.pendingCandidate != nil) else { return }
            self.overlayView.setProgressCompleted(self.progressCompleted(for: analysis))
            if analysis.state == .ready && self.pendingCandidate == nil {
                var waitingAnalysis = analysis
                waitingAnalysis.state = .holdStill
                self.overlayView.update(
                    waitingAnalysis,
                    message: self.options.localization.text("face_hold_still")
                )
            } else if analysis.state != .ready
                && (!candidateCapture || self.options.revealCapture) {
                let message = analysis.state == .gesture
                    ? self.gestureMessage(analysis: analysis)
                    : self.message(for: analysis.state)
                self.overlayView.update(analysis, message: message)
            }
            if analysis.state == .gesture, let progress = analysis.gestureProgress {
                if progress.stage == .retry {
                    self.discardPendingCandidate()
                } else if progress.stage == .capture {
                    self.beginCapture(analysis, pixelBuffer: pixelBuffer, candidate: true)
                }
            } else if analysis.state == .ready {
                if let pending = self.pendingCandidate { self.finish(pending) }
                else { self.beginCapture(analysis, pixelBuffer: pixelBuffer, candidate: false) }
            }
        }
    }

    private func beginCapture(_ analysis: FaceCaptureAnalysis, pixelBuffer: CVPixelBuffer,
                              candidate: Bool) {
        guard !capturing, !completed else { return }
        capturing = true
        capturingCandidate = candidate
        lastCaptureAnalysis = analysis
        if options.revealCapture {
            var capturingAnalysis = analysis
            capturingAnalysis.state = .capturing
            overlayView.update(capturingAnalysis,
                               message: options.localization.text("face_capturing"))
        }
        processingQueue.async { [weak self] in
            guard let self = self, !self.completed else { return }
            do {
                let result = try self.stillProcessor.process(
                    pixelBuffer: pixelBuffer,
                    analysis: analysis,
                    directory: self.cacheDirectory()
                )
                DispatchQueue.main.async { self.handleCapturedResult(result) }
            } catch let error as MlKitSuiteError {
                DispatchQueue.main.async {
                    switch error {
                    case .captureFailed(let message, true): self.retry(message)
                    default: self.fail(error)
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    self.fail(.processingFailed(
                        self.options.localization.format(
                            "face_process_failed", error.localizedDescription
                        ), true
                    ))
                }
            }
        }
    }

    private func retry(_ message: String) {
        guard !completed else { return }
        if !options.gestures.isEmpty { gestureTracker.retry(now: CACurrentMediaTime()) }
        discardPendingCandidate()
        resetStability()
        overlayView.setProgressCompleted(0)
        overlayView.update(nil, message: message)
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Double(options.retryMessageMs) / 1000
        ) { [weak self] in
            guard let self = self, !self.completed else { return }
            self.capturing = false
            self.capturingCandidate = false
            self.overlayView.update(nil,
                                    message: self.options.localization.text("face_searching"))
        }
    }

    private func handleCapturedResult(_ result: FaceCaptureResult) {
        if !capturingCandidate || options.gestures.isEmpty
            || options.captureAfterGesture >= options.gestures.count {
            finish(result)
            return
        }
        discardPendingCandidate()
        pendingCandidate = result
        overlayView.setProgressCompleted(min(options.gestures.count + 2,
                                             options.captureAfterGesture + 2))
        gestureTracker.resumeAfterCapture()
        capturing = false
        capturingCandidate = false
    }

    private func discardPendingCandidate() {
        if let candidate = pendingCandidate {
            try? FileManager.default.removeItem(at: candidate.fileURL)
            pendingCandidate = nil
        }
    }

    private func cacheDirectory() throws -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let directory = base.appendingPathComponent("mlkit-suite", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private func scheduleTimeout() {
        guard options.timeoutMs > 0 else { return }
        let item = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.fail(.timeout(self.options.localization.text("face_timeout")))
        }
        timeoutItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(options.timeoutMs) / 1000, execute: item)
    }

    @objc private func cancelByUser() {
        guard !completed else { return }
        discardPendingCandidate()
        completed = true
        timeoutItem?.cancel()
        delegate?.faceCaptureDidCancel(self)
    }

    private func finish(_ result: FaceCaptureResult) {
        guard !completed else { return }
        overlayView.setProgressCompleted(options.gestures.count + 2)
        if var finalAnalysis = lastCaptureAnalysis {
            finalAnalysis.state = .ready
            overlayView.update(finalAnalysis, message: options.localization.text("face_ready"))
        }
        if pendingCandidate?.fileURL == result.fileURL { pendingCandidate = nil }
        completed = true
        timeoutItem?.cancel()
        if options.beepEnabled {
            successBeep.play()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
                guard let self = self else { return }
                self.delegate?.faceCapture(self, didFinish: result)
            }
        } else {
            delegate?.faceCapture(self, didFinish: result)
        }
    }

    private func fail(_ error: MlKitSuiteError) {
        guard !completed else { return }
        discardPendingCandidate()
        completed = true
        timeoutItem?.cancel()
        delegate?.faceCapture(self, didFail: error)
    }

    private func message(for state: FaceCaptureState) -> String {
        switch state {
        case .searching: return options.localization.text("face_instruction")
        case .multipleFaces: return options.localization.text("face_multiple")
        case .moveCloser: return options.localization.text("face_move_closer")
        case .moveAway: return options.localization.text("face_move_away")
        case .centerFace: return options.localization.text("face_center")
        case .lookStraight: return options.localization.text("face_look_straight")
        case .openEyes: return options.localization.text("face_open_eyes")
        case .neutralExpression: return options.localization.text("face_neutral")
        case .improveLighting: return options.localization.text("face_more_light")
        case .reduceLighting: return options.localization.text("face_less_light")
        case .holdStill: return options.localization.text("face_hold_still")
        case .deviceTilted: return options.localization.text("face_device_vertical")
        case .gesture: return gestureMessage(analysis: nil)
        case .ready: return options.localization.text("face_ready")
        case .capturing: return options.localization.text("face_capturing")
        }
    }

    private func gestureMessage(analysis: FaceCaptureAnalysis?) -> String {
        let progress = analysis?.gestureProgress ?? gestureTracker.progress
        if progress.stage == .retry {
            return options.localization.text("face_gesture_retry")
        }
        if progress.stage == .capture {
            return options.localization.text("face_gesture_capture_candidate")
        }
        guard progress.stage != .complete, let gesture = progress.gesture else {
            return options.localization.text("face_gestures_complete")
        }
        let action: String
        switch progress.stage {
        case .waitingNeutral:
            if let pitch = analysis?.pitch, pitch > 11 {
                action = options.localization.text("face_lower_head")
            } else if let pitch = analysis?.pitch, pitch < -11 {
                action = options.localization.text("face_raise_head")
            } else {
                action = options.localization.text("face_gesture_prepare")
            }
        case .returnNeutral: action = options.localization.text("face_gesture_return")
        case .perform:
            let phase = progress.debug?.phase ?? 0
            if gesture == .nod {
                action = options.localization.text(
                    phase == 0 ? "face_gesture_nod_up" : "face_gesture_nod_down"
                )
            } else if gesture == .shake {
                action = options.localization.text(
                    phase == 0 ? "face_gesture_shake_right" : "face_gesture_shake_left"
                )
            } else {
                action = options.localization.text("face_gesture_" + gesture.rawValue.lowercased())
            }
        case .capture, .retry, .complete:
            action = options.localization.text("face_gestures_complete")
        }
        return action
    }

    private func progressCompleted(for analysis: FaceCaptureAnalysis) -> Int {
        let total = options.gestures.count + 2
        if options.gestures.isEmpty {
            return analysis.state == .ready || analysis.state == .capturing ? 1 : 0
        }
        guard let progress = analysis.gestureProgress, progress.stage != .retry else { return 0 }
        let faceAccepted = !(progress.index == 0 && progress.stage == .waitingNeutral)
        var gesturesCompleted = progress.index
        if progress.stage == .returnNeutral {
            gesturesCompleted += 1
        } else if progress.stage == .complete {
            gesturesCompleted = progress.count
        }
        let captureCompleted = pendingCandidate == nil ? 0 : 1
        return min(total, (faceAccepted ? 1 : 0) + gesturesCompleted + captureCompleted)
    }

    private func mouthOpenRatio(_ face: Face) -> CGFloat {
        guard face.frame.height > 0,
              let upper = face.contour(ofType: .upperLipBottom),
              let lower = face.contour(ofType: .lowerLipTop),
              !upper.points.isEmpty, !lower.points.isEmpty else { return 0 }
        let upperY = upper.points.reduce(CGFloat(0)) { $0 + CGFloat($1.y) }
            / CGFloat(upper.points.count)
        let lowerY = lower.points.reduce(CGFloat(0)) { $0 + CGFloat($1.y) }
            / CGFloat(lower.points.count)
        return abs(lowerY - upperY) / face.frame.height
    }

    private func imageQuality(_ buffer: CVPixelBuffer) -> (CGFloat, CGFloat) {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard CVPixelBufferGetPlaneCount(buffer) > 0,
              let base = CVPixelBufferGetBaseAddressOfPlane(buffer, 0) else { return (0, 0) }
        let width = CVPixelBufferGetWidthOfPlane(buffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(buffer, 0)
        let rowBytes = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)
        let bytes = base.assumingMemoryBound(to: UInt8.self)
        let step = max(2, min(width, height) / 120)
        var sum = 0
        var gradient = 0
        var count = 0
        for y in Swift.stride(from: step, to: height, by: step) {
            for x in Swift.stride(from: step, to: width, by: step) {
                let value = Int(bytes[y * rowBytes + x])
                sum += value
                gradient += abs(value - Int(bytes[y * rowBytes + x - step]))
                count += 1
            }
        }
        guard count > 0 else { return (0, 0) }
        return (CGFloat(sum) / CGFloat(count * 255), min(1, CGFloat(gradient) / CGFloat(count * 32)))
    }
}

extension FaceCaptureViewController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard !completed, !capturing, !processingFrame,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        processingFrame = true
        let quality = imageQuality(pixelBuffer)
        let image = VisionImage(buffer: sampleBuffer)
        let bufferWidth = CVPixelBufferGetWidth(pixelBuffer)
        let bufferHeight = CVPixelBufferGetHeight(pixelBuffer)
        // AVCaptureVideoDataOutput may physically rotate portrait buffers when its
        // connection has videoOrientation = .portrait. Only apply the sensor-to-
        // portrait transform when the delivered buffer is still landscape.
        image.orientation = bufferHeight >= bufferWidth ? .up : .leftMirrored
        let imageSize = bufferHeight >= bufferWidth
            ? CGSize(width: bufferWidth, height: bufferHeight)
            : CGSize(width: bufferHeight, height: bufferWidth)
        liveDetector.process(image) { [weak self] faces, error in
            guard let self = self else { return }
            self.analysisQueue.async {
                self.processingFrame = false
                if error == nil {
                    self.handle(
                        self.classify(faces: faces ?? [], brightness: quality.0,
                                      sharpness: quality.1, imageSize: imageSize),
                        pixelBuffer: pixelBuffer
                    )
                }
            }
        }
    }
}
