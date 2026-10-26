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

function moduleLoadingMessageError(options) {
    if (!options || !Object.prototype.hasOwnProperty.call(options, 'moduleLoadingMessage')) {
        return null;
    }
    var value = options.moduleLoadingMessage;
    if (value === null || typeof value === 'string') return null;
    return {
        code: 'INVALID_ARGUMENT',
        message: 'moduleLoadingMessage must be a string or null.',
        recoverable: false
    };
}

function rejectInvalidModuleLoadingMessage(options) {
    var error = moduleLoadingMessageError(options);
    return error ? Promise.reject(error) : null;
}

exports.getCapabilities = function getCapabilities() {
    return call('getCapabilities');
};

/**
 * Ensures that selected Play services ML Kit modules are ready. Omit modules
 * to prepare Face Detection, Barcode Scanning, Image Labeling, Latin OCR, and
 * Subject Segmentation. Bundled modules are ignored because they are already available.
 *
 * @param {string|string[]|object} [modules] face, barcode, image-labeling, ocr,
 *        subject-segmentation, an array, or the options object when preparing every module.
 * @param {{moduleLoadingMessage?: string|null, locale?: string}} [options]
 *        Native loading text and UI locale (`auto`, `en`, `es`, or BCP-47).
 * @returns {Promise<{status: string, alreadyAvailable: boolean}>}
 */
exports.prepareModels = function prepareModels(modules, options) {
    if (modules && typeof modules === 'object' && !Array.isArray(modules)
        && options === undefined) {
        options = modules;
        modules = undefined;
    }
    var loadingError = rejectInvalidModuleLoadingMessage(options);
    if (loadingError) return loadingError;
    if (modules === undefined || modules === null) {
        return options ? call('prepareModels', [null, options]) : call('prepareModels');
    }
    var requested = typeof modules === 'string' ? [modules] : modules;
    var valid = Array.isArray(requested) && requested.length > 0 && requested.every(function (module) {
        return typeof module === 'string'
            && ['face', 'barcode', 'image-labeling', 'ocr', 'subject-segmentation']
                .indexOf(module.trim().toLowerCase()) !== -1;
    });
    if (!valid) {
        return Promise.reject({
            code: 'INVALID_ARGUMENT',
            message: 'modules must be face, barcode, image-labeling, ocr, subject-segmentation, or an array containing those names.',
            recoverable: false
        });
    }
    return options ? call('prepareModels', [modules, options]) : call('prepareModels', [modules]);
};

/**
 * Opens the native document-scanner flow.
 *
 * @param {{pageLimit?: number, galleryImportAllowed?: boolean,
 *          resultFormats?: string[]|string|null, scannerMode?: string|null,
 *          moduleLoadingMessage?: string|null, locale?: string}} [options]
 * @returns {Promise<object>} Scanned JPEG pages/PDF or a cancellation result.
 */
exports.scanDocument = function scanDocument(options) {
    var loadingError = rejectInvalidModuleLoadingMessage(options);
    if (loadingError) return loadingError;
    return call('documentScan', [options || {}]);
};

/**
 * Ensures that the Google Play services Document Scanner component is ready.
 * Shows the native loading indicator only when the component must download.
 *
 * @param {{moduleLoadingMessage?: string|null, locale?: string}} [options]
 *        Native loading text and UI locale.
 * @returns {Promise<{status: string, alreadyAvailable: boolean}>}
 */
exports.prepareDocumentScanner = function prepareDocumentScanner(options) {
    var loadingError = rejectInvalidModuleLoadingMessage(options);
    if (loadingError) return loadingError;
    return options ? call('prepareDocumentScanner', [options]) : call('prepareDocumentScanner');
};

/**
 * Recognizes Latin-script text in an existing local image.
 *
 * @param {string} imagePath Native path, file/content URI, or plugin image URI.
 * @param {{moduleLoadingMessage?: string|null, locale?: string}} [options]
 *        Native loading text and UI locale.
 * @returns {Promise<string>} Plain recognized text; empty when none is found.
 */
exports.recognizeText = function recognizeText(imagePath, options) {
    if (typeof imagePath !== 'string' || !imagePath.trim()) {
        return Promise.reject({
            code: 'INVALID_ARGUMENT',
            message: 'imagePath must be a non-empty string.',
            recoverable: false
        });
    }
    var loadingError = rejectInvalidModuleLoadingMessage(options);
    if (loadingError) return loadingError;
    return options ? call('recognizeText', [imagePath, options]) : call('recognizeText', [imagePath]);
};

/**
 * Classifies an existing local image with ML Kit's default image-labeling model.
 *
 * @param {string} imagePath Native path, file/content URI, or plugin image URI.
 * @param {{confidenceThreshold?: number, moduleLoadingMessage?: string|null,
 *          locale?: string}} [options] Optional settings.
 * @returns {Promise<Array<{text: string, confidence: number, index: number}>>}
 */
exports.labelImage = function labelImage(imagePath, options) {
    if (typeof imagePath !== 'string' || !imagePath.trim()) {
        return Promise.reject({
            code: 'INVALID_ARGUMENT',
            message: 'imagePath must be a non-empty string.',
            recoverable: false
        });
    }
    options = options || {};
    if (Object.prototype.hasOwnProperty.call(options, 'confidenceThreshold')) {
        var threshold = options.confidenceThreshold;
        if (typeof threshold !== 'number' || !isFinite(threshold)
            || threshold < 0 || threshold > 1) {
            return Promise.reject({
                code: 'INVALID_ARGUMENT',
                message: 'confidenceThreshold must be a number from 0 to 1.',
                recoverable: false
            });
        }
    }
    var loadingError = rejectInvalidModuleLoadingMessage(options);
    if (loadingError) return loadingError;
    return call('labelImage', [imagePath, options]);
};

