# Changelog

## 0.5.1 - 2026-11-26

### Face capture gestures (iOS)

- Ported the 0.5.0 Android liveness gesture detection to iOS, with the same constants and behavior: smoothed head pose (exponential moving average), hysteresis on every gesture, bidirectional `NOD` / `SHAKE` measured as a swing, `LOOK_UP` / `LOOK_DOWN` at 12° / 10°, two-frame blinks, gesture-specific arming neutral, a baseline averaged over the neutral frames, and a capture neutral that enforces the still validator's absolute pose limits when `checks.pose` is enabled.
- Accepting the first half of `NOD` / `SHAKE` no longer pauses detection; the instruction now starts generic and then asks for the missing direction.
- Device verticality now uses 10° / 16° enter/exit hysteresis with a 0.4 s hold. A gesture in progress is only restarted beyond 28° of tilt, while the photo still waits for an upright device, and the return-to-neutral prompt asks to hold the device upright or raise/lower the head when needed.
- Increased the active-gesture face-loss tolerance from 300 ms to 700 ms.
- Added the `face_gesture_nod_up_now` and `face_gesture_shake_right_now` iOS strings.

## 0.5.0 - 2026-11-26

### Face capture gestures (Android)

- Reworked liveness gesture detection so natural movements are accepted on the first attempt: head pose is smoothed with a moving average, and every gesture uses hysteresis (a stricter threshold to start a hold and a looser one to keep it), so a single noisy ML Kit frame no longer resets progress.
- `NOD` and `SHAKE` now accept either direction first and are measured as a swing (8° / 12° to one side, then across neutral with about 14° / 20° of total travel). Accepting the first half no longer pauses detection for 500 ms, and the instruction switches to the missing direction.
- Lowered `LOOK_UP` / `LOOK_DOWN` thresholds to 12° / 10° and accepted deliberate blinks after two consecutive frames.
- The neutral pose before a gesture now only checks the state that gesture needs (for example, head gestures no longer require open eyes or a neutral expression), and its baseline is the average of the neutral frames.
- The neutral pose before the photo now enforces the same absolute pose limits as the final still validation, preventing a successful gesture from being followed by a late "look straight" retry; the overlay guides the user to raise or lower the head while returning.
- Device verticality now uses 10° / 16° enter/exit hysteresis. A gesture in progress is only restarted beyond 28° of tilt, while the photo still waits for an upright device.
- Increased the active-gesture face-loss tolerance from 300 ms to 700 ms.

### Defaults

- Increased the default `gestureTimeoutMs` from 4000 to 6000 and `neutralTimeoutMs` from 2000 to 3000 on Android and iOS.

### Performance and reliability (Android)

- Face analysis runs at 480×640 instead of 1280×720, roughly halving ML Kit latency and sampling head movements more often.
- ML Kit results and still-photo cropping, quality analysis, and JPEG encoding run off the UI thread; late callbacks after the activity closes are dropped safely.
- Still photos are decoded with power-of-two subsampling instead of at full sensor resolution, reducing capture memory and processing time.
- The live analyzer can no longer remain stuck holding a camera frame if ML Kit rejects an input.

### Tests

- Added plain-Java unit tests for the face gesture tracker covering bidirectional `NOD`, continuous `SHAKE`, noise tolerance, jitter rejection, capture pose gating, device tilt gating, `BLINK`, and `SMILE`.

## 0.4.1 - 2026-11-03

- Centered the Android Face Capture oval exactly within the usable portrait viewport while accounting for system bars and display cutouts across Android 7/API 24 and newer.
- Centered the instruction and progress indicator within the remaining spaces above and below the oval, and kept face-position validation synchronized with the displayed guide after inset or viewport changes.
- Locked the Android Face Capture activity to portrait orientation.

## 0.4.0 - 2026-10-30

- Removed Selfie Segmentation from Android, iOS, and the JavaScript API. ML Kit offers this module only as a bundled dependency; measured against the Android universal debug APK, removing it reduced the application by 77.84 MB (74.23 MiB), from 84.81 MB to 6.97 MB, primarily by eliminating four ABI variants of `libxeno_native.so` and the bundled model.
- Fixed Face Capture compilation with current Xcode versions by scheduling Core Motion updates on the required serial `OperationQueue`.
- Fixed the inverted left and right on-screen instructions for the iOS `SHAKE` challenge.

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
