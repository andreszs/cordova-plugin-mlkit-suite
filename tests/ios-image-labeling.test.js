'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');
var root = path.resolve(__dirname, '..');
var pluginXml = fs.readFileSync(path.join(root, 'plugin.xml'), 'utf8');
var plugin = fs.readFileSync(path.join(root, 'src/ios/MlKitSuitePlugin.swift'), 'utf8');

assert(pluginXml.includes('<pod name="GoogleMLKit/ImageLabeling" spec="8.0.0" />'));
assert(plugin.includes('import MLKitImageLabeling'));
assert(plugin.includes('@objc(labelImage:)'));
assert(plugin.includes('ImageLabeler.imageLabeler(options: options)'));
assert(plugin.includes('"confidence": Double($0.confidence)'));
assert(plugin.includes('"index": $0.index'));

process.stdout.write('iOS image labeling contract tests passed.\n');
