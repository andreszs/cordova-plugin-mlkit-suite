'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');

var root = path.resolve(__dirname, '..');
var pluginXml = fs.readFileSync(path.join(root, 'plugin.xml'), 'utf8');
var plugin = fs.readFileSync(path.join(root, 'src/ios/MlKitSuitePlugin.swift'), 'utf8');
var processor = fs.readFileSync(path.join(root, 'src/ios/TextRecognitionProcessor.swift'), 'utf8');

assert(pluginXml.includes('<source-file src="src/ios/TextRecognitionProcessor.swift" />'));
assert(plugin.includes('let processor = TextRecognitionProcessor()'));
assert(plugin.includes('let image = try self.decodedImage(rawPath)'));
assert(processor.includes('let rawImage = UIImage(cgImage: cgImage'));
assert(processor.includes('[preferred, .up, .right, .down, .left]'));
assert(processor.includes('index: index + 1'));
assert(processor.includes('candidate.score > nextBest!.score'));
assert(processor.includes('CharacterSet.alphanumerics'));

process.stdout.write('iOS static text recognition source contract tests passed.\n');
