'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');
var controller = fs.readFileSync(path.join(root, 'src/ios/FaceCaptureViewController.swift'), 'utf8');
var types = fs.readFileSync(path.join(root, 'src/ios/FaceCaptureTypes.swift'), 'utf8');
var overlay = fs.readFileSync(path.join(root, 'src/ios/FaceOverlayView.swift'), 'utf8');

assert(controller.includes('checks: options.checks'));
assert(controller.includes('bufferHeight >= bufferWidth ? .up : .leftMirrored'));
assert(controller.includes('previewLayer.connection, mirrored: true'));
assert(controller.includes('beginCapture(analysis, pixelBuffer: pixelBuffer)'));
assert(controller.includes('pixelBuffer: pixelBuffer'));
assert(types.includes('beepEnabled = try boolean("beepEnabled", fallback: true)'));
assert(controller.includes('if options.beepEnabled'));
assert(controller.includes('successBeep.play()'));
assert(!controller.includes('AVCapturePhotoOutput'));
assert(!controller.includes('AVCapturePhotoCaptureDelegate'));
assert(!controller.includes('fileDataRepresentation'));
assert(!controller.includes('[MlKitSuite][Face]'));
var stillProcessor = fs.readFileSync(path.join(root, 'src/ios/FaceStillProcessor.swift'), 'utf8');
assert(stillProcessor.includes('func process(pixelBuffer: CVPixelBuffer'));
assert(stillProcessor.includes('CIImage(cvPixelBuffer: pixelBuffer)'));
assert(stillProcessor.includes('UIImage(cgImage: cgImage, scale: 1, orientation: .up)'));
assert(stillProcessor.includes('analysis.normalizedBounds'));
assert(!stillProcessor.includes('VisionImage'));
assert(!stillProcessor.includes('FaceDetector'));
assert(!stillProcessor.includes('debug-face-capture.jpg'));
assert(!stillProcessor.includes('orientations: [UIImage.Orientation]'));
assert(overlay.includes('private let checks: FaceCaptureChecks'));
assert(overlay.includes('size %.2f'));
assert(overlay.includes('center %.2f'));
assert(overlay.includes('eye L '));
assert(overlay.includes('smile '));
assert(overlay.includes('light %.2f'));
assert(overlay.includes('sharp %.2f'));
assert(overlay.includes('stable \\(analysis.stableDurationMs)ms'));
assert(overlay.includes('accepted! ? valid : invalid'));
assert(overlay.includes('box.width >= 0.28 && box.width <= 0.62'));
assert(overlay.includes('center <= 0.13'));
assert(overlay.includes('analysis.sharpness >= 0.10'));
assert(overlay.includes('analysis.stableDurationMs >= 650'));

process.stdout.write('iOS face capture overlay contract tests passed.\n');
