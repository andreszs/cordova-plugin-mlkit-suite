import AVFoundation
import CoreImage
import MLKitBarcodeScanning
import MLKitVision
import UIKit

protocol BarcodeScanViewControllerDelegate: AnyObject {
    func barcodeScan(_ controller: BarcodeScanViewController, didFinish result: BarcodeScanResult)
    func barcodeScanDidCancel(_ controller: BarcodeScanViewController)
    func barcodeScan(_ controller: BarcodeScanViewController, didFail error: MlKitSuiteError)
}
final class BarcodeScanViewController: UIViewController {
    weak var delegate: BarcodeScanViewControllerDelegate?

    private static let requiredConfirmationFrames = 2
    private static let successFeedbackDuration: TimeInterval = 0.45
    private let options: BarcodeScanOptions
    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "cordova.mlkit.barcode.session")
    private let analysisQueue = DispatchQueue(label: "cordova.mlkit.barcode.analysis")
    private let scanner: BarcodeScanner
    private let imageContext = CIContext()
    private var previewLayer: AVCaptureVideoPreviewLayer!
    private var overlayView: BarcodeOverlayView!
    private var closeButton: UIButton?
    private var torchButton: UIButton?
    private var successBeepPlayer: AVAudioPlayer?
    private var camera: AVCaptureDevice?
    private var processingFrame = false
    private var analysisFinished = false
    private var candidateText: String?
    private var candidateFormat: String?
    private var consecutiveFrames = 0
    private var completed = false
    private var timeoutItem: DispatchWorkItem?

    init(options: BarcodeScanOptions) {
        self.options = options
        scanner = BarcodeScanner.barcodeScanner(options: BarcodeScannerOptions(formats: options.formats))
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

        overlayView = BarcodeOverlayView(
            instruction: options.instructionText,
            guideColor: options.guideColor,
            initialMessage: options.localization.text("barcode_searching")
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
        if options.showTorchButton { addTorchButton() }
        scheduleTimeout()
        configureCamera()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        timeoutItem?.cancel()
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    func abortForWebViewReset() {
        guard !completed else { return }
        completed = true
        timeoutItem?.cancel()
        analysisQueue.async { [weak self] in self?.analysisFinished = true }
        dismiss(animated: false)
    }

    private func addCloseButton() {
        let button = cameraIconButton(
            systemName: "xmark",
            accessibilityLabel: options.localization.text("close_barcode")
        )
        button.addTarget(self, action: #selector(cancelByUser), for: .touchUpInside)
        view.addSubview(button)
        NSLayoutConstraint.activate([
            button.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            button.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            button.widthAnchor.constraint(equalToConstant: 44),
            button.heightAnchor.constraint(equalToConstant: 44)
        ])
        closeButton = button
    }

    private func addTorchButton() {
        let button = cameraIconButton(
            systemName: "bolt.fill",
            accessibilityLabel: options.localization.text("turn_torch_on")
        )
        button.isHidden = true
        button.addTarget(self, action: #selector(toggleTorch), for: .touchUpInside)
        view.addSubview(button)
        NSLayoutConstraint.activate([
            button.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            button.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            button.widthAnchor.constraint(equalToConstant: 44),
            button.heightAnchor.constraint(equalToConstant: 44)
        ])
        torchButton = button
    }

    private func cameraIconButton(systemName: String, accessibilityLabel: String) -> UIButton {
        let button = UIButton(type: .system)
        let configuration = UIImage.SymbolConfiguration(pointSize: 20, weight: .bold)
        button.setImage(UIImage(systemName: systemName, withConfiguration: configuration), for: .normal)
        button.tintColor = UIColor(red: 0.48, green: 0.52, blue: 0.59, alpha: 1)
        button.backgroundColor = UIColor.white.withAlphaComponent(0.96)
        button.layer.cornerRadius = 22
        button.accessibilityLabel = accessibilityLabel
        button.translatesAutoresizingMaskIntoConstraints = false
        return button
    }

    private func configureCamera() {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            var beganConfiguration = false
            do {
                self.session.beginConfiguration()
                beganConfiguration = true
                if self.session.canSetSessionPreset(.hd1920x1080) {
                    self.session.sessionPreset = .hd1920x1080
                } else {
                    self.session.sessionPreset = .high
                }
                guard let camera = AVCaptureDevice.default(
                    .builtInWideAngleCamera, for: .video, position: .back
                ) else {
                    throw MlKitSuiteError.cameraUnavailable(
                        self.options.localization.text("rear_camera_missing"), false)
                }
                self.camera = camera
                try self.configureFocus(camera)
                let input = try AVCaptureDeviceInput(device: camera)
                guard self.session.canAddInput(input) else {
                    throw MlKitSuiteError.cameraUnavailable(
                        self.options.localization.text("rear_camera_connect_failed"), true)
                }
                self.session.addInput(input)

                let output = AVCaptureVideoDataOutput()
                output.alwaysDiscardsLateVideoFrames = true
                output.videoSettings = [
                    kCVPixelBufferPixelFormatTypeKey as String:
                        kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
                ]
                output.setSampleBufferDelegate(self, queue: self.analysisQueue)
                guard self.session.canAddOutput(output) else {
                    throw MlKitSuiteError.cameraUnavailable(
                        self.options.localization.text("camera_analysis_config_failed"), true)
                }
                self.session.addOutput(output)
                self.configure(connection: output.connection(with: .video))
                self.session.commitConfiguration()
                beganConfiguration = false

                DispatchQueue.main.async {
                    self.configure(connection: self.previewLayer.connection)
                    self.torchButton?.isHidden = !camera.hasTorch
                }
                self.session.startRunning()
            } catch let error as MlKitSuiteError {
                if beganConfiguration { self.session.commitConfiguration() }
                DispatchQueue.main.async { self.fail(error) }
            } catch {
                if beganConfiguration { self.session.commitConfiguration() }
                DispatchQueue.main.async {
                    self.fail(.cameraUnavailable(
                        self.options.localization.format(
                            "rear_camera_start_failed", error.localizedDescription
                        ), true
                    ))
                }
            }
        }
    }

    private func configureFocus(_ camera: AVCaptureDevice) throws {
        try camera.lockForConfiguration()
        defer { camera.unlockForConfiguration() }
        if camera.isFocusModeSupported(.continuousAutoFocus) {
            camera.focusMode = .continuousAutoFocus
        }
        if camera.isExposureModeSupported(.continuousAutoExposure) {
            camera.exposureMode = .continuousAutoExposure
        }
    }

    private func configure(connection: AVCaptureConnection?) {
        guard let connection = connection else { return }
        if connection.isVideoOrientationSupported { connection.videoOrientation = .portrait }
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = false
        }
    }

    @objc private func toggleTorch() {
        guard let camera = camera, camera.hasTorch else { return }
        sessionQueue.async { [weak self] in
            var locked = false
            do {
                try camera.lockForConfiguration()
                locked = true
                let enabled = camera.torchMode != .on
                if enabled {
                    try camera.setTorchModeOn(level: AVCaptureDevice.maxAvailableTorchLevel)
                } else {
                    camera.torchMode = .off
                }
                camera.unlockForConfiguration()
                locked = false
                DispatchQueue.main.async {
                    self?.torchButton?.tintColor = enabled
                        ? UIColor(red: 0.98, green: 0.74, blue: 0.02, alpha: 1)
                        : UIColor(red: 0.48, green: 0.52, blue: 0.59, alpha: 1)
                    self?.torchButton?.accessibilityLabel = self?.options.localization.text(
                        enabled ? "turn_torch_off" : "turn_torch_on"
                    )
                }
            } catch {
                if locked { camera.unlockForConfiguration() }
                DispatchQueue.main.async {
                    self?.overlayView.update(
                        message: self?.options.localization.text("torch_change_failed") ?? "",
                        hasCandidate: false
                    )
                }
            }
        }
    }

    private func handle(_ barcodes: [Barcode], imageSize: CGSize,
                        sampleBuffer: CMSampleBuffer) {
        guard !analysisFinished else { return }
        let decoded = barcodes.filter {
            guard let text = $0.rawValue ?? $0.displayValue else { return false }
            return !text.isEmpty
        }
        let barcode = decoded.first { candidate in
            var contained = false
            DispatchQueue.main.sync { [weak self] in
                contained = self?.overlayView.contains(
                    barcodeFrame: candidate.frame, imageSize: imageSize
                ) ?? false
            }
            return contained
        }
        guard let barcode = barcode, let text = barcode.rawValue ?? barcode.displayValue else {
            resetConfirmation()
            updateOverlay(
                message: decoded.isEmpty
                    ? options.localization.text("barcode_searching")
                    : options.localization.text("barcode_center"),
                hasCandidate: false
            )
            return
        }

        let format = BarcodeFormatSupport.name(for: barcode.format)
        if text == candidateText && format == candidateFormat {
            consecutiveFrames += 1
        } else {
            candidateText = text
            candidateFormat = format
            consecutiveFrames = 1
        }
        updateOverlay(
            message: options.localization.format(
                "barcode_detected", format, consecutiveFrames, Self.requiredConfirmationFrames
            ),
            hasCandidate: true
        )
        if consecutiveFrames >= Self.requiredConfirmationFrames {
            analysisFinished = true
            let frozenImage = makeFrozenImage(from: sampleBuffer)
            DispatchQueue.main.async { [weak self] in
                self?.finish(
                    BarcodeScanResult(text: text, format: format),
                    barcodeFrame: barcode.frame,
                    imageSize: imageSize,
                    frozenImage: frozenImage
                )
            }
        }
    }

    private func resetConfirmation() {
        candidateText = nil
        candidateFormat = nil
        consecutiveFrames = 0
    }

    private func updateOverlay(message: String, hasCandidate: Bool) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self, !self.completed else { return }
            self.overlayView.update(message: message, hasCandidate: hasCandidate)
        }
    }

    private func scheduleTimeout() {
        guard options.timeoutMs > 0 else { return }
        let item = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.fail(.timeout(self.options.localization.text("barcode_timeout")))
        }
        timeoutItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Double(options.timeoutMs) / 1000,
            execute: item
        )
    }

    @objc private func cancelByUser() {
        guard !completed else { return }
        completed = true
        timeoutItem?.cancel()
        analysisQueue.async { [weak self] in self?.analysisFinished = true }
        delegate?.barcodeScanDidCancel(self)
    }

    private func finish(_ result: BarcodeScanResult, barcodeFrame: CGRect,
                        imageSize: CGSize, frozenImage: UIImage?) {
        guard !completed else { return }
        completed = true
        timeoutItem?.cancel()
        if let frozenImage = frozenImage {
            let frozenView = UIImageView(frame: view.bounds)
            frozenView.image = frozenImage
            frozenView.contentMode = .scaleAspectFill
            frozenView.clipsToBounds = true
            frozenView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.insertSubview(frozenView, belowSubview: overlayView)
        }
        closeButton?.isHidden = true
        torchButton?.isHidden = true
        overlayView.showSuccess(
            barcodeFrame: barcodeFrame,
            imageSize: imageSize,
            message: options.localization.text("barcode_success")
        )
        playSuccessBeep()
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Self.successFeedbackDuration
        ) { [weak self] in
            guard let self = self else { return }
            self.delegate?.barcodeScan(self, didFinish: result)
        }
    }

    private func makeFrozenImage(from sampleBuffer: CMSampleBuffer) -> UIImage? {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return nil }
        let image = CIImage(cvPixelBuffer: pixelBuffer)
        guard let cgImage = imageContext.createCGImage(image, from: image.extent) else { return nil }
        return UIImage(cgImage: cgImage, scale: 1, orientation: .right)
    }

    private func playSuccessBeep() {
        guard options.beepEnabled else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.ambient, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
            let player = try AVAudioPlayer(data: Self.successBeepData)
            player.volume = 1
            player.prepareToPlay()
            successBeepPlayer = player
            player.play()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
                guard let self = self, self.successBeepPlayer === player else { return }
                player.stop()
                self.successBeepPlayer = nil
                try? session.setActive(false, options: [.notifyOthersOnDeactivation])
            }
        } catch {
            successBeepPlayer = nil
        }
    }

    private static let successBeepData: Data = {
        let sampleRate: UInt32 = 22_050
        let sampleCount = Int(sampleRate / 10)
        let dataSize = UInt32(sampleCount * 2)
        var data = Data()

        func appendASCII(_ value: String) {
            data.append(contentsOf: value.utf8)
        }
        func appendUInt16(_ value: UInt16) {
            var littleEndian = value.littleEndian
            Swift.withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
        }
        func appendUInt32(_ value: UInt32) {
            var littleEndian = value.littleEndian
            Swift.withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
        }
        func appendInt16(_ value: Int16) {
            appendUInt16(UInt16(bitPattern: value))
        }

        appendASCII("RIFF")
        appendUInt32(36 + dataSize)
        appendASCII("WAVE")
        appendASCII("fmt ")
        appendUInt32(16)
        appendUInt16(1)
        appendUInt16(1)
        appendUInt32(sampleRate)
        appendUInt32(sampleRate * 2)
        appendUInt16(2)
        appendUInt16(16)
        appendASCII("data")
        appendUInt32(dataSize)

        for index in 0..<sampleCount {
            let time = Double(index) / Double(sampleRate)
            let remaining = Double(sampleCount - index) / Double(sampleRate)
            let envelope = min(1, time / 0.005) * min(1, remaining / 0.025)
            let value = sin(2 * Double.pi * 1_800 * time)
                * envelope * Double(Int16.max) * 0.55
            appendInt16(Int16(value.rounded()))
        }
        return data
    }()

    private func fail(_ error: MlKitSuiteError) {
        guard !completed else { return }
        completed = true
        timeoutItem?.cancel()
        analysisQueue.async { [weak self] in self?.analysisFinished = true }
        delegate?.barcodeScan(self, didFail: error)
    }
}

extension BarcodeScanViewController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard !analysisFinished, !processingFrame else { return }
        processingFrame = true
        let image = VisionImage(buffer: sampleBuffer)
        image.orientation = .right
        let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer)
        let imageSize: CGSize
        if let imageBuffer = imageBuffer {
            imageSize = CGSize(
                width: CVPixelBufferGetWidth(imageBuffer),
                height: CVPixelBufferGetHeight(imageBuffer)
            )
        } else {
            imageSize = .zero
        }
        scanner.process(image) { [weak self] barcodes, error in
            guard let self = self else { return }
            self.analysisQueue.async {
                self.processingFrame = false
                guard !self.analysisFinished else { return }
                if error == nil {
                    self.handle(
                        barcodes ?? [], imageSize: imageSize, sampleBuffer: sampleBuffer
                    )
                } else {
                    self.resetConfirmation()
                    self.updateOverlay(
                        message: self.options.localization.text("barcode_analyze_failed"),
                        hasCandidate: false
                    )
                }
            }
        }
    }
}
