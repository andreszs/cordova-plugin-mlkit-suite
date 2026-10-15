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
    .then(function () { return MlKit.face.capture({ timeoutMs: 1000 }); })
    .then(function () {
        return MlKit.barcode.scan({ formats: ['PDF_417', 'QR_CODE'] });
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
            { service: 'MlKitSuite', action: 'faceCapture', args: [{ timeoutMs: 1000 }] },
            { service: 'MlKitSuite', action: 'barcodeScan', args: [{ formats: ['PDF_417', 'QR_CODE'] }] },
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

