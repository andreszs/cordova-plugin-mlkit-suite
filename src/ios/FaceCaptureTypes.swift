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
        eyesOpen = bool("eyesOpen", true)
        neutralExpression = bool("neutralExpression", true)
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

struct FaceCaptureOptions {
    let outputWidth: Int
    let outputHeight: Int
    let jpegQuality: Int
    let showCloseButton: Bool
    let debugMode: Bool
    let instructionText: String
    let timeoutMs: Int
    let checks: FaceCaptureChecks

    init(dictionary: [String: Any]) throws {
        let known = Set([
            "outputWidth", "outputHeight", "jpegQuality", "showCloseButton",
            "debugMode", "instructionText", "timeoutMs", "checks",
            "moduleLoadingMessage"
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
        timeoutMs = try integer("timeoutMs", fallback: 0, minimum: 0, maximum: 600_000)
        if let raw = dictionary["instructionText"], !(raw is NSNull) {
            guard let string = raw as? String else {
                throw MlKitSuiteError.invalidArgument("instructionText must be a string.")
            }
            instructionText = string.trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            instructionText = "Place your face inside the oval"
        }
        if let raw = dictionary["checks"], !(raw is NSNull) {
            guard let checks = raw as? [String: Any] else {
                throw MlKitSuiteError.invalidArgument("checks must be an object.")
            }
            self.checks = try FaceCaptureChecks(dictionary: checks)
        } else {
            self.checks = try FaceCaptureChecks(dictionary: nil)
        }
    }
}

enum FaceCaptureState {
    case searching, multipleFaces, moveCloser, moveAway, centerFace, lookStraight
    case openEyes, neutralExpression, improveLighting, reduceLighting, holdStill
    case ready, capturing
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
    let brightness: CGFloat
    let sharpness: CGFloat
    var stableDurationMs: Int
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

