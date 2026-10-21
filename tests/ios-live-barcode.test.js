'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');
var pluginXml = fs.readFileSync(path.join(root, 'plugin.xml'), 'utf8');
var plugin = fs.readFileSync(path.join(root, 'src/ios/MlKitSuitePlugin.swift'), 'utf8');
var controller = fs.readFileSync(path.join(root, 'src/ios/BarcodeScanViewController.swift'), 'utf8');
var faceController = fs.readFileSync(path.join(root, 'src/ios/FaceCaptureViewController.swift'), 'utf8');
var types = fs.readFileSync(path.join(root, 'src/ios/BarcodeScanTypes.swift'), 'utf8');
var spanish = fs.readFileSync(path.join(
    root, 'src/ios/MlKitSuiteResources.bundle/es.lproj/Localizable.strings'
), 'utf8');

[
    'src/ios/BarcodeScanTypes.swift',
    'src/ios/BarcodeOverlayView.swift',
    'src/ios/BarcodeScanViewController.swift',
    'src/ios/MlKitLocalization.swift'
].forEach(function (source) {
    assert(pluginXml.includes('<source-file src="' + source + '" />'), source + ' must be packaged.');
});

assert(pluginXml.includes('<pod name="GoogleMLKit/BarcodeScanning" spec="8.0.0" />'));
assert(plugin.includes('func barcodeScan(_ command: CDVInvokedUrlCommand)'));
assert(plugin.includes('(barcode.cornerPoints ?? []).map'));
assert(plugin.includes('"scan": true'));
assert(!plugin.includes('Barcode scanning is currently available on Android only.'));
assert(controller.includes('position: .back'));
assert(controller.includes('.hd1920x1080'));
assert(controller.includes('automaticallyAdjustsVideoMirroring = false'));
assert(controller.indexOf('automaticallyAdjustsVideoMirroring = false')
    < controller.indexOf('isVideoMirrored = false'));
assert(controller.includes('width: CVPixelBufferGetWidth(imageBuffer)'));
assert(controller.includes('height: CVPixelBufferGetHeight(imageBuffer)'));
assert(faceController.includes('automaticallyAdjustsVideoMirroring = false'));
assert(faceController.indexOf('automaticallyAdjustsVideoMirroring = false')
    < faceController.indexOf('isVideoMirrored = mirrored'));
assert(controller.includes('VisionImage(buffer: sampleBuffer)'));
assert(controller.includes('requiredConfirmationFrames = 2'));
assert(controller.includes('options.localization.text("barcode_center")'));
assert(controller.includes('overlayView.contains('));
assert(types.includes('guideColor must be a color in #RRGGBB format.'));
assert(controller.includes('setTorchModeOn'));
assert(controller.includes('UIImage(systemName: systemName'));
assert(controller.includes('accessibilityLabel'));
assert(types.includes('"PDF_417"'));
assert(types.includes('"QR_CODE"'));
assert(pluginXml.includes('src/ios/MlKitSuiteResources.bundle'));
assert(spanish.includes('"barcode_center"'));

process.stdout.write('iOS live barcode source contract tests passed.\n');
