import AVFoundation
import MLKitFaceDetection
import MLKitVision
import UIKit

protocol FaceCaptureViewControllerDelegate: AnyObject {
    func faceCapture(_ controller: FaceCaptureViewController, didFinish result: FaceCaptureResult)
    func faceCaptureDidCancel(_ controller: FaceCaptureViewController)
    func faceCapture(_ controller: FaceCaptureViewController, didFail error: MlKitSuiteError)
}

final class FaceCaptureViewController: UIViewController {
    weak var delegate: FaceCaptureViewControllerDelegate?

    private let options: FaceCaptureOptions
    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.andreszs.mlkitsuite.session")
    private let analysisQueue = DispatchQueue(label: "com.andreszs.mlkitsuite.analysis", qos: .userInitiated)
    private let processingQueue = DispatchQueue(label: "com.andreszs.mlkitsuite.processing", qos: .userInitiated)
    private let liveDetector: FaceDetector
    private let stillProcessor: FaceStillProcessor
    private var previewLayer: AVCaptureVideoPreviewLayer!
    private var overlayView: FaceOverlayView!
    private var photoOutput: AVCapturePhotoOutput?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var processingFrame = false
    private var capturing = false
    private var completed = false
    private var previousBounds: CGRect?
    private var stableSince: CFTimeInterval = 0
    private var stableFrames = 0
    private var timeoutItem: DispatchWorkItem?

