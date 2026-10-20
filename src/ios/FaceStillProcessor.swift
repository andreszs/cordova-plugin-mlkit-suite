import CoreGraphics
import MLKitFaceDetection
import MLKitVision
import UIKit

final class FaceStillProcessor {
    private let options: FaceCaptureOptions
    private let detector: FaceDetector

    init(options: FaceCaptureOptions) {
        self.options = options
        let detectorOptions = FaceDetectorOptions()
        detectorOptions.performanceMode = .accurate
        detectorOptions.landmarkMode = .all
        detectorOptions.classificationMode = .all
        detectorOptions.minFaceSize = 0.12
        detector = FaceDetector.faceDetector(options: detectorOptions)
    }

    func process(data: Data, directory: URL, completion: @escaping (Result<FaceCaptureResult, Error>) -> Void) {
        guard let decoded = UIImage(data: data), let normalized = normalizedImage(decoded) else {
            completion(.failure(MlKitSuiteError.processingFailed(
                options.localization.text("face_decode_failed"), true
            )))
            return
        }
        let visionImage = VisionImage(image: normalized)
        visionImage.orientation = .up
        detector.process(visionImage) { [weak self] faces, error in
            guard let self = self else { return }
            if let error = error {
                completion(.failure(MlKitSuiteError.processingFailed(
                    self.options.localization.format(
                        "face_detect_failed", error.localizedDescription
                    ), true
                )))
                return
            }
            do {
                let result = try self.createResult(image: normalized, faces: faces ?? [], directory: directory)
                completion(.success(result))
            } catch {
                completion(.failure(error))
            }
        }
    }

    private func createResult(image: UIImage, faces: [Face], directory: URL) throws -> FaceCaptureResult {
        if options.checks.singleFace && faces.count != 1 {
            throw MlKitSuiteError.captureFailed(
                faces.isEmpty
                    ? options.localization.text("face_not_found")
                    : options.localization.text("face_multiple_found"),
                true
            )
        }
        guard let face = faces.max(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }) else {
            throw MlKitSuiteError.captureFailed(
                options.localization.text("face_not_found"), true)
        }
        try validate(face: face, imageSize: image.size)
        guard let cgImage = image.cgImage else {
            throw MlKitSuiteError.processingFailed(
                options.localization.text("face_pixels_failed"), false)
        }
        let crop = cropRect(face: face.frame, imageSize: image.size,
                            aspect: CGFloat(options.outputWidth) / CGFloat(options.outputHeight)).integral
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
        guard let jpeg = output.jpegData(compressionQuality: CGFloat(options.jpegQuality) / 100) else {
            throw MlKitSuiteError.processingFailed(
                options.localization.text("face_encode_failed"), false)
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("face-\(UUID().uuidString.lowercased()).jpg")
        try jpeg.write(to: file, options: .atomic)
        return FaceCaptureResult(
            fileURL: file,
            width: options.outputWidth,
            height: options.outputHeight,
            brightness: quality.brightness,
            sharpness: quality.sharpness,
            yaw: face.hasHeadEulerAngleY ? face.headEulerAngleY : 0,
            pitch: face.hasHeadEulerAngleX ? face.headEulerAngleX : 0,
            roll: face.hasHeadEulerAngleZ ? face.headEulerAngleZ : 0,
            leftEye: face.hasLeftEyeOpenProbability ? face.leftEyeOpenProbability : nil,
            rightEye: face.hasRightEyeOpenProbability ? face.rightEyeOpenProbability : nil,
            smile: face.hasSmilingProbability ? face.smilingProbability : nil
        )
    }

    private func validate(face: Face, imageSize: CGSize) throws {
        let widthFraction = face.frame.width / imageSize.width
        let centerOffset = hypot(
            face.frame.midX / imageSize.width - 0.5,
            face.frame.midY / imageSize.height - 0.44
        )
        if options.checks.faceSize && widthFraction < 0.22 {
            throw MlKitSuiteError.captureFailed(
                options.localization.text("face_move_closer_retry"), true)
        }
        if options.checks.centering && centerOffset > 0.18 {
            throw MlKitSuiteError.captureFailed(
                options.localization.text("face_center_retry"), true)
        }
        if options.checks.pose && (
            (face.hasHeadEulerAngleY && abs(face.headEulerAngleY) > 13)
                || (face.hasHeadEulerAngleX && abs(face.headEulerAngleX) > 13)
                || (face.hasHeadEulerAngleZ && abs(face.headEulerAngleZ) > 11)
        ) {
            throw MlKitSuiteError.captureFailed(
                options.localization.text("face_look_straight_retry"), true)
        }
        if options.checks.eyesOpen && (
            !face.hasLeftEyeOpenProbability || !face.hasRightEyeOpenProbability
                || face.leftEyeOpenProbability < 0.58 || face.rightEyeOpenProbability < 0.58
        ) {
            throw MlKitSuiteError.captureFailed(
                options.localization.text("face_open_eyes_retry"), true)
        }
        if options.checks.neutralExpression
            && face.hasSmilingProbability && face.smilingProbability > 0.42 {
            throw MlKitSuiteError.captureFailed(
                options.localization.text("face_neutral_retry"), true)
        }
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

    private func normalizedImage(_ image: UIImage) -> UIImage? {
        if image.imageOrientation == .up, image.cgImage != nil { return image }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
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
