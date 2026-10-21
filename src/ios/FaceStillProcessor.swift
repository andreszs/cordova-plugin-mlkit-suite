import CoreImage
import CoreVideo
import UIKit

final class FaceStillProcessor {
    private let options: FaceCaptureOptions
    private let imageContext = CIContext(options: [.cacheIntermediates: false])

    init(options: FaceCaptureOptions) {
        self.options = options
    }

    /// Produces the final JPEG from the exact portrait video frame that ML Kit
    /// already detected and accepted. AVCaptureVideoDataOutput physically rotates
    /// this buffer, so this path has no still-photo EXIF orientation to interpret.
    func process(pixelBuffer: CVPixelBuffer, analysis: FaceCaptureAnalysis,
                 directory: URL) throws -> FaceCaptureResult {
        guard let normalizedBounds = analysis.normalizedBounds else {
            throw MlKitSuiteError.captureFailed(
                options.localization.text("face_not_found"), true)
        }
        let input = CIImage(cvPixelBuffer: pixelBuffer)
        guard let cgImage = imageContext.createCGImage(input, from: input.extent) else {
            throw MlKitSuiteError.processingFailed(
                options.localization.text("face_pixels_failed"), true)
        }
        let image = UIImage(cgImage: cgImage, scale: 1, orientation: .up)
        return try createResult(image: image, normalizedBounds: normalizedBounds,
                                analysis: analysis, directory: directory)
    }

    private func createResult(image: UIImage, normalizedBounds: CGRect,
                              analysis: FaceCaptureAnalysis,
                              directory: URL) throws -> FaceCaptureResult {
        guard let cgImage = image.cgImage else {
            throw MlKitSuiteError.processingFailed(
                options.localization.text("face_pixels_failed"), false)
        }
        let imageSize = CGSize(width: cgImage.width, height: cgImage.height)
        let face = CGRect(
            x: normalizedBounds.minX * imageSize.width,
            y: normalizedBounds.minY * imageSize.height,
            width: normalizedBounds.width * imageSize.width,
            height: normalizedBounds.height * imageSize.height
        )
        let crop = cropRect(
            face: face, imageSize: imageSize,
            aspect: CGFloat(options.outputWidth) / CGFloat(options.outputHeight)
        ).integral
        guard let cropped = cgImage.cropping(to: crop) else {
            throw MlKitSuiteError.processingFailed(
                options.localization.text("face_crop_failed"), false)
        }

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let size = CGSize(width: options.outputWidth, height: options.outputHeight)
        let output = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            UIImage(cgImage: cropped).draw(in: CGRect(origin: .zero, size: size))
        }
        let quality = imageQuality(output)
        if options.checks.brightness && quality.brightness < 0.20 {
            throw MlKitSuiteError.captureFailed(
                options.localization.text("face_too_dark"), true)
        }
        if options.checks.brightness && quality.brightness > 0.90 {
            throw MlKitSuiteError.captureFailed(
                options.localization.text("face_too_bright"), true)
        }
        if options.checks.sharpness && quality.sharpness < 0.10 {
            throw MlKitSuiteError.captureFailed(
                options.localization.text("face_not_sharp"), true)
        }
        guard let jpeg = output.jpegData(
            compressionQuality: CGFloat(options.jpegQuality) / 100
        ) else {
            throw MlKitSuiteError.processingFailed(
                options.localization.text("face_encode_failed"), false)
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent(
            "face-\(UUID().uuidString.lowercased()).jpg")
        try jpeg.write(to: file, options: .atomic)
        return FaceCaptureResult(
            fileURL: file,
            width: options.outputWidth,
            height: options.outputHeight,
            brightness: quality.brightness,
            sharpness: quality.sharpness,
            yaw: analysis.yaw,
            pitch: analysis.pitch,
            roll: analysis.roll,
            leftEye: analysis.leftEye,
            rightEye: analysis.rightEye,
            smile: analysis.smile
        )
    }

    private func cropRect(face: CGRect, imageSize: CGSize, aspect: CGFloat) -> CGRect {
        var height = max(face.height * 2.0, face.width / aspect * 1.35)
        height = min(height, imageSize.height)
        var width = height * aspect
        if width > imageSize.width {
            width = imageSize.width
            height = width / aspect
        }
        var x = face.midX - width / 2
        var y = face.midY + face.height * 0.18 - height / 2
        x = max(0, min(x, imageSize.width - width))
        y = max(0, min(y, imageSize.height - height))
        return CGRect(x: x, y: y, width: width, height: height)
    }

    private func imageQuality(_ image: UIImage) -> (brightness: CGFloat, sharpness: CGFloat) {
        guard let cgImage = image.cgImage else { return (0, 0) }
        let width = 160
        let height = max(1, Int(CGFloat(width) * CGFloat(cgImage.height) / CGFloat(cgImage.width)))
        var pixels = [UInt8](repeating: 0, count: width * height)
        guard let context = CGContext(
            data: &pixels, width: width, height: height, bitsPerComponent: 8,
            bytesPerRow: width, space: CGColorSpaceCreateDeviceGray(),
            bitmapInfo: CGImageAlphaInfo.none.rawValue
        ) else { return (0, 0) }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        var sum = 0
        var gradient = 0
        var count = 0
        for y in 1..<height {
            for x in 1..<width {
                let index = y * width + x
                sum += Int(pixels[index])
                gradient += abs(Int(pixels[index]) - Int(pixels[index - 1]))
                count += 1
            }
        }
        guard count > 0 else { return (0, 0) }
        return (
            CGFloat(sum) / CGFloat(count * 255),
            min(1, CGFloat(gradient) / CGFloat(count * 32))
        )
    }
}
