import CoreFoundation
import Foundation
import MLKitBarcodeScanning
import UIKit

enum BarcodeFormatSupport {
    static let names = [
        "AZTEC", "CODABAR", "CODE_39", "CODE_93", "CODE_128", "DATA_MATRIX",
        "EAN_8", "EAN_13", "ITF", "PDF_417", "QR_CODE", "UPC_A", "UPC_E"
    ]

    static func parse(_ raw: Any?) throws -> BarcodeFormat {
        guard let raw = raw, !(raw is NSNull) else { return .all }
        let values: [String]
        if let array = raw as? [Any] {
            guard array.allSatisfy({ $0 is String }) else {
                throw MlKitSuiteError.invalidArgument("formats must contain only strings.")
            }
            values = array.compactMap { $0 as? String }
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
        var selected = Set<String>()
        for rawValue in values {
            var name = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
                .uppercased().replacingOccurrences(of: "-", with: "_")
            if name == "PDF417" { name = "PDF_417" }
            guard names.contains(name) else {
                throw MlKitSuiteError.invalidArgument("Unsupported barcode format: \(rawValue).")
            }
            guard selected.insert(name).inserted else { continue }
            switch name {
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
            default: break
            }
        }
        return result
    }

    static func name(for format: BarcodeFormat) -> String {
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
}
struct BarcodeScanOptions {
    let formats: BarcodeFormat
    let showCloseButton: Bool
    let showTorchButton: Bool
    let instructionText: String
    let guideColor: UIColor?
    let timeoutMs: Int

    init(dictionary: [String: Any]) throws {
        let known = Set([
            "formats", "showCloseButton", "showTorchButton", "instructionText",
            "guideColor", "timeoutMs", "moduleLoadingMessage"
        ])
        for key in dictionary.keys where !known.contains(key) {
            throw MlKitSuiteError.invalidArgument("Unknown barcode scan option: \(key).")
        }

        func boolean(_ key: String, fallback: Bool) throws -> Bool {
            guard let value = dictionary[key] else { return fallback }
            guard let number = value as? NSNumber,
                  CFGetTypeID(number) == CFBooleanGetTypeID() else {
                throw MlKitSuiteError.invalidArgument("\(key) must be boolean.")
            }
            return number.boolValue
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

        formats = try BarcodeFormatSupport.parse(dictionary["formats"])
        showCloseButton = try boolean("showCloseButton", fallback: true)
        showTorchButton = try boolean("showTorchButton", fallback: true)
        timeoutMs = try integer("timeoutMs", fallback: 0, minimum: 0, maximum: 600_000)
        if let raw = dictionary["instructionText"], !(raw is NSNull) {
            guard let string = raw as? String else {
                throw MlKitSuiteError.invalidArgument("instructionText must be a string.")
            }
            instructionText = string.trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            instructionText = "Place a barcode inside the frame"
        }
        if let raw = dictionary["guideColor"], !(raw is NSNull) {
            guard let string = raw as? String,
                  let color = Self.color(from: string) else {
                throw MlKitSuiteError.invalidArgument(
                    "guideColor must be a color in #RRGGBB format."
                )
            }
            guideColor = color
        } else {
            guideColor = nil
        }
    }

    private static func color(from value: String) -> UIColor? {
        guard value.range(of: "^#[0-9A-Fa-f]{6}$", options: .regularExpression) != nil,
              let rgb = UInt32(value.dropFirst(), radix: 16) else { return nil }
        return UIColor(
            red: CGFloat((rgb >> 16) & 0xff) / 255,
            green: CGFloat((rgb >> 8) & 0xff) / 255,
            blue: CGFloat(rgb & 0xff) / 255,
            alpha: 1
        )
    }
}

struct BarcodeScanResult {
    let text: String
    let format: String
}
