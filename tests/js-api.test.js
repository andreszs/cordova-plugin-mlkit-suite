'use strict';

var assert = require('assert');
var Module = require('module');
var originalRequire = Module.prototype.require;
var calls = [];
var nextFailure = null;

Module.prototype.require = function (name) {
    if (name === 'cordova/exec') {
        return function (success, error, service, action, args) {
            calls.push({ service: service, action: action, args: args });
            if (nextFailure !== null) {
                var failure = nextFailure;
                nextFailure = null;
                error(failure);
                return;
            }
            success({ status: 'success' });
        };
    }
    return originalRequire.apply(this, arguments);
};

var MlKit = require('../www/MlKit');

Promise.resolve()
    .then(function () { return MlKit.getCapabilities(); })
    .then(function () { return MlKit.prepareModels(); })
    .then(function () {
        return MlKit.prepareModels(['barcode', 'image-labeling', 'ocr'], {
            moduleLoadingMessage: 'Cargando módulos…', locale: 'es-AR'
        });
    })
    .then(function () {
        return MlKit.face.capture({ timeoutMs: 1000, moduleLoadingMessage: '', locale: 'es' });
    })
    .then(function () {
        return MlKit.barcode.scan({ formats: ['PDF_417', 'QR_CODE'], locale: 'auto' });
    })
    .then(function () {
        return MlKit.prepareDocumentScanner({ moduleLoadingMessage: 'Preparing scanner…' });
    })
    .then(function () {
        return MlKit.scanDocument({
            pageLimit: 2,
            resultFormats: ['JPEG', 'PDF'],
            moduleLoadingMessage: 'Preparing scanner…'
        });
    })
    .then(function () {
        return MlKit.recognizeText('/cache/page-1.jpg', {
            moduleLoadingMessage: 'Cargando OCR…'
        });
    })
    .then(function () {
        return MlKit.scanBarcodes('/cache/page-2.jpg', { formats: ['PDF_417'] });
    })
    .then(function () {
        return MlKit.labelImage('/cache/photo.jpg', { confidenceThreshold: 0.6 });
    })
    .then(function () {
        return MlKit.segmentSelfie('/cache/selfie.jpg', { maxDimension: 1600 });
    })
    .then(function () {
        return MlKit.segmentSelfie('/cache/selfie.jpg', { maxDimension: 128 }).then(function () {
            throw new Error('Expected an invalid maximum dimension to reject.');
        }, function (error) {
            assert.strictEqual(error.code, 'INVALID_ARGUMENT');
        });
    })
    .then(function () {
        return MlKit.labelImage('/cache/photo.jpg', { confidenceThreshold: 2 }).then(function () {
            throw new Error('Expected an invalid confidence threshold to reject.');
        }, function (error) {
            assert.strictEqual(error.code, 'INVALID_ARGUMENT');
        });
    })
    .then(function () {
        return MlKit.prepareModels([]).then(function () {
            throw new Error('Expected an empty modules array to reject.');
        }, function (error) {
            assert.strictEqual(error.code, 'INVALID_ARGUMENT');
        });
    })
    .then(function () {
        return MlKit.prepareModels({ moduleLoadingMessage: 42 }).then(function () {
            throw new Error('Expected a non-string module loading message to reject.');
        }, function (error) {
            assert.strictEqual(error.code, 'INVALID_ARGUMENT');
        });
    })
    .then(function () {
        return MlKit.recognizeText('').then(function () {
            throw new Error('Expected an empty imagePath to reject.');
        }, function (error) {
            assert.strictEqual(error.code, 'INVALID_ARGUMENT');
        });
    })
    .then(function () {
        nextFailure = JSON.stringify({
            code: 'USER_CANCELLED',
            message: 'Cancelled.',
            recoverable: true
        });
        return MlKit.face.capture();
    })
    .then(function (cancelled) {
        assert.deepStrictEqual(cancelled, { status: 'cancelled', reason: 'user' });
        nextFailure = { code: 'BUSY', message: 'Busy.', recoverable: true };
        return MlKit.face.capture().then(function () {
            throw new Error('Expected the BUSY error to reject.');
        }, function (error) {
            assert.deepStrictEqual(error, nextFailure || {
                code: 'BUSY', message: 'Busy.', recoverable: true
            });
        });
    })
    .then(function () {
        assert.deepStrictEqual(calls, [
            { service: 'MlKitSuite', action: 'getCapabilities', args: [] },
            { service: 'MlKitSuite', action: 'prepareModels', args: [] },
            { service: 'MlKitSuite', action: 'prepareModels', args: [
                ['barcode', 'image-labeling', 'ocr'], {
                    moduleLoadingMessage: 'Cargando módulos…', locale: 'es-AR'
                }
            ] },
            { service: 'MlKitSuite', action: 'faceCapture', args: [{
                timeoutMs: 1000, moduleLoadingMessage: '', locale: 'es'
            }] },
            { service: 'MlKitSuite', action: 'barcodeScan', args: [{
                formats: ['PDF_417', 'QR_CODE'], locale: 'auto'
            }] },
            { service: 'MlKitSuite', action: 'prepareDocumentScanner', args: [{
                moduleLoadingMessage: 'Preparing scanner…'
            }] },
            { service: 'MlKitSuite', action: 'documentScan', args: [{
                pageLimit: 2,
                resultFormats: ['JPEG', 'PDF'],
                moduleLoadingMessage: 'Preparing scanner…'
            }] },
            { service: 'MlKitSuite', action: 'recognizeText', args: [
                '/cache/page-1.jpg', { moduleLoadingMessage: 'Cargando OCR…' }
            ] },
            { service: 'MlKitSuite', action: 'scanBarcodes', args: ['/cache/page-2.jpg', { formats: ['PDF_417'] }] },
            { service: 'MlKitSuite', action: 'labelImage', args: [
                '/cache/photo.jpg', { confidenceThreshold: 0.6 }
            ] },
            { service: 'MlKitSuite', action: 'segmentSelfie', args: [
                '/cache/selfie.jpg', { maxDimension: 1600 }
            ] },
            { service: 'MlKitSuite', action: 'faceCapture', args: [{}] },
            { service: 'MlKitSuite', action: 'faceCapture', args: [{}] }
        ]);
        process.stdout.write('JavaScript API tests passed.\n');
    })
    .catch(function (error) {
        process.stderr.write(String(error.stack || error) + '\n');
        process.exitCode = 1;
    })
    .finally(function () {
        Module.prototype.require = originalRequire;
    });

