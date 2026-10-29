import UIKit

final class FaceOverlayView: UIView {
    private static let progressTransitionDuration: CFTimeInterval = 0.26
    private struct OverlayLayout {
        let guide: CGRect
        let messageArea: CGRect
        let progressCenterY: CGFloat
        let debugArea: CGRect
    }

    private let localization: MlKitLocalization
    private let debugMode: Bool
    private let checks: FaceCaptureChecks
    private let progressSegments: Int
    private let captureAfterGesture: Int
    private let hasGestures: Bool
    private let deviceVerticalCheckEnabled: Bool
    private var analysis: FaceCaptureAnalysis?
    private var message: String
    private var progressCompleted = 0
    private var progressStartColors: [UIColor] = []
    private var progressTargetColors: [UIColor] = []
    private var progressTransitionStartedAt: [CFTimeInterval] = []
    private var deviceTiltDegrees: Double?
    private var deviceVerticalReady: Bool

    init(localization: MlKitLocalization, debugMode: Bool, checks: FaceCaptureChecks,
         initialMessage: String, progressSegments: Int, captureAfterGesture: Int,
         hasGestures: Bool,
         deviceVerticalCheckEnabled: Bool) {
        self.localization = localization
        self.debugMode = debugMode
        self.checks = checks
        self.message = initialMessage
        self.progressSegments = max(0, progressSegments)
        self.captureAfterGesture = max(0, captureAfterGesture)
        self.hasGestures = hasGestures
        self.deviceVerticalCheckEnabled = deviceVerticalCheckEnabled
        self.deviceVerticalReady = !deviceVerticalCheckEnabled
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

    func setProgressCompleted(_ value: Int) {
        progressCompleted = max(0, min(progressSegments, value))
        setNeedsDisplay()
    }

    func updateDeviceTilt(_ degrees: Double, ready: Bool) {
        deviceTiltDegrees = degrees
        deviceVerticalReady = ready
        setNeedsDisplay()
    }

    func centeringGuide() -> CGRect {
        return overlayLayout().guide
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        let layout = overlayLayout()
        let guide = layout.guide
        context.saveGState()
        context.setFillColor(UIColor.black.withAlphaComponent(0.60).cgColor)
        let shade = UIBezierPath(rect: bounds)
        shade.append(UIBezierPath(ovalIn: guide))
        shade.usesEvenOddFillRule = true
        shade.fill()
        context.restoreGState()

        let color = stateColor(analysis?.state ?? .searching)
        let phaseFeedback = analysis?.gestureProgress?.phaseFeedback == true
        let ovalColor = phaseFeedback
            ? UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1) : color
        let messageColor = analysis?.state == .holdStill || phaseFeedback
            ? UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1) : color
        let oval = UIBezierPath(ovalIn: guide)
        oval.lineWidth = 4
        ovalColor.setStroke()
        oval.stroke()

        drawText(message, centerY: layout.messageArea.midY,
                 area: layout.messageArea,
                 font: .systemFont(ofSize: 18, weight: .semibold), color: messageColor,
                 maxLines: 3)
        drawProgress(centerY: layout.progressCenterY)

