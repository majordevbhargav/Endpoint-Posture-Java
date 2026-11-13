package com.endpointposture.security;

import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import static org.junit.jupiter.api.Assertions.*;

class SecureXmlTest {

    @Test
    void rejectsDoctypeAndExternalEntityDeclarations() {
        String xml = """
                <!DOCTYPE activeList [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <activeList>&xxe;</activeList>
                """;
        assertThrows(SAXException.class, () -> SecureXml.parse(xml));
    }

    @Test
    void doesNotProcessXInclude() throws Exception {
        String xml = """
                <activeList xmlns:xi="http://www.w3.org/2001/XInclude">
                  <xi:include href="file:///etc/passwd" parse="text"/>
                </activeList>
                """;
        var document = SecureXml.parse(xml);
        assertTrue(document.getDocumentElement().getTextContent().isBlank());
    }
}
