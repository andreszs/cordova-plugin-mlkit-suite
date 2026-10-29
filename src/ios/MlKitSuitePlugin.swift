import AVFoundation
import Foundation
import MLKitBarcodeScanning
import MLKitImageLabeling
import MLKitSegmentationSelfie
import MLKitVision
import UIKit
import WebKit

#if canImport(Cordova)
import Cordova
#endif

@objc(MlKitSuitePlugin)
final class MlKitSuitePlugin: CDVPlugin {
    private static let imagePathPrefix = "/_mlkit_suite_image_/"
    private var pendingCallbackId: String?
    private var pendingOptions: FaceCaptureOptions?
    private var pendingBarcodeOptions: BarcodeScanOptions?
    private weak var captureController: FaceCaptureViewController?
    private weak var barcodeController: BarcodeScanViewController?

    @objc(getCapabilities:)
    func getCapabilities(_ command: CDVInvokedUrlCommand) {
        let payload: [String: Any] = [
            "platform": "ios",
            "offline": true,
            "modelDelivery": "bundled",
            "modelDownloadStrategy": "bundled",
            "localization": [
                "default": "auto",
                "supportedLocales": MlKitLocalization.supportedLocales,
                "deviceLocale": Locale.preferredLanguages.first ?? "en",
                "resolvedLocale": localization([:]).locale
            ],
            "face": [
                "capture": true,
                "frontCamera": true,
                "autoCapture": true,
                "mode": "bundled",
                "bundled": true,
                "ready": true,
                "modelStatus": "bundled"
            ],
            "barcode": [
                "scan": true,
                "scanImage": true,
                "mode": "bundled",
                "bundled": true,
                "ready": true,
                "modelStatus": "bundled",
                "resolution": "1920x1080",
                "confirmationFrames": 2,
                "formats": BarcodeFormatSupport.names
            ],
            "document": [
                "scan": false,
                "prepare": false,
                "mode": "unsupported",
                "ready": false,
                "modelStatus": "unsupported"
            ],
            "imageLabeling": [
                "label": true,
                "mode": "bundled",
                "bundled": true,
                "ready": true,
                "modelStatus": "bundled",
                "defaultModel": true,
                "labels": "400+"
            ],
            "selfieSegmentation": [
                "segment": true,
                "mode": "bundled",
                "bundled": true,
                "ready": true,
                "modelStatus": "bundled",
                "output": "transparent-png"
            ],
            "subjectSegmentation": [
                "segment": false,
                "mode": "unsupported",
                "bundled": false,
                "ready": false,
                "modelStatus": "unsupported"
            ],
            "text": [
                "recognize": true,
                "mode": "bundled",
                "bundled": true,
                "ready": true,
                "modelStatus": "bundled",
                "scripts": ["LATIN"]
            ]
        ]
        commandDelegate.send(
            CDVPluginResult(status: CDVCommandStatus.ok, messageAs: payload),
            callbackId: command.callbackId
        )
    }