    init(options: FaceCaptureOptions) {
        self.options = options
        let detectorOptions = FaceDetectorOptions()
        detectorOptions.performanceMode = .fast
        detectorOptions.landmarkMode = .none
        detectorOptions.classificationMode = .all
        detectorOptions.minFaceSize = 0.15
        detectorOptions.isTrackingEnabled = true
        liveDetector = FaceDetector.faceDetector(options: detectorOptions)
        stillProcessor = FaceStillProcessor(options: options)
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
            instruction: options.instructionText,
            debugMode: options.debugMode,
            initialMessage: options.localization.text("face_searching")
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
        scheduleTimeout()
        configureCamera()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    func abortForWebViewReset() {
        guard !completed else { return }
        completed = true
        timeoutItem?.cancel()
        dismiss(animated: false)
    }

    private func addCloseButton() {
        let button = UIButton(type: .system)
        button.setTitle(options.localization.text("close"), for: .normal)
        button.accessibilityLabel = options.localization.text("close_face")
        button.setTitleColor(.white, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        button.translatesAutoresizingMaskIntoConstraints = false
        button.addTarget(self, action: #selector(cancelByUser), for: .touchUpInside)
        view.addSubview(button)
        NSLayoutConstraint.activate([
            button.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            button.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            button.widthAnchor.constraint(greaterThanOrEqualToConstant: 64),
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

                let photo = AVCapturePhotoOutput()
                guard self.session.canAddOutput(photo) else {
                    throw MlKitSuiteError.cameraUnavailable(
                        self.options.localization.text("photo_capture_config_failed"), true)
                }
                self.session.addOutput(photo)
                photo.isHighResolutionCaptureEnabled = true
                self.photoOutput = photo
                self.session.commitConfiguration()

                self.configure(connection: video.connection(with: .video))
                self.configure(connection: photo.connection(with: .video))
                DispatchQueue.main.async {
                    self.configure(connection: self.previewLayer.connection)
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

    private func configure(connection: AVCaptureConnection?) {
        guard let connection = connection else { return }
        if connection.isVideoOrientationSupported { connection.videoOrientation = .portrait }
        if connection.isVideoMirroringSupported { connection.isVideoMirrored = true }
    }

    private func classify(faces: [Face], brightness: CGFloat, sharpness: CGFloat,
                          imageSize: CGSize) -> FaceCaptureAnalysis {
        guard !faces.isEmpty else {
            resetStability()
            return emptyAnalysis(.searching, brightness, sharpness)
        }
        if options.checks.singleFace && faces.count != 1 {
            resetStability()
            return emptyAnalysis(.multipleFaces, brightness, sharpness)
        }
        let face = faces.max { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }!
        let frame = face.frame
        let bounds = CGRect(
            x: max(0, min(1, frame.minX / imageSize.width)),
            y: max(0, min(1, frame.minY / imageSize.height)),
            width: max(0, min(1, frame.width / imageSize.width)),
            height: max(0, min(1, frame.height / imageSize.height))
        )
        let centerOffset = hypot(bounds.midX - 0.5, bounds.midY - 0.44)
        let leftEye = face.hasLeftEyeOpenProbability ? face.leftEyeOpenProbability : nil
        let rightEye = face.hasRightEyeOpenProbability ? face.rightEyeOpenProbability : nil
        let smile = face.hasSmilingProbability ? face.smilingProbability : nil
        var state: FaceCaptureState
        if options.checks.faceSize && bounds.width < 0.28 { state = .moveCloser }
        else if options.checks.faceSize && bounds.width > 0.62 { state = .moveAway }
        else if options.checks.centering && centerOffset > 0.13 { state = .centerFace }
        else if options.checks.pose && (
            (face.hasHeadEulerAngleY && abs(face.headEulerAngleY) > 11)
                || (face.hasHeadEulerAngleX && abs(face.headEulerAngleX) > 11)
                || (face.hasHeadEulerAngleZ && abs(face.headEulerAngleZ) > 9)
        ) { state = .lookStraight }
        else if options.checks.eyesOpen && (
            leftEye == nil || rightEye == nil || leftEye! < 0.62 || rightEye! < 0.62
        ) { state = .openEyes }
        else if options.checks.neutralExpression && smile != nil && smile! > 0.38 {
            state = .neutralExpression
        } else if options.checks.brightness && brightness < 0.20 { state = .improveLighting }
        else if options.checks.brightness && brightness > 0.88 { state = .reduceLighting }
        else if options.checks.sharpness && sharpness < 0.10 { state = .holdStill }
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
            yaw: face.hasHeadEulerAngleY ? face.headEulerAngleY : 0,
            pitch: face.hasHeadEulerAngleX ? face.headEulerAngleX : 0,
            roll: face.hasHeadEulerAngleZ ? face.headEulerAngleZ : 0,
            leftEye: leftEye, rightEye: rightEye, smile: smile,
            brightness: brightness, sharpness: sharpness, stableDurationMs: stableMs
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

    private func emptyAnalysis(_ state: FaceCaptureState, _ brightness: CGFloat,
                               _ sharpness: CGFloat) -> FaceCaptureAnalysis {
        return FaceCaptureAnalysis(state: state, normalizedBounds: nil, yaw: 0, pitch: 0, roll: 0,
                                   leftEye: nil, rightEye: nil, smile: nil,
                                   brightness: brightness, sharpness: sharpness, stableDurationMs: 0)
    }

    private func handle(_ analysis: FaceCaptureAnalysis) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self, !self.completed, !self.capturing else { return }
            self.overlayView.update(analysis, message: self.message(for: analysis.state))
            if analysis.state == .ready { self.beginCapture(analysis) }
        }
    }

    private func beginCapture(_ analysis: FaceCaptureAnalysis) {
        guard !capturing, !completed, photoOutput != nil else { return }
        capturing = true
        var capturingAnalysis = analysis
        capturingAnalysis.state = .capturing
        overlayView.update(capturingAnalysis,
                           message: options.localization.text("face_capturing"))
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
            self?.capturePhoto()
        }
    }

    private func capturePhoto() {
        guard let output = photoOutput, !completed else { return }
        let settings: AVCapturePhotoSettings
        if output.availablePhotoCodecTypes.contains(.jpeg) {
            settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
        } else {
            settings = AVCapturePhotoSettings()
        }
        settings.isHighResolutionPhotoEnabled = true
        settings.photoQualityPrioritization = .quality
        output.capturePhoto(with: settings, delegate: self)
    }

    private func retry(_ message: String) {
        guard !completed else { return }
        resetStability()
        overlayView.update(nil, message: message)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
            guard let self = self, !self.completed else { return }
            self.capturing = false
            self.overlayView.update(nil,
                                    message: self.options.localization.text("face_searching"))
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
        completed = true
        timeoutItem?.cancel()
        delegate?.faceCaptureDidCancel(self)
    }

    private func finish(_ result: FaceCaptureResult) {
        guard !completed else { return }
        completed = true
        timeoutItem?.cancel()
        delegate?.faceCapture(self, didFinish: result)
    }

    private func fail(_ error: MlKitSuiteError) {
        guard !completed else { return }
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
        case .ready: return options.localization.text("face_ready")
        case .capturing: return options.localization.text("face_capturing")
        }
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
        image.orientation = .leftMirrored
        // ML Kit reports face coordinates in the input buffer's coordinate space;
        // orientation is metadata used for detection, not a pixel-buffer rotation.
        let imageSize = CGSize(width: CVPixelBufferGetWidth(pixelBuffer),
                               height: CVPixelBufferGetHeight(pixelBuffer))
        liveDetector.process(image) { [weak self] faces, error in
            guard let self = self else { return }
            self.analysisQueue.async {
                self.processingFrame = false
                if error == nil {
                    self.handle(self.classify(faces: faces ?? [], brightness: quality.0,
                                              sharpness: quality.1, imageSize: imageSize))
                }
            }
        }
    }
}

extension FaceCaptureViewController: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto,
                     error: Error?) {
        if let error = error {
            DispatchQueue.main.async {
                self.retry(self.options.localization.format(
                    "face_capture_failed_detail", error.localizedDescription
                ))
            }
            return
        }
        guard let data = photo.fileDataRepresentation() else {
            DispatchQueue.main.async {
                self.retry(self.options.localization.text("face_no_photo"))
            }
            return
        }
        processingQueue.async { [weak self] in
            guard let self = self, !self.completed else { return }
            do {
                let directory = try self.cacheDirectory()
                self.stillProcessor.process(data: data, directory: directory) { result in
                    DispatchQueue.main.async {
                        switch result {
                        case .success(let value): self.finish(value)
                        case .failure(let error as MlKitSuiteError):
                            switch error {
                            case .captureFailed(let message, true): self.retry(message)
                            default: self.fail(error)
                            }
                        case .failure(let error):
                            self.fail(.processingFailed(
                                self.options.localization.format(
                                    "face_process_failed", error.localizedDescription
                                ), true
                            ))
                        }
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    self.fail(.processingFailed(
                        self.options.localization.format(
                            "face_prepare_failed", error.localizedDescription
                        ), false
                    ))
                }
            }
        }
    }
}
