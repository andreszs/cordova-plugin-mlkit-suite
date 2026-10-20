import AVFoundation
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
    private let options: BarcodeScanOptions
    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "cordova.mlkit.barcode.session")
    private let analysisQueue = DispatchQueue(label: "cordova.mlkit.barcode.analysis")
    private let scanner: BarcodeScanner
    private var previewLayer: AVCaptureVideoPreviewLayer!
    private var overlayView: BarcodeOverlayView!
    private var torchButton: UIButton?
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
            guideColor: options.guideColor
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
        let button = cameraButton(title: "Close")
        button.addTarget(self, action: #selector(cancelByUser), for: .touchUpInside)
        view.addSubview(button)
        NSLayoutConstraint.activate([
            button.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            button.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            button.widthAnchor.constraint(greaterThanOrEqualToConstant: 64),
            button.heightAnchor.constraint(equalToConstant: 44)
        ])
    }

    private func addTorchButton() {
        let button = cameraButton(title: "Torch off")
        button.isHidden = true
        button.addTarget(self, action: #selector(toggleTorch), for: .touchUpInside)
        view.addSubview(button)
        NSLayoutConstraint.activate([
            button.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            button.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            button.widthAnchor.constraint(greaterThanOrEqualToConstant: 88),
            button.heightAnchor.constraint(equalToConstant: 44)
        ])
        torchButton = button
    }

    private func cameraButton(title: String) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
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
                    throw MlKitSuiteError.cameraUnavailable("A rear camera is not available.", false)
                }
                self.camera = camera
                try self.configureFocus(camera)
                let input = try AVCaptureDeviceInput(device: camera)
                guard self.session.canAddInput(input) else {
                    throw MlKitSuiteError.cameraUnavailable("Unable to connect the rear camera.", true)
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
                    throw MlKitSuiteError.cameraUnavailable("Unable to configure camera analysis.", true)
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
                        "Unable to start the rear camera: \(error.localizedDescription)", true
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
        if connection.isVideoMirroringSupported { connection.isVideoMirrored = false }
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
                    self?.torchButton?.setTitle(enabled ? "Torch on" : "Torch off", for: .normal)
                }
            } catch {
                if locked { camera.unlockForConfiguration() }
                DispatchQueue.main.async {
                    self?.overlayView.update(message: "Unable to change the torch", hasCandidate: false)
                }
            }
        }
    }

    private func handle(_ barcodes: [Barcode], imageSize: CGSize) {
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
                    ? "Looking for a barcode"
                    : "Center the barcode inside the frame",
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
            message: "Detected \(format) — hold steady (\(consecutiveFrames)/\(Self.requiredConfirmationFrames))",
            hasCandidate: true
        )
        if consecutiveFrames >= Self.requiredConfirmationFrames {
            analysisFinished = true
            DispatchQueue.main.async { [weak self] in
                self?.finish(BarcodeScanResult(text: text, format: format))
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
            self?.fail(.timeout("No barcode was confirmed before the timeout."))
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

    private func finish(_ result: BarcodeScanResult) {
        guard !completed else { return }
        completed = true
        timeoutItem?.cancel()
        delegate?.barcodeScan(self, didFinish: result)
    }

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
                width: CVPixelBufferGetHeight(imageBuffer),
                height: CVPixelBufferGetWidth(imageBuffer)
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
                    self.handle(barcodes ?? [], imageSize: imageSize)
                } else {
                    self.resetConfirmation()
                    self.updateOverlay(message: "Unable to analyze the camera image", hasCandidate: false)
                }
            }
        }
    }
}
