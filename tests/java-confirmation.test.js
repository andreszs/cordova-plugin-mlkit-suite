'use strict';

var childProcess = require('child_process');
var fs = require('fs');
var os = require('os');
var path = require('path');

var output = fs.mkdtempSync(path.join(os.tmpdir(), 'mlkit-suite-java-test-'));
var root = path.resolve(__dirname, '..');
var source = path.join(root, 'src', 'android', 'java', 'BarcodeConfirmationTracker.java');
var test = path.join(root, 'tests', 'java', 'BarcodeConfirmationTrackerTest.java');
var executable = process.platform === 'win32' ? '.exe' : '';
var javaHomes = [
    process.env.JAVA_HOME,
    process.platform === 'win32' && 'C:\\Program Files\\Java\\jdk-17',
    process.platform === 'win32' && 'C:\\Program Files\\Java\\jdk-11',
    process.platform === 'win32' && 'C:\\Program Files\\Java\\jdk-1.8',
    process.platform !== 'win32' && '/usr/lib/jvm/default-java'
].filter(Boolean);
var javaHome = javaHomes.find(function (candidate) {
    return fs.existsSync(path.join(candidate, 'bin', 'java' + executable))
        && fs.existsSync(path.join(candidate, 'bin', 'javac' + executable));
});
var javac = javaHome ? path.join(javaHome, 'bin', 'javac' + executable) : 'javac';
var java = javaHome ? path.join(javaHome, 'bin', 'java' + executable) : 'java';

try {
    childProcess.execFileSync(javac, ['-d', output, source, test], { stdio: 'inherit' });
    childProcess.execFileSync(java, [
        '-cp', output, 'com.andreszs.plugins.mlkitsuite.BarcodeConfirmationTrackerTest'
    ], { stdio: 'inherit' });
} finally {
    fs.rmSync(output, { recursive: true, force: true });
}
