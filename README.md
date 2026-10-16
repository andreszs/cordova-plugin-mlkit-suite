![npm version](https://img.shields.io/npm/v/cordova-plugin-mlkit-suite) ![GitHub package.json version](https://img.shields.io/github/package-json/v/andreszs/cordova-plugin-mlkit-suite?color=FF6D00&label=main&logo=github)

# cordova-plugin-mlkit-suite

Extensible Google ML Kit integration for Cordova on Android and iOS. Version `0.1.0` includes guided face capture, camera and still-image barcode scanning, Latin text recognition, and the native Android document scanner.

> [!WARNING]
> This plugin is in active development and has not been published to npm. Its API, defaults, quality thresholds, and native behavior may change before a stable release. Validate every required workflow on representative physical devices before using it in production.

## Table of contents

- [Compatibility](#compatibility)
- [Installation](#installation)
- [Android model delivery](#android-model-delivery)
  - [Install with a delivery mode](#install-with-a-delivery-mode)
  - [Switch modes](#switch-modes)
  - [Manual source configuration](#manual-source-configuration)
  - [Choosing a delivery mode](#choosing-a-delivery-mode)
- [API overview](#api-overview)
- [Capabilities](#capabilities)
- [Document scanning](#document-scanning)
  - [Document options](#document-options)
  - [Document result](#document-result)
- [Text recognition](#text-recognition)
- [Barcode detection in an image](#barcode-detection-in-an-image)
- [Camera barcode scanning](#camera-barcode-scanning)
  - [Barcode formats](#barcode-formats)
  - [Camera barcode options](#camera-barcode-options)
- [Face capture](#face-capture)
  - [Face options](#face-options)
  - [Optional face checks](#optional-face-checks)
  - [Face result](#face-result)
- [Cancellation and errors](#cancellation-and-errors)
- [Architecture and behavior](#architecture-and-behavior)
- [Dependencies and licenses](#dependencies-and-licenses)
- [Privacy](#privacy)
- [Known limitations](#known-limitations)
- [Tests](#tests)
- [Changelog](#changelog)

## Compatibility

The Android implementation supports `cordova-android` 11 through 15 from one codebase. It selects tested CameraX and AndroidX versions from the installed `cordova-android` major version.

| cordova-android | CameraX | Face Detection | Barcode Scanning | Text Recognition | Document Scanner | Reference toolchain |
| --- | --- | --- | --- | --- | --- | --- |
| 11.x | 1.1.0 | 16.1.5 | 17.2.0 | 16.0.1 | 16.0.0 | compileSdk 32 / AGP 7.2.1 / JDK 11 |
| 12.x | 1.2.3 | 16.1.7 | 17.2.0 | 16.0.1 | 16.0.0 | compileSdk 33 / AGP 7.4.2 / JDK 11 |
| 13.x | 1.4.2 | 16.1.7 | 17.2.0 | 16.0.1 | 16.0.0 | compileSdk 34 / AGP 8.3.0 / JDK 17 |
| 14.x | 1.5.2 | 16.1.7 | 17.2.0 | 16.0.1 | 16.0.0 | compileSdk 35 / AGP 8.7.3 / JDK 17 |
| 15.x | 1.6.0 | 16.1.7 | 17.2.0 | 16.0.1 | 16.0.0 | compileSdk 36 / AGP 8.10.1 / JDK 17 |

With `bundled` delivery, face capture, barcode processing, and text recognition support Android 5.1 / API 22 and newer. `play-services` delivery and the Google Play services document scanner require Android 6.0 / API 23 or newer; the document scanner also requires at least 1.7 GB of total RAM. On an unsupported device, `MlKit.scanDocument()` rejects with `UNSUPPORTED_DEVICE`; the other modules remain available when their selected dependencies support the device.

Face, barcode, and text models use the selected Android delivery mode; `bundled` is the default. The Android document-scanner UI and model are always delivered dynamically by Google Play services and can require a first-use download. See [Android model delivery](#android-model-delivery) for the available strategies. `cordova-android` 11 uses dependency versions compatible with compileSdk 32; newer Cordova Android versions use their normal modern dependency graph. Unknown Cordova Android toolchains fail at build time instead of silently selecting untested versions.

The iOS implementation supports `cordova-ios` 8.x and requires iOS 15.5 or newer. Face capture, still-image barcode detection, and Latin text recognition use Google ML Kit CocoaPods `8.0.0`. Google does not provide the ML Kit Document Scanner API for iOS, and the camera barcode UI is currently Android-only; these methods reject with `UNSUPPORTED_PLATFORM` on iOS.

## Installation

Until an npm release is published, install directly from GitHub:

```bash
cordova plugin add https://github.com/andreszs/cordova-plugin-mlkit-suite.git
```

To test a local checkout:

```bash
cordova plugin add /absolute/path/to/cordova-plugin-mlkit-suite
```

The plugin requests camera access. Generated images and PDFs are written to the application's private cache, so no storage or Photo Library permission is required.

On iOS, configure the deployment target and a camera-purpose string in the host application's `config.xml`:

```xml
<platform name="ios">
    <preference name="deployment-target" value="15.5" />
    <edit-config file="*-Info.plist" mode="merge" target="NSCameraUsageDescription">
        <string>Use the camera to capture a photo.</string>
    </edit-config>
</platform>
```

## Android model delivery

ML Kit offers two [model installation paths](https://developers.google.com/ml-kit/tips/installation-paths) for Face Detection, Barcode Scanning, and Text Recognition: package each model inside the application, or request the corresponding module from Google Play services. This choice is independent of APK versus AAB packaging. An AAB removes unused CPU architectures from each device download, but it does not automatically convert a bundled ML Kit dependency into an on-demand one.

The Android installation variable `ANDROID_MLKIT_MODEL_DELIVERY` selects one mode for all three modules. Accepted values are `bundled` and `play-services`; the default is `bundled`. Document Scanner is a separate component and is always delivered through Google Play services.

### Install with a delivery mode

Bundled (default, no variable required):

```shell
cordova plugin add https://github.com/andreszs/cordova-plugin-mlkit-suite.git
```

Explicitly bundled:

```shell
cordova plugin add https://github.com/andreszs/cordova-plugin-mlkit-suite.git --variable ANDROID_MLKIT_MODEL_DELIVERY=bundled
```

Google Play services delivery:

```shell
cordova plugin add https://github.com/andreszs/cordova-plugin-mlkit-suite.git --variable ANDROID_MLKIT_MODEL_DELIVERY=play-services
```

Run the command from the Cordova application root. Cordova records the selected variable under `cordova.plugins["cordova-plugin-mlkit-suite"]` in the application's `package.json`; do not edit `plugin.xml`, `plugins/`, `platforms/android/`, or a generated Android manifest after installation. The plugin uses the variable while it is installed to select Gradle dependencies and, for `play-services`, add `face,barcode,ocr` prefetch metadata.

In short: the plugin's `plugin.xml` declares the variable and its `bundled` default, the host application's `package.json` stores the chosen value, and `src/android/mlkit-suite.gradle` applies it at Android build time. There is no post-install XML step.

`MlKit.getCapabilities()` reports the effective value as `modelDelivery`, and each affected module reports its `bundled` boolean.

### Switch modes

The mode is compiled into the Android application and cannot be changed at runtime. To switch an existing installation, remove and add only this plugin with the new value, then rebuild:

```shell
cordova plugin rm cordova-plugin-mlkit-suite
cordova plugin add https://github.com/andreszs/cordova-plugin-mlkit-suite.git --variable ANDROID_MLKIT_MODEL_DELIVERY=play-services
cordova clean android
cordova build android
```

Use `bundled` in the same command to switch back. Reinstalling every plugin or recreating the whole project is unnecessary. Merely changing the saved value in `package.json` does not rewrite the already installed native files; remove/add the plugin after changing it.

### Manual source configuration

The variable is the recommended method. Manual edits remain useful for a fork that mixes delivery per module instead of switching all three together:

- Edit [`src/android/mlkit-suite.gradle`](src/android/mlkit-suite.gradle) before installing the fork. Choose the `com.google.mlkit:*` dependency for bundled delivery or the corresponding `com.google.android.gms:play-services-mlkit-*` dependency for Google Play services.
- In the same file, set `mlKitSuiteDependencyMetadataValue` to only the unbundled modules: `face`, `barcode`, and/or `ocr`. Use `com.google.mlkit.vision.DEPENDENCIES` as the metadata name whenever the list is non-empty.
- Reinstall the plugin from that checkout. Never edit generated copies under `plugins/` or `platforms/android/`; Cordova replaces them.

The dependency and manifest metadata must agree. Metadata requests prefetch but does not remove a bundled model; changing only the dependency does not request prefetch. The current unbundled artifacts are Face Detection `17.1.0`, Barcode Scanning `18.3.1`, and Text Recognition `19.0.1`.

### Choosing a delivery mode

| Mode | Advantages | Trade-offs | Estimated current CA15 payload |
| --- | --- | --- | --- |
| `bundled` | Immediate offline first use; supports API 22 | Larger APK/AAB; model updates require a new app build | ~98.5 MB in a universal APK; ~21–33 MB in a single-ABI/AAB device delivery |
| `play-services` | Much smaller application package; modules can be shared and updated by Google Play services | Requires API 23+, compatible Google Play services, storage and normally network on first use | Bundled payload avoided; small client libraries remain in the app |

The bundled figures above are compressed-payload measurements from the current `cordova-android@15` debug APK using Face Detection `16.1.7`, Barcode Scanning `17.2.0`, and Text Recognition `16.0.1`. The universal APK contains approximately 37.1 MB of Face Detection files, 19.1 MB of Barcode Scanning files, and 42.3 MB of Text Recognition files. They total approximately 98.5 MB (93.9 MiB). A per-device AAB delivery retains one native ABI, reducing the combined model payload to roughly 21–33 MB depending on the device architecture. These figures estimate the incremental packaged files, not the final application size, and can change with dependency updates, ABI selection, compression, and release optimization.

With `play-services`, the manifest requests installation of all three modules after a Play Store install. A sideloaded APK must not assume prefetch has completed: first use can trigger a download and may initially return no result. `MlKit.prepareDocumentScanner()` prepares only the separate Document Scanner component.

## API overview

| Method | Android | iOS | Purpose |
| --- | --- | --- | --- |
| `MlKit.getCapabilities()` | yes | yes | Describes modules available on the current platform and device. |
| `MlKit.prepareDocumentScanner()` | API 23+ and 1.7 GB RAM | no | Checks and installs the on-demand Google Play services scanner component. |
| `MlKit.scanDocument(options)` | API 23+ and 1.7 GB RAM | no | Opens Google's native document capture, crop, filter, and cleanup UI. |
| `MlKit.recognizeText(imagePath)` | yes | yes | Returns plain Latin-script text from a local image. |
| `MlKit.scanBarcodes(imagePath, options)` | yes | yes | Returns every matching barcode detected in a local image. |
| `MlKit.barcode.scan(options)` | yes | no | Opens a high-resolution rear-camera barcode scanner. |
| `MlKit.face.capture(options)` | yes | yes | Opens guided front-camera face capture with automatic capture. |

All methods return Promises. The plugin processes content locally and does not interpret, validate, upload, or transform decoded payloads for a particular country or document type.

## Capabilities

Use `getCapabilities()` to discover platform and device support:

```javascript
const capabilities = await MlKit.getCapabilities();

if (capabilities.document.scan && capabilities.document.deviceSupported) {
    // The native document flow can be requested on this device.
}
```

The result describes `face`, `barcode`, `document`, and `text`. On Android, `modelDelivery` reports `bundled` or `play-services`; `face.bundled`, `barcode.bundled`, and `text.bundled` reflect that selection. `offline` remains `false` because the document scanner is always dynamically delivered.

## Document scanning

`MlKit.scanDocument(options)` opens the Google ML Kit Document Scanner on Android. The native flow handles capture, automatic edge detection, crop adjustment, rotation, cleanup, filters, and optional gallery import.

The scanner UI, logic, and models are an on-demand Google Play services component. Call `prepareDocumentScanner()` before opening the scanner when the application wants to show its own progress UI during a possible first-use download. It resolves immediately when the component is already available and otherwise resolves only after installation completes:

```javascript
showProgress("Preparing scanner… First use may take a moment.");
try {
    await MlKit.prepareDocumentScanner();
} finally {
    hideProgress();
}
```

Applications may use any progress UI. The plugin does not impose a spinner dependency.

```javascript
const result = await MlKit.scanDocument({
    pageLimit: 4,
    galleryImportAllowed: true,
    resultFormats: ["JPEG", "PDF"],
    scannerMode: "FULL"
});

if (result.status === "success") {
    document.querySelector("img").src = result.pages[0].imageUri;
    console.log(result.pdf && result.pdf.path);
}
```

The method performs device checks before requesting the Google scanner. Android versions below API 23 and devices with less than 1.7 GB of total RAM reject with `UNSUPPORTED_DEVICE` instead of opening native scanner code.

### Document options

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| `pageLimit` | integer `1–100` | `1` | Maximum number of pages in one scan. |
| `galleryImportAllowed` | `boolean` | `true` | Allows importing document images from the gallery. |
| `resultFormats` | `string[] \| string` | `["JPEG", "PDF"]` | Requests JPEG pages, a PDF, or both. A comma-separated string is accepted. `JPG` aliases `JPEG`. |
| `scannerMode` | `"BASE" \| "BASE_WITH_FILTER" \| "FULL"` | `"FULL"` | Selects the native editing feature set. `BASE` still includes crop, rotation, page removal, and page reordering. |

> [!IMPORTANT]
> Google does not provide an option to disable crop and rotation in its Document Scanner UI. Even `BASE`, the least permissive mode, retains basic editing because the native flow is designed to leave the user in control of the pages returned to the application. Applications that require an immutable front/back capture must use a dedicated camera flow instead of `MlKit.scanDocument()`.

Unknown options, invalid types, empty result formats, and out-of-range values reject with `INVALID_ARGUMENT`.

### Document result

```json
{
  "status": "success",
  "pages": [
    {
      "imagePath": "/private/cache/mlkit-suite/document-...-page-1.jpg",
      "imageUri": "https://localhost/_mlkit_suite_image_/document-...-page-1.jpg",
      "mimeType": "image/jpeg",
      "bytes": 182734,
      "width": 1440,
      "height": 1920
    }
  ],
  "pdf": {
    "path": "/private/cache/mlkit-suite/document-....pdf",
    "uri": "https://localhost/_mlkit_suite_document_/document-....pdf",
    "mimeType": "application/pdf",
    "bytes": 251809,
    "pageCount": 1
  }
}
```

`pages` is empty when JPEG was not requested. `pdf` is `null` when PDF was not requested. Use `imageUri` for an `<img>` inside the Cordova WebView. Native paths are intended for APIs such as `recognizeText()` and `scanBarcodes()`.

## Text recognition

`MlKit.recognizeText(imagePath)` runs the Latin text recognizer selected by the Android delivery mode against a local image and resolves to one plain string. iOS always bundles the recognizer. An image containing no recognized text resolves to `""`.

```javascript
const text = await MlKit.recognizeText(result.pages[0].imagePath);
console.log(text);
```

`imagePath` can be an absolute native file path, a `file:` URI, an Android `content:` URI, or an `imageUri` previously returned by this plugin. Remote HTTP images are not fetched.

## Barcode detection in an image

`MlKit.scanBarcodes(imagePath, options)` analyzes one existing local image and resolves to an array. It does not require the camera UI or two-frame confirmation.

```javascript
const barcodes = await MlKit.scanBarcodes(imagePath, {
    formats: ["PDF_417", "QR_CODE"]
});

for (const barcode of barcodes) {
    console.log(barcode.text, barcode.format);
}
```

Omit `formats` to enable every supported format. Each detected value includes `text`, `format`, numeric `valueType`, `boundingBox`, and `cornerPoints`. An image containing no matching barcode resolves to `[]`.

## Camera barcode scanning

`MlKit.barcode.scan(options)` opens a full-screen rear-camera scanner on Android:

```javascript
const result = await MlKit.barcode.scan({
    formats: ["PDF_417", "QR_CODE"],
    showCloseButton: true,
    showTorchButton: true,
    instructionText: "Place a barcode inside the frame",
    timeoutMs: 45000
});

if (result.status === "success") {
    console.log(result.text, result.format);
}
```

The analyzer requests `1920×1080` frames from CameraX. The actual stream is the closest high-resolution size supported by the selected camera and use-case combination. A result is returned only after two consecutive analyzed frames contain exactly the same non-empty text and format. A missing or different value resets confirmation.

### Barcode formats

Both barcode methods support all 13 formats exposed by ML Kit Barcode Scanning:

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

`PDF417` is accepted as an alias of `PDF_417`. A comma-separated string is accepted in addition to an array.

### Camera barcode options

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| `formats` | `string[] \| string \| null` | all formats | Formats to detect. Restricting formats generally improves speed. |
| `showCloseButton` | `boolean` | `true` | Shows the native close button. Android Back still cancels when hidden. |
| `showTorchButton` | `boolean` | `true` | Shows a torch toggle when the rear camera has flash hardware. |
| `instructionText` | `string \| null` | `"Place a barcode inside the frame"` | Text shown above the guide. An empty string hides it. |
| `timeoutMs` | integer `0–600000` | `0` | Session timeout in milliseconds. `0` disables it. |

## Face capture

`MlKit.face.capture(options)` opens a full-screen native front-camera view and resolves after a valid JPEG is captured:

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
```

Capture starts automatically only after all enabled checks remain acceptable for a short stability window.

### Face options

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| `outputWidth` | integer `320–2048` | `600` | Exact JPEG width in pixels. |
| `outputHeight` | integer `320–2048` | `720` | Exact JPEG height in pixels and crop aspect ratio. |
| `jpegQuality` | integer `50–100` | `95` | JPEG encoding quality. |
| `showCloseButton` | `boolean` | `true` | Shows the native close button. |
| `debugMode` | `boolean` | `false` | Shows face bounds and live quality values without weakening validation. |
| `instructionText` | `string \| null` | `"Place your face inside the oval"` | Text shown above the guide. An empty string hides it. |
| `checks` | `object \| null` | all enabled | Enables or disables individual capture gates. |
| `timeoutMs` | integer `0–600000` | `0` | Session timeout in milliseconds. `0` disables it. |

### Optional face checks

| Check | Default | Purpose |
| --- | --- | --- |
| `singleFace` | `true` | Requires exactly one detected face. |
| `faceSize` | `true` | Rejects faces that are too small or too close. |
| `centering` | `true` | Requires the face near the guide center. |
| `pose` | `true` | Limits head yaw, pitch, and roll. |
| `eyesOpen` | `true` | Requires both eye-open probabilities to pass the internal threshold. |
| `neutralExpression` | `true` | Rejects a high smiling probability. |
| `stability` | `true` | Requires a stable face position before capture. |
| `sharpness` | `true` | Rejects low-detail frames and final images. |
| `brightness` | `true` | Rejects images that are too dark or too bright. |

### Face result

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
  "quality": { "brightness": 0.57, "sharpness": 0.41 },
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

Cache files are temporary and may be removed by the operating system.

## Cancellation and errors

User cancellation of an interactive native UI resolves rather than rejects:

```javascript
{ "status": "cancelled", "reason": "user" }
```

Other failures reject with a stable shape:

```json
{
  "code": "UNSUPPORTED_DEVICE",
  "message": "ML Kit Document Scanner requires Android 6.0 or newer and at least 1.7 GB of total RAM.",
  "recoverable": false
}
```

| Code | Meaning |
| --- | --- |
| `INVALID_ARGUMENT` | An argument, option, or check is invalid. |
| `INVALID_IMAGE` | The supplied local image cannot be opened or decoded. |
| `BUSY` | Another interactive native session is active. |
| `CAMERA_PERMISSION_DENIED` | Camera permission is unavailable. |
| `CAMERA_UNAVAILABLE` | The requested camera or pipeline could not be opened. |
| `CAPTURE_FAILED` | No acceptable final face photo was obtained. |
| `PROCESSING_FAILED` | ML Kit or output persistence could not complete. |
| `SCANNER_UNAVAILABLE` | The document scanner could not be downloaded, initialized, or opened. |
| `MODULE_DOWNLOAD_CANCELLED` | Google Play services cancelled the scanner-component installation. |
| `MODULE_DOWNLOAD_FAILED` | Google Play services could not install the scanner component. |
| `UNSUPPORTED_DEVICE` | The current Android version, memory, or Google implementation does not support the document scanner. |
| `UNSUPPORTED_PLATFORM` | The requested module is not implemented on the current platform. |
| `TIMEOUT` | The configured interactive-session timeout elapsed. |

## Architecture and behavior

- The JavaScript namespace is module-oriented and leaves room for additional ML Kit APIs.
- Android uses CameraX for camera preview, analysis, and still capture; iOS uses AVFoundation for face capture.
- Android document scanning delegates its UI to the Google Play services ML Kit scanner and copies selected outputs into private application cache.
- Android uses the configured delivery mode for face, Latin text-recognition, and barcode-scanning models; iOS bundles its ML Kit pods.
- Camera barcode scanning is generic, uses the rear camera, supports format filtering and torch control, and confirms a value across two frames.
- Face capture uses live validation and reprocesses the final still before accepting it.
- The plugin never applies country-specific document parsing or validation.
- Processing is on-device. The plugin contains no upload or analytics code.

Face detection is not face recognition, identity verification, presentation-attack detection, or liveness detection. Applications requiring those properties must implement and validate them separately.

## Dependencies and licenses

- [Google ML Kit Document Scanner](https://developers.google.com/ml-kit/vision/doc-scanner) under the Google APIs Terms of Service.
- [Google ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2) under its published Google SDK terms.
- [Google ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning) under its published Google SDK terms.
- [Google ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection) under its published Google SDK terms.
- [AndroidX CameraX](https://developer.android.com/media/camera/camerax) under the Apache License 2.0.
- AVFoundation, UIKit, Core Image, and WebKit are Apple system frameworks.

The plugin source is licensed under Apache License 2.0. Platform dependencies retain their own licenses and terms.

## Privacy

The plugin requests camera permission and processes camera frames and selected local images. It does not include analytics, advertising, network upload code, face recognition, or persistent biometric storage. The document scanner is provided dynamically through Google Play services. The host application controls returned files and is responsible for privacy disclosures, consent, retention, transport security, and regulatory compliance.

The included Apple privacy manifest declares no tracking, collected data, or required-reason API use by the plugin itself. Host applications and transitive SDKs must provide their own accurate manifests.

## Known limitations

- The document scanner is Android-only, requires API 23 and at least 1.7 GB RAM, depends on Google Play services, and can require a first-use component download.
- The Google Document Scanner always allows basic page editing, including crop and rotation; its native UI cannot be configured as an immutable front/back capture flow.
- Camera barcode scanning is currently Android-only. Still-image barcode detection is available on both platforms.
- Text recognition currently includes only the Latin script recognizer.
- Static-image APIs accept local resources only and do not download remote URLs.
- Face quality thresholds are heuristics and require calibration across devices, skin tones, lighting, optics, and accessibility needs.
- The face oval is a visual guide; the returned JPEG is rectangular.
- Cache URLs are session-local and are not durable storage.
- A physical device is required to validate camera and document-scanner behavior.
- The iOS implementation must be compiled and device-tested on macOS with a compatible Xcode and CocoaPods toolchain.

## Tests

Run the JavaScript and Java contract tests:

```bash
npm test
```

Before release, validate installation and a debug build with each supported Cordova Android major. Build iOS with `cordova-ios` 8.x on macOS, then test the native flows on physical devices.

## Changelog

### 0.1.0

- Added the Android install variable `ANDROID_MLKIT_MODEL_DELIVERY` with `bundled` and `play-services` modes.
- Added `MlKit.prepareDocumentScanner()` so applications can display progress while Google Play services installs the on-demand scanner component.
- Added Android `MlKit.scanDocument(options)` using the dynamically delivered ML Kit Document Scanner `16.0.0`.
- Added structured `UNSUPPORTED_DEVICE` handling for Android versions below API 23, devices below 1.7 GB total RAM, and native unsupported responses.
- Added `MlKit.recognizeText(imagePath)` on Android and iOS with bundled Latin text recognition.
- Added `MlKit.scanBarcodes(imagePath, options)` on Android and iOS with all supported barcode formats.
- Added Android `MlKit.barcode.scan(options)` using bundled ML Kit Barcode Scanning `17.2.0`.
- Added high-resolution CameraX analysis, rear-camera preview, optional torch control, and two-frame barcode confirmation.
- Kept all document and barcode payload handling generic and country-agnostic.
- Added the extensible `MlKit` namespace and capability discovery.
- Added `MlKit.face.capture(options)` for Android and iOS.
- Added CameraX and AVFoundation front-camera flows with automatic capture.
- Added face count, framing, pose, eye, expression, stability, brightness, and sharpness checks.
- Added final-still revalidation, exact output sizing, JPEG encoding, private-cache storage, and WebView-safe cache URLs.
- Added strict option validation, structured errors, privacy metadata, and contract tests.
