package com.andreszs.plugins.mlkitsuite;

import java.nio.charset.StandardCharsets;

public final class BarcodeTextDecoderTest {
    public static void main(String[] args) {
        assertEquals(
            "00545207709@G\u00d3MEZ@JOS\u00c9 DAMI\u00c1N",
            BarcodeTextDecoder.decode(
                "00545207709@G\u00d3MEZ@JOS\u00c9 DAMI\u00c1N".getBytes(StandardCharsets.ISO_8859_1),
                "00545207709@GMEZ@JOS DAMIN",
                null
            )
        );
        assertEquals(
            "G\u00d3MEZ",
            BarcodeTextDecoder.decode("G\u00d3MEZ".getBytes(StandardCharsets.UTF_8), null, null)
        );
        assertEquals("fallback", BarcodeTextDecoder.decode(null, "fallback", "display"));
        assertEquals("display", BarcodeTextDecoder.decode(null, null, "display"));
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">.");
        }
    }
}
