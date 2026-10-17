import AVFoundation
import Foundation
import MLKitBarcodeScanning
import MLKitTextRecognition
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
    private weak var captureController: FaceCaptureViewController?

    @objc(getCapabilities:)
    func getCapabilities(_ command: CDVInvokedUrlCommand) {
        let payload: [String: Any] = [
            "platform": "ios",
            "offline": true,
            "modelDelivery": "bundled",
            "modelDownloadStrategy": "bundled",
            "face": [
                "capture": true,
                "frontCamera": true,
                "autoCapture": true,
                "mode": "bundled",
                "bundled": true
            ],
            "barcode": [
                "scan": false,
                "scanImage": true,
                "mode": "bundled",
                "bundled": true,
                "formats": Self.barcodeFormatNames
            ],
            "document": [
                "scan": false,
                "prepare": false
            ],
            "text": [
                "recognize": true,
                "mode": "bundled",
                "bundled": true,
                "scripts": ["LATIN"]
            ]
        ]
        commandDelegate.send(
            CDVPluginResult(status: CDVCommandStatus.ok, messageAs: payload),
            callbackId: command.callbackId
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
        sendError(callbackId: command.callbackId, code: "UNSUPPORTED_PLATFORM",
                  message: "Barcode scanning is currently available on Android only.",
                  recoverable: false)
    }

    @objc(documentScan:)
    func documentScan(_ command: CDVInvokedUrlCommand) {
        sendError(callbackId: command.callbackId, code: "UNSUPPORTED_PLATFORM",
                  message: "ML Kit Document Scanner is currently available on Android only.",
                  recoverable: false)
    }

    @objc(prepareDocumentScanner:)
    func prepareDocumentScanner(_ command: CDVInvokedUrlCommand) {
        sendError(callbackId: command.callbackId, code: "UNSUPPORTED_PLATFORM",
                  message: "ML Kit Document Scanner is currently available on Android only.",
                  recoverable: false)
    }

    @objc(recognizeText:)
    func recognizeText(_ command: CDVInvokedUrlCommand) {
        guard let rawPath = command.argument(at: 0) as? String,
              !rawPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                      message: "imagePath must be a non-empty string.", recoverable: false)
            return
        }
        commandDelegate.run(inBackground: { [weak self] in
            guard let self = self else { return }
            do {
                let image = try self.visionImage(rawPath)
                let recognizer = TextRecognizer.textRecognizer(options: TextRecognizerOptions())
                recognizer.process(image) { result, error in
                    if error != nil || result == nil {
                        self.sendError(callbackId: command.callbackId, code: "PROCESSING_FAILED",
                                       message: "Unable to recognize text in the image.", recoverable: true)
                        return
                    }
                    self.commandDelegate.send(
                        CDVPluginResult(status: CDVCommandStatus.ok, messageAs: result?.text ?? ""),
                        callbackId: command.callbackId
                    )
                }
            } catch {
                self.sendError(callbackId: command.callbackId, code: "INVALID_IMAGE",
                               message: "Unable to open imagePath.", recoverable: false)
            }
        })
    }

    @objc(scanBarcodes:)
    func scanBarcodes(_ command: CDVInvokedUrlCommand) {
        guard let rawPath = command.argument(at: 0) as? String,
              !rawPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            sendError(callbackId: command.callbackId, code: "INVALID_ARGUMENT",
                      message: "imagePath must be a non-empty string.", recoverable: false)
            return
        }
        do {
            let dictionary = command.argument(at: 1) as? [String: Any] ?? [:]
            let formats = try Self.barcodeFormats(dictionary)
            commandDelegate.run(inBackground: { [weak self] in
                guard let self = self else { return }
                do {
                    let image = try self.visionImage(rawPath)
                    let options = BarcodeScannerOptions(formats: formats)
                    let scanner = BarcodeScanner.barcodeScanner(options: options)
                    scanner.process(image) { barcodes, error in
                        if error != nil || barcodes == nil {
                            self.sendError(callbackId: command.callbackId, code: "PROCESSING_FAILED",
                                           message: "Unable to scan barcodes in the image.", recoverable: true)
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
                                   message: "Unable to open imagePath.", recoverable: false)
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
            sendError(callbackId: command.callbackId, code: "BUSY",
                      message: "A face capture session is already active.", recoverable: true)
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
                                             message: "Camera permission was denied.", recoverable: true)
                    }
                }
            }
        case .denied, .restricted:
            finishWithError(code: "CAMERA_PERMISSION_DENIED",
                            message: "Camera permission was denied.", recoverable: true)
        @unknown default:
            finishWithError(code: "CAMERA_PERMISSION_DENIED",
                            message: "Unable to determine camera permission.", recoverable: true)
        }
    }

    private func presentCapture(_ options: FaceCaptureOptions) {
        guard pendingCallbackId != nil else { return }
        guard let host = viewController else {
            finishWithError(code: "CAMERA_UNAVAILABLE",
                            message: "The application view controller is unavailable.", recoverable: false)
            return
        }
        let controller = FaceCaptureViewController(options: options)
        controller.delegate = self
        captureController = controller
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
            "appliedChecks": options.checks.dictionary
        ]
    }

    private func imageWebURL(fileName: String) -> String {
        let scheme = viewController?.appScheme ?? "app"
        let hostname = (commandDelegate.settings["hostname"] as? String) ?? "localhost"
        return "\(scheme)://\(hostname)\(Self.imagePathPrefix)\(fileName)"
    }

    private func visionImage(_ rawPath: String) throws -> VisionImage {
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
        let visionImage = VisionImage(image: image)
        visionImage.orientation = image.imageOrientation
        return visionImage
    }

    private static let barcodeFormatNames = [
        "AZTEC", "CODABAR", "CODE_39", "CODE_93", "CODE_128", "DATA_MATRIX",
        "EAN_8", "EAN_13", "ITF", "PDF_417", "QR_CODE", "UPC_A", "UPC_E"
    ]

    private static func barcodeFormats(_ dictionary: [String: Any]) throws -> BarcodeFormat {
        for key in dictionary.keys where key != "formats" && key != "moduleLoadingMessage" {
            throw MlKitSuiteError.invalidArgument("Unknown barcode image option: \(key).")
        }
        guard let raw = dictionary["formats"], !(raw is NSNull) else { return .all }
        let values: [String]
        if let array = raw as? [String] {
            values = array
        } else if let string = raw as? String {
            values = string.components(separatedBy: ",")
        } else {
            throw MlKitSuiteError.invalidArgument(
                "formats must be an array of strings or a comma-separated string."
            )
        }
        guard !values.isEmpty else {
            throw MlKitSuiteError.invalidArgument("formats must not be empty.")
        }
        var result: BarcodeFormat = []
        for rawValue in values {
            let name = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
                .uppercased().replacingOccurrences(of: "-", with: "_")
            switch name == "PDF417" ? "PDF_417" : name {
            case "AZTEC": result.insert(.aztec)
            case "CODABAR": result.insert(.codaBar)
            case "CODE_39": result.insert(.code39)
            case "CODE_93": result.insert(.code93)
            case "CODE_128": result.insert(.code128)
            case "DATA_MATRIX": result.insert(.dataMatrix)
            case "EAN_8": result.insert(.EAN8)
            case "EAN_13": result.insert(.EAN13)
            case "ITF": result.insert(.ITF)
            case "PDF_417": result.insert(.PDF417)
            case "QR_CODE": result.insert(.qrCode)
            case "UPC_A": result.insert(.UPCA)
            case "UPC_E": result.insert(.UPCE)
            default:
                throw MlKitSuiteError.invalidArgument("Unsupported barcode format: \(rawValue).")
            }
        }
        return result
    }

    private func barcodeDictionary(_ barcode: Barcode) -> [String: Any] {
        let frame = barcode.frame
        let points: [[String: Double]] = barcode.cornerPoints.map {
            let point = $0.cgPointValue
            return ["x": Double(point.x), "y": Double(point.y)]
        }
        return [
            "text": barcode.rawValue ?? barcode.displayValue ?? "",
            "format": barcodeFormatName(barcode.format),
            "valueType": barcode.valueType.rawValue,
            "boundingBox": [
                "x": Double(frame.origin.x), "y": Double(frame.origin.y),
                "width": Double(frame.size.width), "height": Double(frame.size.height)
            ],
            "cornerPoints": points
        ]
    }

    private func barcodeFormatName(_ format: BarcodeFormat) -> String {
        switch format {
        case .aztec: return "AZTEC"
        case .codaBar: return "CODABAR"
        case .code39: return "CODE_39"
        case .code93: return "CODE_93"
        case .code128: return "CODE_128"
        case .dataMatrix: return "DATA_MATRIX"
        case .EAN8: return "EAN_8"
        case .EAN13: return "EAN_13"
        case .ITF: return "ITF"
        case .PDF417: return "PDF_417"
        case .qrCode: return "QR_CODE"
        case .UPCA: return "UPC_A"
        case .UPCE: return "UPC_E"
        default: return "UNKNOWN"
        }
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

    private func sendErrorPayload(callbackId: String, payload: [String: Any]) {
        commandDelegate.send(CDVPluginResult(status: CDVCommandStatus.error, messageAs: payload),
                             callbackId: callbackId)
    }

    private func clearPending() {
        pendingCallbackId = nil
        pendingOptions = nil
        captureController = nil
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
        let expression = try? NSRegularExpression(pattern: "^face-[0-9a-fA-F-]+\\.jpg$")
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
                        "Content-Type": "image/jpeg",
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
        guard let callbackId = pendingCallbackId else { return }
        controller.dismiss(animated: true) { [weak self] in
            guard let self = self else { return }
            self.clearPending()
            self.sendError(callbackId: callbackId, code: "USER_CANCELLED",
                           message: "Face capture was cancelled by the user.", recoverable: true)
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
