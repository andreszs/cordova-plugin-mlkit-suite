# Changelog

## 0.4.0 - 2026-10-30

- Removed Selfie Segmentation from Android, iOS, and the JavaScript API. ML Kit offers this module only as a bundled dependency; measured against the Android universal debug APK, removing it reduced the application by 77.84 MB (74.23 MiB), from 84.81 MB to 6.97 MB, primarily by eliminating four ABI variants of `libxeno_native.so` and the bundled model.
- Fixed Face Capture compilation with current Xcode versions by scheduling Core Motion updates on the required serial `OperationQueue`.

## 0.3.0 - 2026-10-29

### Face capture

- Added ordered gesture challenges with up to five `OPEN_MOUTH`, `BLINK`, `SMILE`, `LOOK_UP`, `LOOK_DOWN`, `NOD`, or `SHAKE` gestures on Android and iOS. Removed the single-direction `TURN_LEFT` and `TURN_RIGHT` gestures in favor of the symmetric two-stage `SHAKE` challenge.
- Added configurable capture checkpoints with `captureAfterGesture`, keeping an intermediate JPEG private until every later gesture succeeds and discarding it when the sequence fails or times out.
- Added `gestureTimeoutMs`, `neutralTimeoutMs`, `retryMessageMs`, and `revealCapture`, including full-sequence retry behavior and the option to hide the exact capture moment.
- Added the optional `requireDeviceVertical` gravity-sensor gate, enabled by default, requiring the phone to remain within ±10° of vertical before face or gesture validation begins.
- Calibrated movements from a stable neutral pose, prevented startup motion from satisfying the first gesture, added phase-specific instructions and 500 ms success feedback, and tolerated tracking interruptions up to 300 ms during an active gesture.
- Redesigned the native overlay with a responsive oval, circular close button, animated segmented progress, compact small-screen layouts, synchronized final checkmark/beep/result feedback, and no detected-face rectangle or redundant secondary instruction.
- Expanded debug mode with only the active checks, live device tilt and centering offsets, and contextual gesture phases, measurements, thresholds, and acceptance meters.
- Fixed centering by projecting ML Kit coordinates into the visible oval-relative preview geometry without changing CameraX zoom or field of view, and removed Android's conflicting fixed-point JPEG recheck.
- Disabled `eyesOpen` and `neutralExpression` by default, increased the default session timeout to 90 seconds, and removed the obsolete face `instructionText` option.

### Fixes and documentation

- Fixed an Android 10 overlay crash caused by using a newer `CharSequence.isEmpty()` API.
- Reorganized README screenshots into their corresponding API modules, added direct module links, and matched the API order to the demo application.

## 0.2.2 - 2026-10-26

- Added trusted npm publishing through GitHub Actions with verifiable provenance.
- Improved package, Cordova, GitHub, and README metadata for project discovery.

## 0.2.1 - 2026-10-26

- Added the optional `beepEnabled` Face Capture confirmation sound, enabled by default on Android and iOS.
- Reused one synthesized native confirmation tone for Barcode Scanning and Face Capture.

## 0.2.0 - 2026-10-26

- Added default-model Image Labeling on Android and iOS, including configurable Android model delivery.
- Added bundled Selfie Segmentation on Android and iOS with transparent PNG output.
- Added unbundled Subject Segmentation on Android with transparent PNG output.
- Added frozen-frame barcode success feedback that animates the guide onto the detected code on Android and iOS.
- Added the optional `beepEnabled` barcode confirmation sound, enabled by default on Android and iOS.
- Fixed iOS live barcode geometry and explicit camera mirroring configuration.
- Fixed guided face capture on iOS by producing the final JPEG from the same portrait video frame accepted by ML Kit, avoiding a second EXIF-dependent detection pass.
- Added per-check colors to the iOS face capture debug overlay.
- Improved iOS static text recognition for rotated gallery images by evaluating all four cardinal orientations.

## 0.1.0

- Initial Android and iOS release; requires Android 7/API 24 with `cordova-android` 12–15, or iOS 15.5 with `cordova-ios` 8.