/**
 * Separates people from the background of an existing local image.
 * The returned image is a temporary PNG with transparency.
 *
 * @param {string} imagePath Native path, file/content URI, or plugin image URI.
 * @param {{maxDimension?: number, locale?: string}} [options] Optional settings.
 * @returns {Promise<{status: string, imageUri: string, imagePath: string,
 *          width: number, height: number, mimeType: string, bytes: number}>}
 */
exports.segmentSelfie = function segmentSelfie(imagePath, options) {
    if (typeof imagePath !== 'string' || !imagePath.trim()) {
        return Promise.reject({
            code: 'INVALID_ARGUMENT',
            message: 'imagePath must be a non-empty string.',
            recoverable: false
        });
    }
    options = options || {};
    if (Object.prototype.hasOwnProperty.call(options, 'maxDimension')) {
        var maxDimension = options.maxDimension;
        if (typeof maxDimension !== 'number' || !isFinite(maxDimension)
            || Math.floor(maxDimension) !== maxDimension
            || maxDimension < 256 || maxDimension > 4096) {
            return Promise.reject({
                code: 'INVALID_ARGUMENT',
                message: 'maxDimension must be an integer from 256 to 4096.',
                recoverable: false
            });
        }
    }
    return call('segmentSelfie', [imagePath, options]);
};

/**
 * Separates foreground subjects from the background of an existing local image.
 * Android only. The returned image is a temporary PNG with transparency.
 *
 * @param {string} imagePath Native path, file/content URI, or plugin image URI.
 * @param {{maxDimension?: number, moduleLoadingMessage?: string|null,
 *          locale?: string}} [options] Optional settings.
 * @returns {Promise<{status: string, imageUri: string, imagePath: string,
 *          width: number, height: number, mimeType: string, bytes: number}>}
 */
exports.segmentSubjects = function segmentSubjects(imagePath, options) {
    if (typeof imagePath !== 'string' || !imagePath.trim()) {
        return Promise.reject({
            code: 'INVALID_ARGUMENT',
            message: 'imagePath must be a non-empty string.',
            recoverable: false
        });
    }
    options = options || {};
    if (Object.prototype.hasOwnProperty.call(options, 'maxDimension')) {
        var maxDimension = options.maxDimension;
        if (typeof maxDimension !== 'number' || !isFinite(maxDimension)
            || Math.floor(maxDimension) !== maxDimension
            || maxDimension < 256 || maxDimension > 4096) {
            return Promise.reject({
                code: 'INVALID_ARGUMENT',
                message: 'maxDimension must be an integer from 256 to 4096.',
                recoverable: false
            });
        }
    }
    var loadingError = rejectInvalidModuleLoadingMessage(options);
    if (loadingError) return loadingError;
    return call('segmentSubjects', [imagePath, options]);
};

/**
 * Detects every supported barcode in an existing local image.
 *
 * @param {string} imagePath Native path, file/content URI, or plugin image URI.
 * @param {{formats?: string[]|string|null,
 *          moduleLoadingMessage?: string|null, locale?: string}} [options] Optional settings.
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
    var loadingError = rejectInvalidModuleLoadingMessage(options);
    if (loadingError) return loadingError;
    return call('scanBarcodes', [imagePath, options || {}]);
};

exports.face = {
    /**
     * Opens the native front-camera guide and automatically captures a JPEG.
     *
     * @param {{outputWidth?: number, outputHeight?: number, jpegQuality?: number,
     *          showCloseButton?: boolean, debugMode?: boolean,
     *          instructionText?: string|null, timeoutMs?: number,
     *          moduleLoadingMessage?: string|null, locale?: string,
     *          checks?: {singleFace?: boolean, faceSize?: boolean,
     *          centering?: boolean, pose?: boolean, eyesOpen?: boolean,
     *          neutralExpression?: boolean, stability?: boolean,
     *          sharpness?: boolean, brightness?: boolean}}} [options]
     * @returns {Promise<object>} A success result or {status: 'cancelled', reason: 'user'}.
     */
    capture: function capture(options) {
        var loadingError = rejectInvalidModuleLoadingMessage(options);
        if (loadingError) return loadingError;
        return call('faceCapture', [options || {}]);
    }
};

exports.barcode = {
    /**
     * Opens the native Android or iOS barcode scanner. All ML Kit formats are enabled
     * by default; formats can be restricted to improve detection speed.
     *
     * @param {{formats?: string[]|string|null, showCloseButton?: boolean,
     *          showTorchButton?: boolean, beepEnabled?: boolean,
     *          instructionText?: string|null, guideColor?: string|null, timeoutMs?: number,
     *          moduleLoadingMessage?: string|null, locale?: string}} [options]
     * @returns {Promise<object>} A success result or {status: 'cancelled', reason: 'user'}.
     */
    scan: function scan(options) {
        var loadingError = rejectInvalidModuleLoadingMessage(options);
        if (loadingError) return loadingError;
        return call('barcodeScan', [options || {}]);
    }
};

