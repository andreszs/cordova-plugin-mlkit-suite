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

