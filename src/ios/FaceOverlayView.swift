import UIKit

final class FaceOverlayView: UIView {
    private let instruction: String
    private let debugMode: Bool
    private var analysis: FaceCaptureAnalysis?
    private var message = "Looking for a face"

    init(instruction: String, debugMode: Bool) {
        self.instruction = instruction
        self.debugMode = debugMode
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func update(_ analysis: FaceCaptureAnalysis?, message: String) {
        self.analysis = analysis
        self.message = message
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        let guide = guideRect()
        context.saveGState()
        context.setFillColor(UIColor.black.withAlphaComponent(0.60).cgColor)
        let shade = UIBezierPath(rect: bounds)
        shade.append(UIBezierPath(ovalIn: guide))
        shade.usesEvenOddFillRule = true
        shade.fill()
        context.restoreGState()

        let color = stateColor(analysis?.state ?? .searching)
        let oval = UIBezierPath(ovalIn: guide)
        oval.lineWidth = 4
        color.setStroke()
        oval.stroke()

        if debugMode, let analysis = analysis, let box = analysis.normalizedBounds {
            let mapped = CGRect(
                x: (1 - box.maxX) * bounds.width,
                y: box.minY * bounds.height,
                width: box.width * bounds.width,
                height: box.height * bounds.height
            )
            let face = UIBezierPath(rect: mapped)
            face.lineWidth = 2
            UIColor.white.withAlphaComponent(0.85).setStroke()
            face.stroke()
        }

        if !instruction.isEmpty {
            drawText(instruction, centerY: max(safeAreaInsets.top + 34, guide.minY - 34),
                     font: .systemFont(ofSize: 17, weight: .semibold), color: .white)
        }
        drawText(message, centerY: min(bounds.height - safeAreaInsets.bottom - 42, guide.maxY + 48),
                 font: .systemFont(ofSize: 18, weight: .semibold), color: color)

        if debugMode, let analysis = analysis {
            let values = String(format:
                "yaw %.1f  pitch %.1f  roll %.1f\nlight %.2f  sharp %.2f  stable %d ms",
                analysis.yaw, analysis.pitch, analysis.roll, analysis.brightness,
                analysis.sharpness, analysis.stableDurationMs)
            drawText(values, centerY: bounds.height - safeAreaInsets.bottom - 68,
                     font: .monospacedSystemFont(ofSize: 12, weight: .regular),
                     color: UIColor(white: 0.88, alpha: 1))
        }
    }

    private func guideRect() -> CGRect {
        var width = min(bounds.width * 0.78, bounds.height * 0.54)
        var height = width * 1.28
        if height > bounds.height * 0.65 {
            height = bounds.height * 0.65
            width = height / 1.28
        }
        return CGRect(x: bounds.midX - width / 2, y: bounds.height * 0.43 - height / 2,
                      width: width, height: height)
    }

    private func drawText(_ value: String, centerY: CGFloat, font: UIFont, color: UIColor) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font, .foregroundColor: color, .paragraphStyle: paragraph
        ]
        let size = CGSize(width: max(100, bounds.width - 48), height: 80)
        let rect = CGRect(x: 24, y: centerY - size.height / 2, width: size.width, height: size.height)
        value.draw(in: rect, withAttributes: attributes)
    }

    private func stateColor(_ state: FaceCaptureState) -> UIColor {
        switch state {
        case .ready, .capturing: return UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1)
        case .searching, .multipleFaces: return UIColor(red: 0.94, green: 0.27, blue: 0.27, alpha: 1)
        default: return UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 1)
        }
    }
}
