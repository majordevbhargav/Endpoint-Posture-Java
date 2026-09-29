package com.endpointposture.endpoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EndpointService.normalizeMac exists specifically so the same physical
 * device reported in different separator styles (Windows uses hyphens,
 * Cisco ISE uses colons) always resolves to one canonical row. These
 * tests pin that behavior down.
 */
class EndpointServiceNormalizeMacTest {

    @Test
    void hyphenSeparatedIsConvertedToColonSeparated() {
        assertEquals("AA:BB:CC:DD:EE:FF", EndpointService.normalizeMac("aa-bb-cc-dd-ee-ff"));
    }

    @Test
    void lowercaseColonSeparatedIsUppercased() {
        assertEquals("AA:BB:CC:DD:EE:FF", EndpointService.normalizeMac("aa:bb:cc:dd:ee:ff"));
    }

    @Test
    void alreadyNormalizedValueIsUnchanged() {
        assertEquals("AA:BB:CC:DD:EE:FF", EndpointService.normalizeMac("AA:BB:CC:DD:EE:FF"));
    }

    @Test
    void leadingAndTrailingWhitespaceIsTrimmed() {
        assertEquals("AA:BB:CC:DD:EE:FF", EndpointService.normalizeMac("  aa-bb-cc-dd-ee-ff  "));
    }

    @Test
    void mixedCaseWithHyphensIsFullyNormalized() {
        assertEquals("AA:BB:CC:DD:EE:FF", EndpointService.normalizeMac("Aa-bB-cC-dD-eE-fF"));
    }

    @Test
    void windowsStyleAndIseStyleResolveToTheSameCanonicalValue() {
        // This is the actual bug the method exists to prevent: the same
        // device reported by two different collectors must produce one row.
        String windowsStyle = EndpointService.normalizeMac("aa-bb-cc-dd-ee-ff");
        String iseStyle = EndpointService.normalizeMac("AA:BB:CC:DD:EE:FF");
        assertEquals(windowsStyle, iseStyle);
    }
}