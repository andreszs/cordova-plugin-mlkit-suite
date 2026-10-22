import CoreImage
import Foundation
import MLKitSegmentationSelfie
import MLKitVision
import UIKit

struct SelfieSegmentationResult {
    let fileURL: URL
    let width: Int
    let height: Int
}

final class SelfieSegmentationProcessor {
    private let context = CIContext(options: [.useSoftwareRenderer: false])

    func process(_ image: UIImage, maxDimension: Int, outputDirectory: URL,
                 completion: @escaping (Result<SelfieSegmentationResult, Error>) -> Void) {
        guard let prepared = preparedImage(image, maxDimension: maxDimension) else {
            completion(.failure(MlKitSuiteError.processingFailed(
                "Unable to prepare imagePath.", false
            )))
            return
        }

        let options = SelfieSegmenterOptions()
        options.segmenterMode = .singleImage
        options.shouldEnableRawSizeMask = false
        let segmenter = Segmenter.segmenter(options: options)
        let visionImage = VisionImage(image: prepared)
        visionImage.orientation = .up
        segmenter.process(visionImage) { mask, error in
            guard error == nil, let mask = mask else {
                completion(.failure(error ?? MlKitSuiteError.processingFailed(
                    "Selfie segmentation failed.", true
                )))
                return
            }
            do {
                completion(.success(try self.writeCutout(
                    prepared, maskBuffer: mask.buffer, outputDirectory: outputDirectory
                )))
            } catch {
                completion(.failure(error))
            }
        }
    }

    private func preparedImage(_ image: UIImage, maxDimension: Int) -> UIImage? {
        let pixelWidth = max(1, Int((image.size.width * image.scale).rounded()))
        let pixelHeight = max(1, Int((image.size.height * image.scale).rounded()))
        let largest = max(pixelWidth, pixelHeight)
        let scale = largest > maxDimension ? CGFloat(maxDimension) / CGFloat(largest) : 1
        let target = CGSize(
            width: max(1, (CGFloat(pixelWidth) * scale).rounded()),
            height: max(1, (CGFloat(pixelHeight) * scale).rounded())
        )
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = false
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    private func writeCutout(_ sourceImage: UIImage, maskBuffer: CVPixelBuffer,
                             outputDirectory: URL) throws -> SelfieSegmentationResult {
        guard let source = CIImage(image: sourceImage) else {
            throw MlKitSuiteError.processingFailed("Unable to render imagePath.", false)
        }
        let mask = CIImage(cvPixelBuffer: maskBuffer)
        let scaleX = source.extent.width / max(1, mask.extent.width)
        let scaleY = source.extent.height / max(1, mask.extent.height)
        let scaledMask = mask.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))
            .cropped(to: source.extent)
        let background = CIImage(
            color: CIColor(red: 0, green: 0, blue: 0, alpha: 0)
        ).cropped(to: source.extent)
        guard let filter = CIFilter(name: "CIBlendWithMask") else {
            throw MlKitSuiteError.processingFailed("Unable to create the image mask.", false)
        }
        filter.setValue(source, forKey: kCIInputImageKey)
        filter.setValue(background, forKey: kCIInputBackgroundImageKey)
        filter.setValue(scaledMask, forKey: kCIInputMaskImageKey)
        guard let output = filter.outputImage,
              let cgImage = context.createCGImage(output, from: source.extent),
              let data = UIImage(cgImage: cgImage, scale: 1, orientation: .up).pngData() else {
            throw MlKitSuiteError.processingFailed("Unable to encode the segmented PNG.", true)
        }

        try FileManager.default.createDirectory(
            at: outputDirectory,
            withIntermediateDirectories: true,
            attributes: nil
        )
        let fileURL = outputDirectory.appendingPathComponent(
            "selfie-\(UUID().uuidString.lowercased()).png"
        )
        try data.write(to: fileURL, options: .atomic)
        return SelfieSegmentationResult(
            fileURL: fileURL,
            width: cgImage.width,
            height: cgImage.height
        )
    }
}
