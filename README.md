![npm version](https://img.shields.io/npm/v/cordova-plugin-mlkit-suite) ![GitHub package.json version](https://img.shields.io/github/package-json/v/andreszs/cordova-plugin-mlkit-suite?color=FF6D00&label=main&logo=github)

# cordova-plugin-mlkit-suite

Extensible Google ML Kit integration for Cordova on Android and iOS. Version `0.1.0` provides guided face detection with automatic front-camera capture, plus a generic Android barcode scanner backed by the bundled ML Kit model.

> [!WARNING]
> This plugin is in active development. Its API, defaults, quality thresholds, and native behavior may change before a stable release. Validate every required workflow on representative physical devices before using it in production.

## Table of contents

- [Compatibility](#compatibility)
- [Installation](#installation)
- [Usage](#usage)
  - [Capabilities](#capabilities)
  - [Face capture](#face-capture)
  - [Options](#options)
  - [Optional checks](#optional-checks)
  - [Barcode scanning](#barcode-scanning)
  - [Barcode options](#barcode-options)
- [Results](#results)
- [Cancellation and errors](#cancellation-and-errors)
- [Architecture and behavior](#architecture-and-behavior)
- [Dependencies and licenses](#dependencies-and-licenses)
- [Privacy](#privacy)
- [Known limitations](#known-limitations)
- [Tests](#tests)
- [Changelog](#changelog)

## Compatibility

The Android implementation supports `cordova-android` 11 through 15 from one codebase. It selects a CameraX version from the installed `cordova-android` major version.

| cordova-android | CameraX | ML Kit Face Detection | ML Kit Barcode Scanning | Reference toolchain |
| --- | --- | --- | --- | --- |
| 11.x | 1.1.0 | 16.1.5 | 17.2.0 | compileSdk 32 / AGP 7.2.1 / JDK 11 |
| 12.x | 1.2.3 | 16.1.7 | 17.2.0 | compileSdk 33 / AGP 7.4.2 / JDK 11 |
| 13.x | 1.4.2 | 16.1.7 | 17.2.0 | compileSdk 34 / AGP 8.3.0 / JDK 17 |
| 14.x | 1.5.2 | 16.1.7 | 17.2.0 | compileSdk 35 / AGP 8.7.3 / JDK 17 |
| 15.x | 1.6.0 | 16.1.7 | 17.2.0 | compileSdk 36 / AGP 8.10.1 / JDK 17 |

Android support starts at Android 5.1 / API 22 through `cordova-android` 11. The plugin uses bundled ML Kit models, so detection does not wait for a model download. `cordova-android` 11 uses Face Detection `16.1.5` because newer releases pull AndroidX metadata that requires compileSdk 33; Cordova Android 12–15 use `16.1.7`. Barcode Scanning is pinned to `com.google.mlkit:barcode-scanning:17.2.0` on every supported Android platform. Unknown Cordova Android toolchains fail at build time instead of silently selecting untested dependency versions.

The iOS implementation supports `cordova-ios` 8.x and requires iOS 15.5 or newer. It uses the `GoogleMLKit/FaceDetection` CocoaPod `8.0.0`; the host therefore needs CocoaPods and a compatible Xcode toolchain.

## Installation

Install from npm:

```bash
cordova plugin add cordova-plugin-mlkit-suite
```

Or install directly from GitHub:

```bash
cordova plugin add https://github.com/andreszs/cordova-plugin-mlkit-suite.git
```

To test an unpublished checkout, install it by local path:

```bash
cordova plugin add /absolute/path/to/cordova-plugin-mlkit-suite
```

The plugin requests only camera access. Images are written to the application's private cache, so no storage or Photo Library permission is required.

On iOS, configure the deployment target and a camera-purpose string in the host application's `config.xml`:

```xml
<platform name="ios">
    <preference name="deployment-target" value="15.5" />
    <edit-config file="*-Info.plist" mode="merge" target="NSCameraUsageDescription">
        <string>Use the camera to capture a face photo.</string>
    </edit-config>
</platform>
```

## Usage

### Capabilities

Use `getCapabilities()` when an application needs to discover the modules exposed by the installed plugin version:

```javascript
const capabilities = await MlKit.getCapabilities();

if (capabilities.face.capture) {
    // Guided face capture is available.
}
```

On Android, version `0.1.0` reports the `face` and `barcode` modules. On iOS, `barcode.scan` is reported as unavailable and rejects with `UNSUPPORTED_PLATFORM` if called.

### Face capture

`MlKit.face.capture(options)` opens a full-screen native camera view and resolves after a valid photo is captured:

```javascript
const result = await MlKit.face.capture({
    outputWidth: 600,
    outputHeight: 720,
    jpegQuality: 95,
    showCloseButton: true,
    debugMode: false,
    instructionText: "Place your face inside the oval",
    checks: {
        singleFace: true,
        faceSize: true,
        centering: true,
        pose: true,
        eyesOpen: true,
        neutralExpression: true,
        stability: true,
        sharpness: true,
        brightness: true
    },
    timeoutMs: 45000
});

if (result.status === "cancelled") {
    return;
}

document.querySelector("img").src = result.imageUri;
```

The native view always uses the front camera and portrait orientation. Capture starts automatically only after all enabled checks remain acceptable for a short stability window.

### Options

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| `outputWidth` | integer `320–2048` | `600` | Exact JPEG width in pixels. |
| `outputHeight` | integer `320–2048` | `720` | Exact JPEG height in pixels. Together with width, defines the crop aspect ratio. |
| `jpegQuality` | integer `50–100` | `95` | JPEG encoding quality. |
| `showCloseButton` | `boolean` | `true` | Shows the native close button. Android Back still cancels when hidden. |
| `debugMode` | `boolean` | `false` | Shows the detected face rectangle and live quality values. It does not weaken validation. |
| `instructionText` | `string \| null` | `"Place your face inside the oval"` | Text displayed above the guide. An empty string hides it. |
| `checks` | `object \| null` | all enabled | Enables or disables individual capture gates. |
| `timeoutMs` | integer `0–600000` | `0` | Session timeout in milliseconds. `0` disables the timeout. |

Unknown options, invalid types, and out-of-range numbers reject with `INVALID_ARGUMENT`. This prevents configuration typos from silently changing capture behavior.

### Optional checks

| Check | Default | Purpose |
| --- | --- | --- |
| `singleFace` | `true` | Requires exactly one detected face. |
| `faceSize` | `true` | Rejects faces that are too small or too close to the camera. |
| `centering` | `true` | Requires the face to remain near the guide center. |
| `pose` | `true` | Limits head yaw, pitch, and roll. |
| `eyesOpen` | `true` | Requires both eye-open probabilities to pass the configured internal threshold. |
| `neutralExpression` | `true` | Rejects a high smiling probability. |
| `stability` | `true` | Requires a stable face position before automatic capture. |
| `sharpness` | `true` | Rejects low-detail frames and final images. |
| `brightness` | `true` | Rejects images that are too dark or too bright. |

Disabling a check removes that specific gate. The final still photo is always passed through ML Kit again before it is returned.

### Barcode scanning

`MlKit.barcode.scan(options)` opens a full-screen rear-camera scanner on Android. It supports every format exposed by ML Kit Barcode Scanning `17.2.0` and enables all of them when `formats` is omitted:

- `AZTEC`
- `CODABAR`
- `CODE_39`
- `CODE_93`
- `CODE_128`
- `DATA_MATRIX`
- `EAN_8`
- `EAN_13`
- `ITF`
- `PDF_417`
- `QR_CODE`
- `UPC_A`
- `UPC_E`

```javascript
const result = await MlKit.barcode.scan({
    formats: ["PDF_417", "QR_CODE"],
    showCloseButton: true,
    showTorchButton: true,
    instructionText: "Place the barcode inside the frame",
    timeoutMs: 45000
});

if (result.status === "success") {
    console.log(result.text, result.format);
}
```

The analyzer requests `1920×1080` frames from CameraX. The actual stream is the closest high-resolution size supported by the selected camera and use-case combination. A result is returned only after two consecutive analyzed frames contain exactly the same non-empty text and format. A frame without a decoded value, or with a different value or format, resets confirmation.

The scanner is content-agnostic: it does not interpret, validate, upload, or transform the decoded payload.

### Barcode options

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| `formats` | `string[] \| string \| null` | all formats | Formats to detect. A comma-separated string is also accepted. Restricting formats generally improves speed. `PDF417` is accepted as an alias of `PDF_417`. |
| `showCloseButton` | `boolean` | `true` | Shows the native close button. Android Back still cancels when hidden. |
| `showTorchButton` | `boolean` | `true` | Shows a torch toggle when the rear camera has flash hardware. |
| `instructionText` | `string \| null` | `"Place a barcode inside the frame"` | Text displayed above the guide. An empty string hides it. |
| `timeoutMs` | integer `0–600000` | `0` | Session timeout in milliseconds. `0` disables the timeout. |

## Results

### Face capture result

A successful capture resolves with an object like this:

```json
{
  "status": "success",
  "imageUri": "https://localhost/_mlkit_suite_image_/face-...jpg",
  "imagePath": "/private/cache/mlkit-suite/face-...jpg",
  "width": 600,
  "height": 720,
  "mimeType": "image/jpeg",
  "bytes": 78421,
  "capturedAt": "2026-10-15T12:34:56.789Z",
  "quality": {
    "brightness": 0.57,
    "sharpness": 0.41
  },
  "face": {
    "yaw": 1.2,
    "pitch": -0.8,
    "roll": 0.5,
    "leftEyeOpenProbability": 0.98,
    "rightEyeOpenProbability": 0.97,
    "smilingProbability": 0.04
  },
  "appliedChecks": {
    "singleFace": true,
    "faceSize": true,
    "centering": true,
    "pose": true,
    "eyesOpen": true,
    "neutralExpression": true,
    "stability": true,
    "sharpness": true,
    "brightness": true
  }
}
```

Use `imageUri` in the Cordova WebView. `imagePath` is the native absolute path and is provided for native interoperability; it is not a portable WebView URL. Cache files are temporary and may be removed by the operating system.

### Barcode scan result

```json
{
  "status": "success",
  "text": "decoded payload",
  "format": "QR_CODE"
}
```

`text` is ML Kit's raw decoded string and is returned unchanged. `format` is one of the supported format names listed above.

## Cancellation and errors

User cancellation resolves rather than rejects:

```javascript
{ "status": "cancelled", "reason": "user" }
```

Other failures reject with a stable shape:

```json
{
  "code": "CAMERA_PERMISSION_DENIED",
  "message": "Camera permission was denied.",
  "recoverable": true
}
```

Error codes currently include:

| Code | Meaning |
| --- | --- |
| `INVALID_ARGUMENT` | An option or check is invalid. |
| `BUSY` | Another capture session is active. |
| `CAMERA_PERMISSION_DENIED` | Camera permission is unavailable. |
| `CAMERA_UNAVAILABLE` | A front camera or camera pipeline could not be opened. |
| `CAPTURE_FAILED` | No acceptable final photo was obtained. |
| `PROCESSING_FAILED` | The captured photo could not be decoded, cropped, or encoded. |
| `TIMEOUT` | The configured session timeout elapsed. |
| `UNSUPPORTED_PLATFORM` | The requested module is not implemented on the current platform. |

## Architecture and behavior

- The JavaScript namespace is module-oriented: `MlKit.face` and `MlKit.barcode` are independent modules, and future ML Kit APIs can be added as siblings.
- Android uses CameraX for preview, analysis, and still capture. iOS uses AVFoundation for the equivalent camera pipeline.
- Android barcode scanning uses the rear camera, high-resolution analysis, optional torch control, format filtering, and two-frame confirmation.
- Live detection uses ML Kit's fast mode with classification enabled. The captured still is reprocessed using accurate mode and landmarks before it is accepted.
- The preview checks one face, framing, head pose, open eyes, expression, lighting, sharpness, and temporal stability.
- The final JPEG is cropped around the detected face at the requested aspect ratio, resized to the exact requested dimensions, and stored in private cache.
- Processing is on-device. The plugin does not upload, transmit, or retain images outside its cache directory.

Face detection is not face recognition, identity verification, presentation-attack detection, or liveness detection. Applications that require those properties must implement and validate them separately.

## Dependencies and licenses

- [Google ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection) under its published Google SDK terms.
- [Google ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning) under its published Google SDK terms.
- [AndroidX CameraX](https://developer.android.com/media/camera/camerax) under the Apache License 2.0.
- AVFoundation, UIKit, Core Image, and WebKit are Apple system frameworks.

The plugin source is licensed under the Apache License 2.0. Installing this plugin also installs platform dependencies whose own licenses and terms apply.

## Privacy

The plugin requests camera permission and processes camera frames locally. It does not include analytics, advertising, network upload code, face recognition, or persistent biometric storage. The host application controls what happens to a returned JPEG and is responsible for its privacy disclosures, retention policy, consent flow, transport security, and regulatory compliance.

The included Apple privacy manifest declares no tracking, collected data, or required-reason API use by the plugin itself. Host applications and transitive SDKs must provide their own accurate manifests.

## Known limitations

- Quality thresholds are heuristics and require calibration across devices, skin tones, lighting conditions, camera optics, and accessibility needs.
- Eye and smile probabilities may be unavailable for some detections; enabled checks conservatively reject missing eye probabilities.
- The oval is a visual guide, not a mask. The returned JPEG is rectangular.
- Front-camera preview mirroring and final-image orientation can vary by device camera implementation and must be verified on the supported device matrix.
- Cache URLs are session-local artifacts, not durable storage.
- A physical device is required to validate camera behavior. Simulators can validate plugin integration and compilation only.
- Barcode scanning is currently Android-only; iOS reports the module as unavailable.
- Enabling all barcode formats is the most flexible mode but may be slower than selecting expected formats.

## Tests

Run the JavaScript API contract test:

```bash
npm test
```

Before release, validate plugin installation and build at least one application with each supported Cordova Android major. Build the iOS implementation with `cordova-ios` 8.x on macOS, then test the full camera flow on physical devices.

## Changelog

### 0.1.0

- Added Android `MlKit.barcode.scan(options)` using bundled ML Kit Barcode Scanning `17.2.0`.
- Added all 13 ML Kit barcode formats with optional format filtering.
- Added high-resolution `1920×1080` CameraX analysis, rear-camera preview, and optional torch control.
- Added exact text-and-format confirmation across two consecutive analyzed frames.
- Added structured barcode results, strict options, capability discovery, and iOS unsupported-platform reporting.
- Kept barcode payload handling generic and country-agnostic.
- Added the extensible `MlKit` JavaScript namespace and capability discovery.
- Added `MlKit.face.capture(options)` for Android and iOS.
- Added CameraX and AVFoundation front-camera flows with automatic capture.
- Added ML Kit face count, framing, pose, eye, expression, stability, brightness, and sharpness checks.
- Added final-still revalidation, rectangular crop, exact output sizing, JPEG encoding, private-cache storage, and WebView-safe image URLs.
- Added strict option validation, structured errors, privacy metadata, and JavaScript API tests.
