# Changelog

## Unreleased

- Added default-model Image Labeling on Android and iOS, including configurable Android model delivery.

- Fixed iOS live barcode geometry and explicit camera mirroring configuration.
- Fixed guided face capture on iOS by producing the final JPEG from the same portrait video frame accepted by ML Kit, avoiding a second EXIF-dependent detection pass.
- Added per-check colors to the iOS face capture debug overlay.
- Improved iOS static text recognition for rotated gallery images by evaluating all four cardinal orientations.
