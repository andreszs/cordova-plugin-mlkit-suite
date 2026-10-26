# Changelog

## Unreleased

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
