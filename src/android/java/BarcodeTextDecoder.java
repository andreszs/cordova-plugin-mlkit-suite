package com.andreszs.plugins.mlkitsuite;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Decodes barcode bytes without losing non-ASCII characters from legacy PDF417 payloads. */
final class BarcodeTextDecoder {
    private BarcodeTextDecoder() { }

    static String decode(byte[] rawBytes, String rawValue, String displayValue) {
        if (rawBytes != null && rawBytes.length > 0) {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawBytes))
                    .toString();
            } catch (CharacterCodingException ignored) {
                return new String(rawBytes, StandardCharsets.ISO_8859_1);
            }
        }
        if (rawValue != null) return rawValue;
        return displayValue == null ? "" : displayValue;
    }
}
