'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');

function read(relative) {
    return fs.readFileSync(path.join(root, relative), 'utf8');
}

function keys(source, pattern) {
    var result = [];
    var match;
    while ((match = pattern.exec(source)) !== null) result.push(match[1]);
    return result.sort();
}

var androidEnglish = keys(
    read('src/android/res/values/mlkit_suite_strings.xml'),
    /<string name="mlkit_suite_([^"]+)"/g
);
var androidSpanish = keys(
    read('src/android/res/values-es/mlkit_suite_strings.xml'),
    /<string name="mlkit_suite_([^"]+)"/g
);
var iosEnglish = keys(
    read('src/ios/MlKitSuiteResources.bundle/en.lproj/Localizable.strings'),
    /^"([^"]+)"\s*=/gm
);
var iosSpanish = keys(
    read('src/ios/MlKitSuiteResources.bundle/es.lproj/Localizable.strings'),
    /^"([^"]+)"\s*=/gm
);

assert.deepStrictEqual(androidSpanish, androidEnglish, 'Android locale keys must match.');
assert.deepStrictEqual(iosSpanish, iosEnglish, 'iOS locale keys must match.');
assert(androidEnglish.includes('face_instruction'));
assert(androidEnglish.includes('face_device_vertical'));
assert(androidEnglish.includes('barcode_instruction'));
assert(androidEnglish.includes('loading_module'));
assert(iosEnglish.includes('face_instruction'));
assert(iosEnglish.includes('face_device_vertical'));
assert(iosEnglish.includes('barcode_instruction'));

var pluginXml = read('plugin.xml');
assert(pluginXml.includes('src/android/res/values-es/mlkit_suite_strings.xml'));
assert(pluginXml.includes('src/ios/MlKitSuiteResources.bundle'));

process.stdout.write('Localization resource tests passed.\n');
