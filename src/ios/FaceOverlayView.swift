import UIKit

final class FaceOverlayView: UIView {
    private let instruction: String
    private let debugMode: Bool
    private let checks: FaceCaptureChecks
    private var analysis: FaceCaptureAnalysis?
    private var message: String

    init(instruction: String, debugMode: Bool, checks: FaceCaptureChecks,
         initialMessage: String) {
        self.instruction = instruction
        self.debugMode = debugMode
        self.checks = checks
        self.message = initialMessage
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
            drawDebugValues(analysis)
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

    private func drawDebugValues(_ analysis: FaceCaptureAnalysis) {
        let values = NSMutableAttributedString()
        let font = UIFont.monospacedSystemFont(ofSize: 11, weight: .semibold)
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        let valid = UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1)
        let invalid = UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 1)
        let unavailable = UIColor(white: 0.72, alpha: 1)

        func append(_ text: String, accepted: Bool?, enabled: Bool, suffix: String = "  ") {
            let color: UIColor
            if !enabled || accepted == nil { color = unavailable }
            else { color = accepted! ? valid : invalid }
            values.append(NSAttributedString(
                string: text + suffix,
                attributes: [.font: font, .foregroundColor: color, .paragraphStyle: paragraph]
            ))
        }

        if let box = analysis.normalizedBounds {
            let center = hypot(box.midX - 0.5, box.midY - 0.44)
            append(String(format: "size %.2f", box.width),
                   accepted: box.width >= 0.28 && box.width <= 0.62,
                   enabled: checks.faceSize)
            append(String(format: "center %.2f", center), accepted: center <= 0.13,
                   enabled: checks.centering, suffix: "\n")
            append(String(format: "yaw %.1f", analysis.yaw), accepted: abs(analysis.yaw) <= 11,
                   enabled: checks.pose)
            append(String(format: "pitch %.1f", analysis.pitch), accepted: abs(analysis.pitch) <= 11,
                   enabled: checks.pose)
            append(String(format: "roll %.1f", analysis.roll), accepted: abs(analysis.roll) <= 9,
                   enabled: checks.pose, suffix: "\n")
        } else {
            append("size —", accepted: nil, enabled: checks.faceSize)
            append("center —", accepted: nil, enabled: checks.centering, suffix: "\n")
            append("yaw —", accepted: nil, enabled: checks.pose)
            append("pitch —", accepted: nil, enabled: checks.pose)
            append("roll —", accepted: nil, enabled: checks.pose, suffix: "\n")
        }

        append("eye L " + probability(analysis.leftEye),
               accepted: analysis.leftEye.map { $0 >= 0.62 }, enabled: checks.eyesOpen)
        append("R " + probability(analysis.rightEye),
               accepted: analysis.rightEye.map { $0 >= 0.62 }, enabled: checks.eyesOpen)
        append("smile " + probability(analysis.smile),
               accepted: analysis.smile.map { $0 <= 0.38 },
               enabled: checks.neutralExpression, suffix: "\n")
        append(String(format: "light %.2f", analysis.brightness),
               accepted: analysis.brightness >= 0.20 && analysis.brightness <= 0.88,
               enabled: checks.brightness)
        append(String(format: "sharp %.2f", analysis.sharpness),
               accepted: analysis.sharpness >= 0.10, enabled: checks.sharpness)
        append("stable \(analysis.stableDurationMs)ms",
               accepted: analysis.stableDurationMs >= 650, enabled: checks.stability, suffix: "")

        let height: CGFloat = 76
        let rect = CGRect(x: 16,
                          y: bounds.height - safeAreaInsets.bottom - height - 8,
                          width: max(100, bounds.width - 32), height: height)
        values.draw(in: rect)
    }

    private func probability(_ value: CGFloat?) -> String {
        guard let value = value else { return "—" }
        return String(format: "%.2f", value)
    }

    private func stateColor(_ state: FaceCaptureState) -> UIColor {
        switch state {
        case .ready, .capturing: return UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1)
        case .searching, .multipleFaces: return UIColor(red: 0.94, green: 0.27, blue: 0.27, alpha: 1)
        default: return UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 1)
        }
    }
}
