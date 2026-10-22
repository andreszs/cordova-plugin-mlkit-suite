'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');
var pluginXml = fs.readFileSync(path.join(root, 'plugin.xml'), 'utf8');
var plugin = fs.readFileSync(
    path.join(root, 'src', 'ios', 'MlKitSuitePlugin.swift'), 'utf8'
);
var processor = fs.readFileSync(
    path.join(root, 'src', 'ios', 'SelfieSegmentationProcessor.swift'), 'utf8'
);

assert.match(pluginXml, /GoogleMLKit\/SegmentationSelfie/);
assert.match(pluginXml, /SelfieSegmentationProcessor\.swift/);
assert.match(plugin, /@objc\(segmentSelfie:\)/);
assert.match(plugin, /"selfieSegmentation"/);
assert.match(processor, /SelfieSegmenterOptions/);
assert.match(processor, /CIBlendWithMask/);
assert.match(processor, /pngData\(\)/);

process.stdout.write('iOS selfie segmentation contract tests passed.\n');
