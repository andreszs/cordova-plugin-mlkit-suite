import AVFoundation
import Foundation
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
            "face": [
                "capture": true,
                "frontCamera": true,
                "autoCapture": true
            ],
            "barcode": [
                "scan": false
            ]
        ]
        commandDelegate.send(
            CDVPluginResult(status: CDVCommandStatus.ok, messageAs: payload),
            callbackId: command.callbackId
        )
    }

    @objc(barcodeScan:)
    func barcodeScan(_ command: CDVInvokedUrlCommand) {
        sendError(callbackId: command.callbackId, code: "UNSUPPORTED_PLATFORM",
                  message: "Barcode scanning is currently available on Android only.",
                  recoverable: false)
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
