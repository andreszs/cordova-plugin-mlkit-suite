import UIKit

final class BarcodeOverlayView: UIView {
    private let instruction: String
    private let guideColor: UIColor?
    private var message: String
    private var candidateVisible = false
    private var successVisible = false
    private var displayedGuide: CGRect?
    private var animationStart = CGRect.zero
    private var animationTarget = CGRect.zero
    private var animationStartedAt: CFTimeInterval = 0
    private var displayLink: CADisplayLink?

    init(instruction: String, guideColor: UIColor?, initialMessage: String) {
        self.instruction = instruction
        self.guideColor = guideColor
        self.message = initialMessage
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

    func contains(barcodeFrame: CGRect, imageSize: CGSize) -> Bool {
        guard let mapped = mappedFrame(barcodeFrame, imageSize: imageSize) else { return false }
        return guideRect(in: bounds).contains(mapped)
    }

    func showSuccess(barcodeFrame: CGRect, imageSize: CGSize, message: String) {
        let initial = guideRect(in: bounds)
        let mapped = mappedFrame(barcodeFrame, imageSize: imageSize) ?? initial
        let padded = mapped.insetBy(dx: -10, dy: -10).intersection(bounds)
        animationStart = initial
        animationTarget = padded.isNull || padded.isEmpty ? initial : padded
        displayedGuide = initial
        self.message = message
        candidateVisible = true
        successVisible = true
        displayLink?.invalidate()
        animationStartedAt = CACurrentMediaTime()
        let link = CADisplayLink(target: self, selector: #selector(advanceSuccessAnimation(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
        setNeedsDisplay()
    }

    private func mappedFrame(_ barcodeFrame: CGRect, imageSize: CGSize) -> CGRect? {
        guard imageSize.width > 0, imageSize.height > 0, !barcodeFrame.isNull,
              !barcodeFrame.isEmpty else { return nil }
        let scale = max(bounds.width / imageSize.width, bounds.height / imageSize.height)
        let offsetX = (bounds.width - imageSize.width * scale) / 2
        let offsetY = (bounds.height - imageSize.height * scale) / 2
        return CGRect(
            x: barcodeFrame.minX * scale + offsetX,
            y: barcodeFrame.minY * scale + offsetY,
            width: barcodeFrame.width * scale,
            height: barcodeFrame.height * scale
        )
    }

    @objc private func advanceSuccessAnimation(_ link: CADisplayLink) {
        let progress = min(1, max(0, (CACurrentMediaTime() - animationStartedAt) / 0.22))
        let eased = 1 - pow(1 - CGFloat(progress), 3)
        displayedGuide = CGRect(
            x: interpolate(animationStart.minX, animationTarget.minX, eased),
            y: interpolate(animationStart.minY, animationTarget.minY, eased),
            width: interpolate(animationStart.width, animationTarget.width, eased),
            height: interpolate(animationStart.height, animationTarget.height, eased)
        )
        setNeedsDisplay()
        if progress >= 1 {
            link.invalidate()
            displayLink = nil
        }
    }

    override func draw(_ rect: CGRect) {
        let guide = successVisible ? (displayedGuide ?? guideRect(in: rect)) : guideRect(in: rect)

        let shade = UIBezierPath(rect: rect)
        shade.append(UIBezierPath(roundedRect: guide, cornerRadius: 18))
        shade.usesEvenOddFillRule = true
        UIColor.black.withAlphaComponent(0.60).setFill()
        shade.fill()

        let accent = candidateVisible
            ? UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1)
            : UIColor.white
        if candidateVisible {
            drawCornerFrame(guide, colors: [accent, accent, accent, accent])
        } else if let guideColor = guideColor {
            drawCornerFrame(guide, colors: [guideColor, guideColor, guideColor, guideColor])
        } else {
            drawCornerFrame(guide, colors: [
                UIColor(red: 0.26, green: 0.52, blue: 0.96, alpha: 1),
                UIColor(red: 0.98, green: 0.74, blue: 0.02, alpha: 1),
                UIColor(red: 0.20, green: 0.66, blue: 0.33, alpha: 1),
                UIColor(red: 0.92, green: 0.26, blue: 0.21, alpha: 1)
            ])
        }

        if !successVisible && !instruction.isEmpty {
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

    private func guideRect(in rect: CGRect) -> CGRect {
        let size = min(rect.width * 0.80, rect.height * 0.50)
        let centerY = rect.height * 0.45
        return CGRect(x: rect.midX - size / 2, y: centerY - size / 2,
                      width: size, height: size)
    }

    private func drawCornerFrame(_ guide: CGRect, colors: [UIColor]) {
        let radius: CGFloat = 22
        let leg = min(guide.width * 0.22, 76)
        let definitions: [[CGPoint]] = [
            [CGPoint(x: guide.minX, y: guide.minY + leg),
             CGPoint(x: guide.minX, y: guide.minY + radius),
             CGPoint(x: guide.minX + radius, y: guide.minY),
             CGPoint(x: guide.minX + leg, y: guide.minY)],
            [CGPoint(x: guide.maxX - leg, y: guide.minY),
             CGPoint(x: guide.maxX - radius, y: guide.minY),
             CGPoint(x: guide.maxX, y: guide.minY + radius),
             CGPoint(x: guide.maxX, y: guide.minY + leg)],
            [CGPoint(x: guide.minX, y: guide.maxY - leg),
             CGPoint(x: guide.minX, y: guide.maxY - radius),
             CGPoint(x: guide.minX + radius, y: guide.maxY),
             CGPoint(x: guide.minX + leg, y: guide.maxY)],
            [CGPoint(x: guide.maxX - leg, y: guide.maxY),
             CGPoint(x: guide.maxX - radius, y: guide.maxY),
             CGPoint(x: guide.maxX, y: guide.maxY - radius),
             CGPoint(x: guide.maxX, y: guide.maxY - leg)]
        ]
        for (index, points) in definitions.enumerated() {
            let path = UIBezierPath()
            path.move(to: points[0])
            path.addLine(to: points[1])
            path.addQuadCurve(to: points[2], controlPoint: CGPoint(
                x: index == 0 || index == 2 ? guide.minX : guide.maxX,
                y: index < 2 ? guide.minY : guide.maxY
            ))
            path.addLine(to: points[3])
            path.lineWidth = 4
            path.lineCapStyle = .round
            path.lineJoinStyle = .round
            colors[index].setStroke()
            path.stroke()
        }
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

    private func interpolate(_ start: CGFloat, _ end: CGFloat, _ progress: CGFloat) -> CGFloat {
        start + (end - start) * progress
    }

    deinit {
        displayLink?.invalidate()
    }
}