    @objc(labelImage:)
    func labelImage(_ command: CDVInvokedUrlCommand) {
        let dictionary = command.argument(at: 1) as? [String: Any] ?? [:]
        let localization: MlKitLocalization
        do {
            localization = try MlKitLocalization(dictionary: dictionary)
            for key in dictionary.keys where key != "confidenceThreshold"
                && key != "moduleLoadingMessage" && key != "locale" {
                throw MlKitSuiteError.invalidArgument("Unknown image labeling option: \(key).")
            }
        } catch let error as MlKitSuiteError {
            sendErrorPayload(callbackId: command.callbackId, payload: error.payload)
            return
        } catch {
            sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                      message: error.localizedDescription, recoverable: false)
            return
        }
        guard let rawPath = command.argument(at: 0) as? String,
              !rawPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                      message: "imagePath must be a non-empty string.", recoverable: false)
            return
        }

        let threshold: NSNumber?
        if let rawThreshold = dictionary["confidenceThreshold"] {
            guard let number = rawThreshold as? NSNumber,
                  CFGetTypeID(number) != CFBooleanGetTypeID(),
                  number.doubleValue.isFinite,
                  (0.0...1.0).contains(number.doubleValue) else {
                sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                          message: "confidenceThreshold must be a number from 0 to 1.",
                          recoverable: false)
                return
            }
            threshold = number
        } else {
            threshold = nil
        }

        commandDelegate.run(inBackground: { [weak self] in
            guard let self = self else { return }
            do {
                let image = try self.visionImage(rawPath)
                let options = ImageLabelerOptions()
                if let threshold = threshold {
                    options.confidenceThreshold = threshold
                }
                let labeler = ImageLabeler.imageLabeler(options: options)
                labeler.process(image) { labels, error in
                    if error != nil || labels == nil {
                        self.sendError(
                            callbackId: command.callbackId, code: "PROCESSING_FAILED",
                            message: localization.text("image_labeling_failed"), recoverable: true
                        )
                        return
                    }
                    let payload: [[String: Any]] = (labels ?? []).map {
                        [
                            "text": $0.text,
                            "confidence": Double($0.confidence),
                            "index": $0.index
                        ]
                    }
                    self.commandDelegate.send(
                        CDVPluginResult(status: CDVCommandStatus.ok, messageAs: payload),
                        callbackId: command.callbackId
                    )
                }
            } catch {
                self.sendError(callbackId: command.callbackId, code: "INVALID_IMAGE",
                               message: localization.text("image_open_failed"), recoverable: false)
            }
        })
    }

    @objc(segmentSelfie:)
    func segmentSelfie(_ command: CDVInvokedUrlCommand) {
        let dictionary = command.argument(at: 1) as? [String: Any] ?? [:]
        let localization: MlKitLocalization
        do {
            localization = try MlKitLocalization(dictionary: dictionary)
            for key in dictionary.keys where key != "maxDimension"
                && key != "moduleLoadingMessage" && key != "locale" {
                throw MlKitSuiteError.invalidArgument(
                    "Unknown selfie segmentation option: \(key)."
                )
            }
        } catch let error as MlKitSuiteError {
            sendErrorPayload(callbackId: command.callbackId, payload: error.payload)
            return
        } catch {
            sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                      message: error.localizedDescription, recoverable: false)
            return
        }
        guard let rawPath = command.argument(at: 0) as? String,
              !rawPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                      message: "imagePath must be a non-empty string.", recoverable: false)
            return
        }
        let maxDimension: Int
        if let rawValue = dictionary["maxDimension"] {
            guard let number = rawValue as? NSNumber,
                  CFGetTypeID(number) != CFBooleanGetTypeID(),
                  number.doubleValue.rounded() == number.doubleValue,
                  (256...4096).contains(number.intValue) else {
                sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                          message: "maxDimension must be an integer from 256 to 4096.",
                          recoverable: false)
                return
            }
            maxDimension = number.intValue
        } else {
            maxDimension = 2048
        }

        commandDelegate.run(inBackground: { [weak self] in
            guard let self = self else { return }
            do {
                let image = try self.decodedImage(rawPath)
                let processor = SelfieSegmentationProcessor()
                processor.process(
                    image,
                    maxDimension: maxDimension,
                    outputDirectory: try self.cacheDirectory()
                ) { result in
                    switch result {
                    case .success(let value):
                        let attributes = try? FileManager.default.attributesOfItem(
                            atPath: value.fileURL.path
                        )
                        let bytes = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
                        self.commandDelegate.send(
                            CDVPluginResult(status: CDVCommandStatus.ok, messageAs: [
                                "status": "success",
                                "imageUri": self.imageWebURL(
                                    fileName: value.fileURL.lastPathComponent
                                ),
                                "imagePath": value.fileURL.path,
                                "width": value.width,
                                "height": value.height,
                                "mimeType": "image/png",
                                "bytes": bytes
                            ]),
                            callbackId: command.callbackId
                        )
                    case .failure:
                        self.sendError(
                            callbackId: command.callbackId,
                            code: "PROCESSING_FAILED",
                            message: localization.text("selfie_segmentation_failed"),
                            recoverable: true
                        )
                    }
                }
            } catch {
                self.sendError(callbackId: command.callbackId, code: "INVALID_IMAGE",
                               message: localization.text("image_open_failed"), recoverable: false)
            }
        })
    }

    @objc(segmentSubjects:)
    func segmentSubjects(_ command: CDVInvokedUrlCommand) {
        let localization = localization(command.argument(at: 1) as? [String: Any] ?? [:])
        sendError(
            callbackId: command.callbackId,
            code: "UNSUPPORTED_PLATFORM",
            message: localization.text("subject_unsupported_platform"),
            recoverable: false
        )
    }

    @objc(prepareModels:)
    func prepareModels(_ command: CDVInvokedUrlCommand) {
        commandDelegate.send(
            CDVPluginResult(
                status: CDVCommandStatus.ok,
                messageAs: ["status": "ready", "alreadyAvailable": true]
            ),
            callbackId: command.callbackId
        )
    }

    @objc(barcodeScan:)
    func barcodeScan(_ command: CDVInvokedUrlCommand) {
        guard pendingCallbackId == nil else {
            let localization = localization(command.argument(at: 0) as? [String: Any] ?? [:])
            sendError(callbackId: command.callbackId, code: "BUSY",
                      message: localization.text("busy"), recoverable: true)
            return
        }
        do {
            let dictionary = command.argument(at: 0) as? [String: Any] ?? [:]
            let options = try BarcodeScanOptions(dictionary: dictionary)
            pendingCallbackId = command.callbackId
            pendingBarcodeOptions = options
            requestCameraAndPresentBarcode(options)
        } catch let error as MlKitSuiteError {
            sendErrorPayload(callbackId: command.callbackId, payload: error.payload)
        } catch {
            sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                      message: error.localizedDescription, recoverable: false)
        }
    }

    @objc(documentScan:)
    func documentScan(_ command: CDVInvokedUrlCommand) {
        let localization = localization(command.argument(at: 0) as? [String: Any] ?? [:])
        sendError(callbackId: command.callbackId, code: "UNSUPPORTED_PLATFORM",
                  message: localization.text("document_unsupported_platform"),
                  recoverable: false)
    }

    @objc(prepareDocumentScanner:)
    func prepareDocumentScanner(_ command: CDVInvokedUrlCommand) {
        let localization = localization(command.argument(at: 0) as? [String: Any] ?? [:])
        sendError(callbackId: command.callbackId, code: "UNSUPPORTED_PLATFORM",
                  message: localization.text("document_unsupported_platform"),
                  recoverable: false)
    }

    @objc(recognizeText:)
    func recognizeText(_ command: CDVInvokedUrlCommand) {
        let dictionary = command.argument(at: 1) as? [String: Any] ?? [:]
        let localization: MlKitLocalization
        do {
            localization = try MlKitLocalization(dictionary: dictionary)
        } catch let error as MlKitSuiteError {
            sendErrorPayload(callbackId: command.callbackId, payload: error.payload)
            return
        } catch {
            sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                      message: error.localizedDescription, recoverable: false)
            return
        }
        guard let rawPath = command.argument(at: 0) as? String,
              !rawPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                      message: "imagePath must be a non-empty string.", recoverable: false)
            return
        }
        commandDelegate.run(inBackground: { [weak self] in
            guard let self = self else { return }
            do {
                let image = try self.decodedImage(rawPath)
                let processor = TextRecognitionProcessor()
                processor.process(image) { result in
                    switch result {
                    case .success(let text):
                        self.commandDelegate.send(
                            CDVPluginResult(status: CDVCommandStatus.ok, messageAs: text),
                            callbackId: command.callbackId
                        )
                    case .failure:
                        self.sendError(
                            callbackId: command.callbackId, code: "PROCESSING_FAILED",
                            message: localization.text("text_recognition_failed"), recoverable: true
                        )
                    }
                }
            } catch {
                self.sendError(callbackId: command.callbackId, code: "INVALID_IMAGE",
                               message: localization.text("image_open_failed"), recoverable: false)
            }
        })
    }

    @objc(scanBarcodes:)
    func scanBarcodes(_ command: CDVInvokedUrlCommand) {
        let dictionary = command.argument(at: 1) as? [String: Any] ?? [:]
        let localization: MlKitLocalization
        do {
            localization = try MlKitLocalization(dictionary: dictionary)
        } catch let error as MlKitSuiteError {
            sendErrorPayload(callbackId: command.callbackId, payload: error.payload)
            return
        } catch {
            sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                      message: error.localizedDescription, recoverable: false)
            return
        }
        guard let rawPath = command.argument(at: 0) as? String,
              !rawPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                      message: "imagePath must be a non-empty string.", recoverable: false)
            return
        }
        do {
            for key in dictionary.keys where key != "formats"
                && key != "moduleLoadingMessage" && key != "locale" {
                throw MlKitSuiteError.invalidArgument("Unknown barcode image option: \(key).")
            }
            let formats = try BarcodeFormatSupport.parse(dictionary["formats"])
            commandDelegate.run(inBackground: { [weak self] in
                guard let self = self else { return }
                do {
                    let image = try self.visionImage(rawPath)
                    let options = BarcodeScannerOptions(formats: formats)
                    let scanner = BarcodeScanner.barcodeScanner(options: options)
                    scanner.process(image) { barcodes, error in
                        if error != nil || barcodes == nil {
                            self.sendError(
                                callbackId: command.callbackId, code: "PROCESSING_FAILED",
                                message: localization.text("barcode_image_failed"), recoverable: true
                            )
                            return
                        }
                        let payload = (barcodes ?? []).map { self.barcodeDictionary($0) }
                        self.commandDelegate.send(
                            CDVPluginResult(status: CDVCommandStatus.ok, messageAs: payload),
                            callbackId: command.callbackId
                        )
                    }
                } catch {
                    self.sendError(callbackId: command.callbackId, code: "INVALID_IMAGE",
                                   message: localization.text("image_open_failed"), recoverable: false)
                }
            })
        } catch let error as MlKitSuiteError {
            sendErrorPayload(callbackId: command.callbackId, payload: error.payload)
        } catch {
            sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                      message: error.localizedDescription, recoverable: false)
        }
    }

    @objc(faceCapture:)
    func faceCapture(_ command: CDVInvokedUrlCommand) {
        guard pendingCallbackId == nil else {
            let localization = localization(command.argument(at: 0) as? [String: Any] ?? [:])
            sendError(callbackId: command.callbackId, code: "BUSY",
                      message: localization.text("busy"), recoverable: true)
            return
        }
        do {
            let dictionary = command.argument(at: 0) as? [String: Any] ?? [:]
            let options = try FaceCaptureOptions(dictionary: dictionary)
            pendingCallbackId = command.callbackId
            pendingOptions = options
            requestCameraAndPresent(options)
        } catch let error as MlKitSuiteError {
            sendErrorPayload(callbackId: command.callbackId, payload: error.payload)
        } catch {
            sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                      message: error.localizedDescription, recoverable: false)
        }
    }

    override func onReset() {
        captureController?.abortForWebViewReset()
        barcodeController?.abortForWebViewReset()
        clearPending()
    }

    private func requestCameraAndPresent(_ options: FaceCaptureOptions) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            presentCapture(options)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    if granted {
                        self.presentCapture(options)
                    } else {
                        self.finishWithError(code: "CAMERA_PERMISSION_DENIED",
                                             message: options.localization.text(
                                                "camera_permission_denied"
                                             ), recoverable: true)
                    }
                }
            }
        case .denied, .restricted:
            finishWithError(code: "CAMERA_PERMISSION_DENIED",
                            message: options.localization.text("camera_permission_denied"),
                            recoverable: true)
        @unknown default:
            finishWithError(code: "CAMERA_PERMISSION_DENIED",
                            message: options.localization.text("camera_permission_unknown"),
                            recoverable: true)
        }
    }

    private func presentCapture(_ options: FaceCaptureOptions) {
        guard pendingCallbackId != nil else { return }
        guard let host = viewController else {
            finishWithError(code: "CAMERA_UNAVAILABLE",
                            message: options.localization.text("view_controller_unavailable"),
                            recoverable: false)
            return
        }
        let controller = FaceCaptureViewController(options: options)
        controller.delegate = self
        captureController = controller
        host.present(controller, animated: true)
    }

    private func requestCameraAndPresentBarcode(_ options: BarcodeScanOptions) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            presentBarcodeScanner(options)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    if granted {
                        self.presentBarcodeScanner(options)
                    } else {
                        self.finishWithError(code: "CAMERA_PERMISSION_DENIED",
                                             message: options.localization.text(
                                                "camera_permission_denied"
                                             ), recoverable: true)
                    }
                }
            }
        case .denied, .restricted:
            finishWithError(code: "CAMERA_PERMISSION_DENIED",
                            message: options.localization.text("camera_permission_denied"),
                            recoverable: true)
        @unknown default:
            finishWithError(code: "CAMERA_PERMISSION_DENIED",
                            message: options.localization.text("camera_permission_unknown"),
                            recoverable: true)
        }
    }

    private func presentBarcodeScanner(_ options: BarcodeScanOptions) {
        guard pendingCallbackId != nil else { return }
        guard let host = viewController else {
            finishWithError(code: "CAMERA_UNAVAILABLE",
                            message: options.localization.text("view_controller_unavailable"),
                            recoverable: false)
            return
        }
        let controller = BarcodeScanViewController(options: options)
        controller.delegate = self
        barcodeController = controller
        host.present(controller, animated: true)
    }

    private func resultDictionary(_ result: FaceCaptureResult, options: FaceCaptureOptions) -> [String: Any] {
        let attributes = try? FileManager.default.attributesOfItem(atPath: result.fileURL.path)
        let bytes = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return [
            "status": "success",
            "imageUri": imageWebURL(fileName: result.fileURL.lastPathComponent),
            "imagePath": result.fileURL.path,
            "width": result.width,
            "height": result.height,
            "mimeType": "image/jpeg",
            "bytes": bytes,
            "capturedAt": formatter.string(from: Date()),
            "quality": [
                "brightness": round(result.brightness),
                "sharpness": round(result.sharpness)
            ],
            "face": [
                "yaw": round(result.yaw),
                "pitch": round(result.pitch),
                "roll": round(result.roll),
                "leftEyeOpenProbability": nullable(result.leftEye),
                "rightEyeOpenProbability": nullable(result.rightEye),
                "smilingProbability": nullable(result.smile)
            ],
            "appliedChecks": options.checks.dictionary,
            "completedGestures": options.gestures.map { $0.rawValue },
            "captureAfterGesture": options.captureAfterGesture
        ]
    }

    private func imageWebURL(fileName: String) -> String {
        let scheme = viewController?.appScheme ?? "app"
        let hostname = (commandDelegate.settings["hostname"] as? String) ?? "localhost"
        return "\(scheme)://\(hostname)\(Self.imagePathPrefix)\(fileName)"
    }

    private func decodedImage(_ rawPath: String) throws -> UIImage {
        let value = rawPath.trimmingCharacters(in: .whitespacesAndNewlines)
        let path: String
        if let url = URL(string: value), url.isFileURL {
            path = url.path
        } else if let url = URL(string: value), url.path.hasPrefix(Self.imagePathPrefix) {
            let fileName = url.lastPathComponent
            let cache = try cacheDirectory().standardizedFileURL
            let file = cache.appendingPathComponent(fileName).standardizedFileURL
            guard file.deletingLastPathComponent() == cache else {
                throw MlKitSuiteError.invalidArgument("imagePath is outside the plugin cache.")
            }
            path = file.path
        } else {
            path = value
        }
        guard let image = UIImage(contentsOfFile: path) else {
            throw MlKitSuiteError.processingFailed("Unable to decode imagePath.", false)
        }
        return image
    }

    private func visionImage(_ rawPath: String) throws -> VisionImage {
        let image = try decodedImage(rawPath)
        let visionImage = VisionImage(image: image)
        visionImage.orientation = image.imageOrientation
        return visionImage
    }

    private func barcodeDictionary(_ barcode: Barcode) -> [String: Any] {
        let frame = barcode.frame
        let points: [[String: Double]] = (barcode.cornerPoints ?? []).map {
            let point = $0.cgPointValue
            return ["x": Double(point.x), "y": Double(point.y)]
        }
        return [
            "text": barcode.rawValue ?? barcode.displayValue ?? "",
            "format": BarcodeFormatSupport.name(for: barcode.format),
            "valueType": barcode.valueType.rawValue,
            "boundingBox": [
                "x": Double(frame.origin.x), "y": Double(frame.origin.y),
                "width": Double(frame.size.width), "height": Double(frame.size.height)
            ],
            "cornerPoints": points
        ]
    }

    private func finishWithError(code: String, message: String, recoverable: Bool) {
        guard let callbackId = pendingCallbackId else { return }
        clearPending()
        sendError(callbackId: callbackId, code: code, message: message, recoverable: recoverable)
    }

    private func sendError(callbackId: String, code: String, message: String, recoverable: Bool) {
        sendErrorPayload(callbackId: callbackId, payload: [
            "code": code, "message": message, "recoverable": recoverable
        ])
    }

    private func localization(_ dictionary: [String: Any]) -> MlKitLocalization {
        return (try? MlKitLocalization(dictionary: dictionary))
            ?? (try! MlKitLocalization())
    }

    private func sendErrorPayload(callbackId: String, payload: [String: Any]) {
        commandDelegate.send(CDVPluginResult(status: CDVCommandStatus.error, messageAs: payload),
                             callbackId: callbackId)
    }

    private func clearPending() {
        pendingCallbackId = nil
        pendingOptions = nil
        pendingBarcodeOptions = nil
        captureController = nil
        barcodeController = nil
    }

    private func round(_ value: CGFloat) -> Double {
        return (Double(value) * 10_000).rounded() / 10_000
    }

    private func nullable(_ value: CGFloat?) -> Any {
        guard let value = value else { return NSNull() }
        return round(value)
    }

    @objc(overrideSchemeTask:)
    func overrideSchemeTask(_ task: WKURLSchemeTask) -> Bool {
        let path = task.request.url?.path ?? ""
        guard path.hasPrefix(Self.imagePathPrefix) else { return false }
        let fileName = String(path.dropFirst(Self.imagePathPrefix.count))
        let expression = try? NSRegularExpression(
            pattern: "^(face-[0-9a-fA-F-]+\\.jpg|selfie-[0-9a-fA-F-]+\\.png)$"
        )
        let range = NSRange(fileName.startIndex..<fileName.endIndex, in: fileName)
        guard expression?.firstMatch(in: fileName, range: range) != nil else {
            failSchemeTask(task)
            return true
        }
        do {
            let cache = try cacheDirectory().standardizedFileURL
            let file = cache.appendingPathComponent(fileName).standardizedFileURL
            guard file.deletingLastPathComponent() == cache else {
                failSchemeTask(task)
                return true
            }
            let data = try Data(contentsOf: file, options: .mappedIfSafe)
            guard let url = task.request.url,
                  let response = HTTPURLResponse(
                    url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                    headerFields: [
                        "Content-Type": fileName.hasSuffix(".png") ? "image/png" : "image/jpeg",
                        "Content-Length": String(data.count),
                        "Cache-Control": "no-store"
                    ]
                  ) else {
                failSchemeTask(task)
                return true
            }
            task.didReceive(response)
            task.didReceive(data)
            task.didFinish()
        } catch {
            failSchemeTask(task)
        }
        return true
    }

    @objc(stopSchemeTask:)
    func stopSchemeTask(_ task: WKURLSchemeTask) {
        // Cached files are read synchronously; there is no pending transfer to cancel.
    }

    private func failSchemeTask(_ task: WKURLSchemeTask) {
        task.didFailWithError(NSError(
            domain: "cordova-plugin-mlkit-suite", code: 404,
            userInfo: [NSLocalizedDescriptionKey: "Temporary image is unavailable."]
        ))
    }

    private func cacheDirectory() throws -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("mlkit-suite", isDirectory: true)
    }
}