        if debugMode, let analysis = analysis {
            if hasGestures, let progress = analysis.gestureProgress,
               let debug = progress.debug {
                drawGestureDebug(analysis, progress: progress, debug: debug,
                                 area: layout.debugArea)
            } else {
                drawDebugValues(analysis, area: layout.debugArea)
            }
        }
    }

    private func overlayLayout() -> OverlayLayout {
        let contentBottom = bounds.height - safeAreaInsets.bottom
        let detailedGestureDebug = debugMode && hasGestures
        let debugHeight = !debugMode ? 0 : detailedGestureDebug
            ? min(82, bounds.height * 0.15)
            : min(52, bounds.height * 0.09)
        let debugBottom = contentBottom - 8
        let debugTop = debugBottom - debugHeight
        let progressBottom = debugMode ? debugTop - 8 : contentBottom - 20
        let guideTopLimit = safeAreaInsets.top
            + min(160, (contentBottom - safeAreaInsets.top) * 0.28)
        let guideBottomLimit = progressBottom - 30
        let availableGuideHeight = max(1, guideBottomLimit - guideTopLimit)
        let guideWidth = min(bounds.width * 0.78, availableGuideHeight / 1.28)
        let guideHeight = guideWidth * 1.28
        let guideTop = guideTopLimit + max(0, availableGuideHeight - guideHeight) * 0.65
        let guide = CGRect(x: bounds.midX - guideWidth / 2, y: guideTop,
                           width: guideWidth, height: guideHeight)
        let messageTop = safeAreaInsets.top + 64
        let messageBottom = guide.minY - 36
        let messageArea = CGRect(x: 24, y: messageTop,
                                 width: max(100, bounds.width - 48),
                                 height: max(0, messageBottom - messageTop))
        let debugArea = CGRect(x: 10, y: debugTop,
                               width: max(100, bounds.width - 20),
                               height: max(0, debugBottom - debugTop))
        return OverlayLayout(guide: guide, messageArea: messageArea,
                             progressCenterY: guide.maxY + 18,
                             debugArea: debugArea)
    }

    private func drawText(_ value: String, centerY: CGFloat, area: CGRect,
                          font: UIFont, color: UIColor, maxLines: Int) {
        guard area.width > 0, area.height > 0 else { return }
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        paragraph.lineBreakMode = .byWordWrapping
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font, .foregroundColor: color, .paragraphStyle: paragraph
        ]
        let maximumHeight = min(area.height, font.lineHeight * CGFloat(maxLines))
        let measured = (value as NSString).boundingRect(
            with: CGSize(width: area.width, height: maximumHeight),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: attributes, context: nil
        )
        let height = min(maximumHeight, ceil(measured.height))
        let y = max(area.minY, min(centerY - height / 2, area.maxY - height))
        let rect = CGRect(x: area.minX, y: y, width: area.width, height: height)
        (value as NSString).draw(
            with: rect,
            options: [.usesLineFragmentOrigin, .usesFontLeading, .truncatesLastVisibleLine],
            attributes: attributes, context: nil
        )
    }

    private func drawProgress(centerY: CGFloat) {
        guard progressSegments > 0 else { return }
        let totalWidth = min(bounds.width - 48, 280)
        let gap: CGFloat = 6
        let segmentWidth = (totalWidth - gap * CGFloat(progressSegments - 1))
            / CGFloat(progressSegments)
        let height: CGFloat = 8
        var x = (bounds.width - totalWidth) / 2
        let allProgressComplete = progressCompleted == progressSegments
        let currentProgress = analysis?.gestureProgress
        let green = UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1)
        let intermediate = UIColor(red: 0.64, green: 0.84, blue: 0.29, alpha: 1)
        let yellow = UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 1)
        let future = UIColor.white.withAlphaComponent(0.40)
        var targets = (0..<progressSegments).map { index in
            index < progressCompleted || allProgressComplete
                ? green : index == progressCompleted ? yellow : future
        }
        if let progress = currentProgress, progress.stage == .perform,
           let debug = progress.debug {
            let segment = gestureSegmentIndex(progress.index)
            if targets.indices.contains(segment) {
                let acceptedPhases = debug.phase + (progress.phaseFeedback ? 1 : 0)
                targets[segment] = acceptedPhases <= 0 ? yellow
                    : acceptedPhases < debug.phaseCount ? intermediate : green
            }
        }
        let now = CACurrentMediaTime()
        ensureProgressAnimation(targets, now: now)
        var animating = false
        for index in 0..<progressSegments {
            let color = animatedProgressColor(index, now: now)
            color.setFill()
            let segment = CGRect(x: x, y: centerY - height / 2,
                                 width: segmentWidth, height: height)
            UIBezierPath(roundedRect: segment, cornerRadius: height / 2).fill()
            animating = animating || !color.isEqual(progressTargetColors[index])
            x += segmentWidth + gap
        }
        if animating { setNeedsDisplay() }
    }

    private func gestureSegmentIndex(_ gestureIndex: Int) -> Int {
        return 1 + gestureIndex + (captureAfterGesture <= gestureIndex ? 1 : 0)
    }

    private func ensureProgressAnimation(_ targets: [UIColor], now: CFTimeInterval) {
        guard progressTargetColors.count == targets.count else {
            progressStartColors = targets
            progressTargetColors = targets
            progressTransitionStartedAt = Array(repeating: now, count: targets.count)
            return
        }
        for index in targets.indices where !progressTargetColors[index].isEqual(targets[index]) {
            progressStartColors[index] = animatedProgressColor(index, now: now)
            progressTargetColors[index] = targets[index]
            progressTransitionStartedAt[index] = now
        }
    }

    private func animatedProgressColor(_ index: Int, now: CFTimeInterval) -> UIColor {
        var amount = CGFloat(min(1, max(0,
            (now - progressTransitionStartedAt[index]) / Self.progressTransitionDuration)))
        amount = amount * amount * (3 - 2 * amount)
        return blendColor(progressStartColors[index], progressTargetColors[index], amount: amount)
    }

    private func blendColor(_ from: UIColor, _ to: UIColor,
                            amount: CGFloat) -> UIColor {
        var fromR: CGFloat = 0, fromG: CGFloat = 0, fromB: CGFloat = 0, fromA: CGFloat = 0
        var toR: CGFloat = 0, toG: CGFloat = 0, toB: CGFloat = 0, toA: CGFloat = 0
        from.getRed(&fromR, green: &fromG, blue: &fromB, alpha: &fromA)
        to.getRed(&toR, green: &toG, blue: &toB, alpha: &toA)
        return UIColor(
            red: fromR + (toR - fromR) * amount,
            green: fromG + (toG - fromG) * amount,
            blue: fromB + (toB - fromB) * amount,
            alpha: fromA + (toA - fromA) * amount
        )
    }

    private func drawGestureDebug(_ analysis: FaceCaptureAnalysis,
                                  progress: FaceGestureProgress,
                                  debug: FaceGestureDebug, area: CGRect) {
        UIColor(red: 0.03, green: 0.04, blue: 0.055, alpha: 0.87).setFill()
        let panel = UIBezierPath(roundedRect: area, cornerRadius: 12)
        panel.fill()
        UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 0.54).setStroke()
        panel.lineWidth = 1
        panel.stroke()

        let scale = max(0.82, min(1, area.height / 92))
        let pad = 7 * scale
        let small = 9 * scale
        let body = 10 * scale
        let left = area.minX + pad
        let right = area.maxX - pad
        let usable = right - left
        var y = area.minY + pad
        let totalStages = debug.phaseCount + 1
        let currentStage: Int
        switch progress.stage {
        case .waitingNeutral: currentStage = 1
        case .returnNeutral: currentStage = totalStages
        default: currentStage = min(totalStages, debug.phase + 2)
        }
        drawFittedLine(localization.text("face_debug_title") + " · "
            + gestureDebugTitle(progress.gesture),
            rect: CGRect(x: left, y: y, width: max(1, usable - 42), height: small + 3),
            fontSize: small, color: UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 1),
            alignment: .left)
        drawFittedLine("\(currentStage)/\(totalStages)",
            rect: CGRect(x: right - 38, y: y, width: 38, height: small + 3),
            fontSize: small, color: .white, alignment: .right)

        y += 17 * scale
        drawFittedLine(gestureSteps(progress, debug: debug),
            rect: CGRect(x: left, y: y, width: usable, height: small + 4),
            fontSize: small,
            color: UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 1),
            alignment: .center)

        if debug.metric == .neutral {
            y += 18 * scale
            let angles = String(format: "Yaw %+.1f° · Pitch %+.1f° · Roll %+.1f°",
                                debug.relativeYaw, debug.relativePitch, debug.relativeRoll)
            drawFittedLine(angles,
                rect: CGRect(x: left, y: y, width: usable, height: body + 4),
                fontSize: body,
                color: debug.satisfied
                    ? UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1) : .white,
                alignment: .center)
            y += 15 * scale
            drawFittedLine(localization.text("face_debug_neutral_target"),
                rect: CGRect(x: left, y: y, width: usable, height: small + 3),
                fontSize: small,
                color: UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 1),
                alignment: .center)
            y += 14 * scale
            drawHoldMeter(left: left, right: right, centerY: y,
                          heldMs: debug.heldMs, requiredMs: debug.requiredHoldMs,
                          accepted: debug.satisfied)
            return
        }

        y += 18 * scale
        drawFittedLine(metricLine(debug),
            rect: CGRect(x: left, y: y, width: usable, height: body + 4),
            fontSize: body,
            color: debug.satisfied
                ? UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1) : .white,
            alignment: .center)
        y += 15 * scale
        drawMetricMeter(debug, left: left, right: right, centerY: y)
    }

    private func gestureDebugTitle(_ gesture: FaceGesture?) -> String {
        guard let gesture = gesture else { return "GESTURE" }
        if gesture == .nod { return "NOD UP + DOWN" }
        if gesture == .shake { return "NOD RIGHT + LEFT" }
        return gesture.rawValue.replacingOccurrences(of: "_", with: " ")
    }

    private func gestureSteps(_ progress: FaceGestureProgress,
                              debug: FaceGestureDebug) -> String {
        let front = localization.text("face_debug_front")
        let actions: [String]
        switch progress.gesture {
        case .some(.nod):
            actions = [localization.text("face_debug_up"),
                       localization.text("face_debug_down")]
        case .some(.shake):
            actions = [localization.text("face_debug_right"),
                       localization.text("face_debug_left")]
        default:
            actions = [gestureActionLabel(progress.gesture)]
        }
        if progress.stage == .returnNeutral {
            return (actions.map { $0 + " ✓" } + [front + " ›"]).joined(separator: "   ·   ")
        }
        var values = [front + (progress.stage == .waitingNeutral ? " ›" : " ✓")]
        for (index, action) in actions.enumerated() {
            let done = progress.stage == .perform
                && (index < debug.phase || (progress.phaseFeedback && index == debug.phase))
            let active = progress.stage == .perform
                && !progress.phaseFeedback && index == debug.phase
            let suffix = done ? " ✓" : active ? " ›" : ""
            values.append(action + suffix)
        }
        return values.joined(separator: "   ·   ")
    }

    private func gestureActionLabel(_ gesture: FaceGesture?) -> String {
        guard let gesture = gesture else { return localization.text("face_debug_action") }
        switch gesture {
        case .lookUp: return localization.text("face_debug_up")
        case .lookDown: return localization.text("face_debug_down")
        case .openMouth: return localization.text("face_debug_metric_mouth")
        case .smile: return localization.text("face_debug_metric_smile")
        case .blink: return localization.text("face_debug_metric_eyes")
        default: return localization.text("face_debug_action")
        }
    }

    private func metricLine(_ debug: FaceGestureDebug) -> String {
        let comparator = debug.lessThan ? "<" : ">"
        if debug.metric == .eyes {
            return metricLabel(debug.metric) + " L "
                + formatMetricValue(debug.rawPrimary, metric: debug.metric) + " · R "
                + formatMetricValue(debug.rawSecondary ?? -1, metric: debug.metric) + " · "
                + localization.text("face_debug_target") + " " + comparator + " "
                + formatMetricValue(debug.target, metric: debug.metric)
        }
        return metricLabel(debug.metric) + " " + localization.text("face_debug_relative")
            + " " + formatMetricValue(debug.relative, metric: debug.metric) + " · "
            + localization.text("face_debug_target") + " " + comparator + " "
            + formatMetricValue(debug.target, metric: debug.metric)
    }

    private func metricLabel(_ metric: FaceGestureMetric) -> String {
        switch metric {
        case .pitch: return localization.text("face_debug_metric_pitch")
        case .yaw: return localization.text("face_debug_metric_yaw")
        case .mouth: return localization.text("face_debug_metric_mouth")
        case .smile: return localization.text("face_debug_metric_smile")
        case .eyes: return localization.text("face_debug_metric_eyes")
        default: return localization.text("face_debug_neutral")
        }
    }

    private func formatMetricValue(_ value: CGFloat, metric: FaceGestureMetric) -> String {
        if value.isNaN || (value < 0 && metric == .eyes) { return "—" }
        if metric == .pitch || metric == .yaw { return String(format: "%+.1f°", value) }
        return String(format: metric == .mouth ? "%.3f" : "%.2f", value)
    }

    private func drawMetricMeter(_ debug: FaceGestureDebug, left: CGFloat,
                                 right: CGFloat, centerY: CGFloat) {
        var minimum: CGFloat = 0
        var maximum: CGFloat = 1
        if debug.metric == .pitch || debug.metric == .yaw {
            minimum = -20
            maximum = 20
        } else if debug.metric == .mouth {
            maximum = 0.10
        }
        let trackHeight: CGFloat = 7
        let track = CGRect(x: left, y: centerY - trackHeight / 2,
                           width: right - left, height: trackHeight)
        UIColor.white.withAlphaComponent(0.27).setFill()
        UIBezierPath(roundedRect: track, cornerRadius: trackHeight / 2).fill()
        let thresholdX = meterX(debug.target, minimum: minimum, maximum: maximum,
                                left: left, right: right)
        let zone = debug.lessThan
            ? CGRect(x: left, y: track.minY, width: max(0, thresholdX - left), height: trackHeight)
            : CGRect(x: thresholdX, y: track.minY, width: max(0, right - thresholdX), height: trackHeight)
        UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 0.33).setFill()
        UIBezierPath(roundedRect: zone, cornerRadius: trackHeight / 2).fill()
        UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 1).setFill()
        UIRectFill(CGRect(x: thresholdX - 1, y: track.minY - 2,
                          width: 2, height: trackHeight + 4))
        let markerX = meterX(debug.relative, minimum: minimum, maximum: maximum,
                             left: left, right: right)
        let marker = UIBezierPath(ovalIn: CGRect(x: markerX - 5, y: centerY - 5,
                                                 width: 10, height: 10))
        UIColor.white.setFill()
        marker.fill()
        (debug.satisfied
            ? UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1)
            : UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 1)).setStroke()
        marker.lineWidth = 2
        marker.stroke()
    }

    private func meterX(_ value: CGFloat, minimum: CGFloat, maximum: CGFloat,
                        left: CGFloat, right: CGFloat) -> CGFloat {
        let normalized = max(0, min(1, (value - minimum) / (maximum - minimum)))
        return left + normalized * (right - left)
    }

    private func drawHoldMeter(left: CGFloat, right: CGFloat, centerY: CGFloat,
                               heldMs: Int, requiredMs: Int, accepted: Bool) {
        let height: CGFloat = 6
        let track = CGRect(x: left, y: centerY - height / 2,
                           width: right - left, height: height)
        UIColor.white.withAlphaComponent(0.27).setFill()
        UIBezierPath(roundedRect: track, cornerRadius: height / 2).fill()
        let ratio = requiredMs <= 0 ? 0
            : max(0, min(1, CGFloat(heldMs) / CGFloat(requiredMs)))
        let fill = CGRect(x: left, y: track.minY, width: track.width * ratio, height: height)
        (accepted
            ? UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1)
            : UIColor(red: 0.98, green: 0.80, blue: 0.08, alpha: 1)).setFill()
        UIBezierPath(roundedRect: fill, cornerRadius: height / 2).fill()
    }

    private func drawFittedLine(_ text: String, rect: CGRect, fontSize: CGFloat,
                                color: UIColor, alignment: NSTextAlignment) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = alignment
        var font = UIFont.systemFont(ofSize: fontSize, weight: .semibold)
        let measured = (text as NSString).size(withAttributes: [.font: font]).width
        if measured > rect.width, measured > 0 {
            font = UIFont.systemFont(ofSize: max(7.5, fontSize * rect.width / measured),
                                     weight: .semibold)
        }
        (text as NSString).draw(in: rect, withAttributes: [
            .font: font, .foregroundColor: color, .paragraphStyle: paragraph
        ])
    }

    private func drawDebugValues(_ analysis: FaceCaptureAnalysis, area: CGRect) {
        let values = NSMutableAttributedString()
        let font = UIFont.monospacedSystemFont(ofSize: 10, weight: .semibold)
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        let valid = UIColor(red: 0.29, green: 0.87, blue: 0.50, alpha: 1)
        let pending = UIColor.white
        let verticalAccepted = !deviceVerticalCheckEnabled || deviceVerticalReady

        func append(_ text: String, accepted: Bool?, enabled: Bool, suffix: String = "  ") {
            guard enabled else { return }
            let color = accepted == true ? valid : pending
            values.append(NSAttributedString(
                string: text + suffix,
                attributes: [.font: font, .foregroundColor: color, .paragraphStyle: paragraph]
            ))
        }

        if deviceVerticalCheckEnabled {
            let tilt = deviceTiltDegrees.map { String(format: "tilt %.1f°", $0) } ?? "tilt —"
            append(tilt, accepted: deviceVerticalReady, enabled: true)
        }

        if let box = analysis.normalizedBounds {
            append(String(format: "size %.2f", box.width),
                   accepted: verticalAccepted && box.width >= 0.28 && box.width <= 0.62,
                   enabled: checks.faceSize)
            let center = analysis.centeringAvailable
                ? String(format: "center x %+.2f y %+.2f",
                         analysis.centeringHorizontalOffset,
                         analysis.centeringVerticalOffset)
                : "center —"
            append(center,
                   accepted: verticalAccepted && analysis.centeringAvailable
                    && analysis.centeringAccepted,
                   enabled: checks.centering, suffix: "\n")
            append(String(format: "yaw %.1f", analysis.yaw),
                   accepted: verticalAccepted && abs(analysis.yaw) <= 11,
                   enabled: checks.pose)
            append(String(format: "pitch %.1f", analysis.pitch),
                   accepted: verticalAccepted && abs(analysis.pitch) <= 11,
                   enabled: checks.pose)
            append(String(format: "roll %.1f", analysis.roll),
                   accepted: verticalAccepted && abs(analysis.roll) <= 9,
                   enabled: checks.pose, suffix: "\n")
        } else {
            append("size —", accepted: nil, enabled: checks.faceSize)
            append("center —", accepted: nil, enabled: checks.centering, suffix: "\n")
            append("yaw —", accepted: nil, enabled: checks.pose)
            append("pitch —", accepted: nil, enabled: checks.pose)
            append("roll —", accepted: nil, enabled: checks.pose, suffix: "\n")
        }

        append("eye L " + probability(analysis.leftEye),
               accepted: analysis.leftEye.map { verticalAccepted && $0 >= 0.62 },
               enabled: checks.eyesOpen)
        append("R " + probability(analysis.rightEye),
               accepted: analysis.rightEye.map { verticalAccepted && $0 >= 0.62 },
               enabled: checks.eyesOpen)
        append("smile " + probability(analysis.smile),
               accepted: analysis.smile.map { verticalAccepted && $0 <= 0.38 },
               enabled: checks.neutralExpression, suffix: "\n")
        var mouthAccepted = analysis.mouthOpenRatio < 0.035
        if analysis.gestureProgress?.stage == .perform,
           analysis.gestureProgress?.gesture == .openMouth {
            mouthAccepted = analysis.mouthOpenRatio > 0.055
        }
        append(analysis.normalizedBounds == nil ? "mouth —"
            : String(format: "mouth %.3f", analysis.mouthOpenRatio),
               accepted: verticalAccepted && analysis.normalizedBounds != nil && mouthAccepted,
               enabled: hasGestures)
        append(String(format: "light %.2f", analysis.brightness),
               accepted: verticalAccepted && analysis.brightness >= 0.20
                && analysis.brightness <= 0.88,
               enabled: checks.brightness)
        append(String(format: "sharp %.2f", analysis.sharpness),
               accepted: verticalAccepted && analysis.sharpness >= 0.10,
               enabled: checks.sharpness)
        append("stable \(analysis.stableDurationMs)ms",
               accepted: verticalAccepted && analysis.stableDurationMs >= 650,
               enabled: checks.stability && !hasGestures, suffix: "")

        values.draw(in: area)
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
