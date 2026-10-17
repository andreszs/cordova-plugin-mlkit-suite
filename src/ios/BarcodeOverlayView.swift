import UIKit

final class BarcodeOverlayView: UIView {
    private let instruction: String
    private var message = "Looking for a barcode"
    private var candidateVisible = false

    init(instruction: String) {
        self.instruction = instruction
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func update(message: String, hasCandidate: Bool) {
        self.message = message
        candidateVisible = hasCandidate
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        let guideWidth = rect.width * 0.86
        let guideHeight = min(guideWidth * 0.62, rect.height * 0.44)
        let centerY = rect.height * 0.45
        let guide = CGRect(
            x: rect.midX - guideWidth / 2,
            y: centerY - guideHeight / 2,
            width: guideWidth,
            height: guideHeight
        )

        let shade = UIBezierPath(rect: rect)
        shade.append(UIBezierPath(roundedRect: guide, cornerRadius: 18))
        shade.usesEvenOddFillRule = true
        UIColor.black.withAlphaComponent(0.60).setFill()
        shade.fill()

        let accent = candidateVisible ? UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1) : .white
        let border = UIBezierPath(roundedRect: guide, cornerRadius: 18)
        border.lineWidth = 4
        accent.setStroke()
        border.stroke()

        if !instruction.isEmpty {
            drawCentered(
                instruction,
                in: CGRect(x: 20, y: max(42, guide.minY - 64), width: rect.width - 40, height: 48),
                color: .white
            )
        }
        drawCentered(
            message,
            in: CGRect(x: 20, y: min(rect.height - 94, guide.maxY + 28), width: rect.width - 40, height: 66),
            color: accent
        )
    }

    private func drawCentered(_ text: String, in frame: CGRect, color: UIColor) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        paragraph.lineBreakMode = .byWordWrapping
        (text as NSString).draw(
            in: frame,
            withAttributes: [
                .font: UIFont.systemFont(ofSize: 17, weight: .bold),
                .foregroundColor: color,
                .paragraphStyle: paragraph
            ]
        )
    }
}