extension MlKitSuitePlugin: FaceCaptureViewControllerDelegate {
    func faceCapture(_ controller: FaceCaptureViewController, didFinish result: FaceCaptureResult) {
        guard let callbackId = pendingCallbackId, let options = pendingOptions else { return }
        let payload = resultDictionary(result, options: options)
        controller.dismiss(animated: true) { [weak self] in
            guard let self = self else { return }
            self.clearPending()
            self.commandDelegate.send(
                CDVPluginResult(status: CDVCommandStatus.ok, messageAs: payload),
                callbackId: callbackId
            )
        }
    }

    func faceCaptureDidCancel(_ controller: FaceCaptureViewController) {
        guard let callbackId = pendingCallbackId, let options = pendingOptions else { return }
        let message = options.localization.text("face_cancelled")
        controller.dismiss(animated: true) { [weak self] in
            guard let self = self else { return }
            self.clearPending()
            self.sendError(callbackId: callbackId, code: "USER_CANCELLED",
                           message: message, recoverable: true)
        }
    }

    func faceCapture(_ controller: FaceCaptureViewController, didFail error: MlKitSuiteError) {
        guard let callbackId = pendingCallbackId else { return }
        let payload = error.payload
        controller.dismiss(animated: true) { [weak self] in
            guard let self = self else { return }
            self.clearPending()
            self.sendErrorPayload(callbackId: callbackId, payload: payload)
        }
    }
}

