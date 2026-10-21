import MLKitTextRecognition
import MLKitVision
import UIKit

final class TextRecognitionProcessor {
    private struct Candidate {
        let text: String
        let score: Int
    }

    private let recognizer = TextRecognizer.textRecognizer(options: TextRecognizerOptions())

    func process(_ image: UIImage, completion: @escaping (Result<String, Error>) -> Void) {
        guard let cgImage = image.cgImage else {
            completion(.failure(NSError(
                domain: "cordova-plugin-mlkit-suite.text-recognition",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Unable to access the decoded image pixels."]
            )))
            return
        }

        // Work from the encoded pixels and explicitly describe their orientation to ML Kit.
        // Gallery images can be visually upright while the photographed text itself is rotated.
        let rawImage = UIImage(cgImage: cgImage, scale: image.scale, orientation: .up)
        let preferred = cardinalOrientation(image.imageOrientation)
        let orientations = [preferred, .up, .right, .down, .left].reduce(
            into: [UIImage.Orientation]()
        ) { result, orientation in
            if !result.contains(orientation) {
                result.append(orientation)
            }
        }
        process(rawImage, orientations: orientations, index: 0, best: nil,
                lastError: nil, completion: completion)
    }

    private func process(
        _ image: UIImage,
        orientations: [UIImage.Orientation],
        index: Int,
        best: Candidate?,
        lastError: Error?,
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        guard index < orientations.count else {
            if let best = best {
                completion(.success(best.text))
            } else {
                completion(.failure(lastError ?? NSError(
                    domain: "cordova-plugin-mlkit-suite.text-recognition",
                    code: 2,
                    userInfo: [NSLocalizedDescriptionKey: "Text recognition failed."]
                )))
            }
            return
        }

        let visionImage = VisionImage(image: image)
        visionImage.orientation = orientations[index]
        recognizer.process(visionImage) { [self] result, error in
            var nextBest = best
            if let result = result {
                let candidate = Candidate(text: result.text, score: score(result))
                if nextBest == nil || candidate.score > nextBest!.score {
                    nextBest = candidate
                }
            }
            process(image, orientations: orientations, index: index + 1, best: nextBest,
                    lastError: error ?? lastError, completion: completion)
        }
    }

    private func score(_ result: Text) -> Int {
        let scalars = result.text.unicodeScalars
        let alphanumeric = scalars.filter { CharacterSet.alphanumerics.contains($0) }.count
        let visible = scalars.filter { !CharacterSet.whitespacesAndNewlines.contains($0) }.count
        let lines = result.blocks.reduce(0) { $0 + $1.lines.count }
        let elements = result.blocks.reduce(0) { total, block in
            total + block.lines.reduce(0) { $0 + $1.elements.count }
        }
        return alphanumeric * 10 + visible * 2 + lines * 3 + elements
    }

    private func cardinalOrientation(_ orientation: UIImage.Orientation) -> UIImage.Orientation {
        switch orientation {
        case .up, .upMirrored:
            return .up
        case .right, .rightMirrored:
            return .right
        case .down, .downMirrored:
            return .down
        case .left, .leftMirrored:
            return .left
        @unknown default:
            return .up
        }
    }
}
