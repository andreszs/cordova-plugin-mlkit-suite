'use strict';

var exec = require('cordova/exec');

function normalizeError(error) {
    if (error && typeof error === 'object' && error.code) {
        return error;
    }
    if (typeof error === 'string') {
        try {
            var parsed = JSON.parse(error);
            if (parsed && parsed.code) {
                return parsed;
            }
        } catch (ignored) {
            // Keep the native message below.
        }
        return { code: 'UNKNOWN', message: error, recoverable: false };
    }
    return { code: 'UNKNOWN', message: 'Unknown ML Kit Suite error.', recoverable: false };
}

function call(action, args) {
    return new Promise(function (resolve, reject) {
        exec(resolve, function (rawError) {
            var error = normalizeError(rawError);
            if (error.code === 'USER_CANCELLED') {
                resolve({ status: 'cancelled', reason: 'user' });
                return;
            }
            reject(error);
        }, 'MlKitSuite', action, args || []);
    });
}

exports.getCapabilities = function getCapabilities() {
    return call('getCapabilities');
};

/**
 * Ensures that selected Play services ML Kit modules are ready. Omit modules
 * to prepare Face Detection, Barcode Scanning, and Latin OCR. Bundled modules
 * are ignored because they are already available.
 *
 * @param {string|string[]} [modules] face, barcode, ocr, or an array of them.
 * @returns {Promise<{status: string, alreadyAvailable: boolean}>}
 */
exports.prepareModels = function prepareModels(modules) {
    if (modules === undefined || modules === null) {
        return call('prepareModels');
    }
    var requested = typeof modules === 'string' ? [modules] : modules;
    var valid = Array.isArray(requested) && requested.length > 0 && requested.every(function (module) {
        return typeof module === 'string' && ['face', 'barcode', 'ocr'].indexOf(module.trim().toLowerCase()) !== -1;
    });
    if (!valid) {
        return Promise.reject({
            code: 'INVALID_ARGUMENT',
            message: 'modules must be face, barcode, ocr, or an array containing those names.',
            recoverable: false
        });
    }
    return call('prepareModels', [modules]);
};

/**
 * Opens the native document-scanner flow.
 *
 * @param {{pageLimit?: number, galleryImportAllowed?: boolean,
 *          resultFormats?: string[]|string, scannerMode?: string}} options
 * @returns {Promise<object>} Scanned JPEG pages/PDF or a cancellation result.
 */
exports.scanDocument = function scanDocument(options) {
    return call('documentScan', [options || {}]);
};

/**
 * Ensures that the Google Play services Document Scanner component is ready.
 * Applications can show progress UI while this Promise is pending.
 *
 * @returns {Promise<{status: string, alreadyAvailable: boolean}>}
 */
exports.prepareDocumentScanner = function prepareDocumentScanner() {
    return call('prepareDocumentScanner');
};

/**
 * Recognizes Latin-script text in an existing local image.
 *
 * @param {string} imagePath Native path, file/content URI, or plugin image URI.
 * @returns {Promise<string>} Plain recognized text; empty when none is found.
 */
exports.recognizeText = function recognizeText(imagePath) {
    if (typeof imagePath !== 'string' || !imagePath.trim()) {
        return Promise.reject({
            code: 'INVALID_ARGUMENT',
            message: 'imagePath must be a non-empty string.',
            recoverable: false
        });
    }
    return call('recognizeText', [imagePath]);
};

/**
 * Detects every supported barcode in an existing local image.
 *
 * @param {string} imagePath Native path, file/content URI, or plugin image URI.
 * @param {{formats?: string[]|string}} options Optional format filter.
 * @returns {Promise<object[]>} All detected barcodes; empty when none are found.
 */
exports.scanBarcodes = function scanBarcodes(imagePath, options) {
    if (typeof imagePath !== 'string' || !imagePath.trim()) {
        return Promise.reject({
            code: 'INVALID_ARGUMENT',
            message: 'imagePath must be a non-empty string.',
            recoverable: false
        });
    }
    return call('scanBarcodes', [imagePath, options || {}]);
};

exports.face = {
    /**
     * Opens the native front-camera guide and automatically captures a JPEG.
     *
     * @param {{outputWidth?: number, outputHeight?: number, jpegQuality?: number,
     *          showCloseButton?: boolean, debugMode?: boolean,
     *          instructionText?: string, timeoutMs?: number,
     *          checks?: {singleFace?: boolean, faceSize?: boolean,
     *          centering?: boolean, pose?: boolean, eyesOpen?: boolean,
     *          neutralExpression?: boolean, stability?: boolean,
     *          sharpness?: boolean, brightness?: boolean}}} options
     * @returns {Promise<object>} A success result or {status: 'cancelled', reason: 'user'}.
     */
    capture: function capture(options) {
        return call('faceCapture', [options || {}]);
    }
};

exports.barcode = {
    /**
     * Opens the native Android barcode scanner. All ML Kit formats are enabled
     * by default; formats can be restricted to improve detection speed.
     *
     * @param {{formats?: string[]|string, showCloseButton?: boolean,
     *          showTorchButton?: boolean,
     *          instructionText?: string, timeoutMs?: number}} options
     * @returns {Promise<object>} A success result or {status: 'cancelled', reason: 'user'}.
     */
    scan: function scan(options) {
        return call('barcodeScan', [options || {}]);
    }
};