extension MlKitSuitePlugin: BarcodeScanViewControllerDelegate {
    func barcodeScan(_ controller: BarcodeScanViewController, didFinish result: BarcodeScanResult) {
        guard let callbackId = pendingCallbackId, pendingBarcodeOptions != nil else { return }
        let payload: [String: Any] = [
            "status": "success",
            "text": result.text,
            "format": result.format
        ]
        controller.dismiss(animated: true) { [weak self] in
            guard let self = self else { return }
            self.clearPending()
            self.commandDelegate.send(
                CDVPluginResult(status: CDVCommandStatus.ok, messageAs: payload),
                callbackId: callbackId
            )
        }
    }

    func barcodeScanDidCancel(_ controller: BarcodeScanViewController) {
        guard let callbackId = pendingCallbackId,
              let options = pendingBarcodeOptions else { return }
        let message = options.localization.text("barcode_cancelled")
        controller.dismiss(animated: true) { [weak self] in
            guard let self = self else { return }
            self.clearPending()
            self.sendError(callbackId: callbackId, code: "USER_CANCELLED",
                           message: message, recoverable: true)
        }
    }

    func barcodeScan(_ controller: BarcodeScanViewController, didFail error: MlKitSuiteError) {
        guard let callbackId = pendingCallbackId else { return }
        let payload = error.payload
        controller.dismiss(animated: true) { [weak self] in
            guard let self = self else { return }
            self.clearPending()
            self.sendErrorPayload(callbackId: callbackId, payload: payload)
        }
    }
}
