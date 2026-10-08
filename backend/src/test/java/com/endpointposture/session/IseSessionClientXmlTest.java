package com.endpointposture.session;

import com.endpointposture.ise.config.IseProperties;
import com.endpointposture.session.dto.IseActiveSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IseSessionClientXmlTest {

    private IseSessionClient client;

    @BeforeEach
    void setUp() {
        IseProperties props = new IseProperties();
        client = new IseSessionClient(props);
    }

    @Test
    void parseActiveList_normalXmlWithMultipleSessions() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <activeList noOfActiveSession="2">
                    <activeSession>
                        <calling_station_id>AA:BB:CC:DD:EE:01</calling_station_id>
                        <framed_ip_address>192.168.1.101</framed_ip_address>
                        <user_name>alice</user_name>
                        <nas_ip_address>10.0.0.1</nas_ip_address>
                    </activeSession>
                    <activeSession>
                        <calling_station_id>AA:BB:CC:DD:EE:02</calling_station_id>
                        <framed_ip_address>192.168.1.102</framed_ip_address>
                        <user_name>bob</user_name>
                    </activeSession>
                </activeList>
                """;

        List<IseActiveSession> result = client.parseActiveList(xml);
        assertEquals(2, result.size());
        assertEquals("AA:BB:CC:DD:EE:01", result.get(0).mac());
        assertEquals("192.168.1.101", result.get(0).ip());
        assertEquals("AA:BB:CC:DD:EE:02", result.get(1).mac());
        assertEquals("192.168.1.102", result.get(1).ip());
    }

    @Test
    void parseActiveList_emptyListXml() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <activeList noOfActiveSession="0">
                </activeList>
                """;

        List<IseActiveSession> result = client.parseActiveList(xml);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseActiveList_nullOrBlankString() throws Exception {
        assertTrue(client.parseActiveList(null).isEmpty());
        assertTrue(client.parseActiveList("").isEmpty());
        assertTrue(client.parseActiveList("   \n\t ").isEmpty());
    }

    @Test
    void parseActiveList_missingIpField() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <activeList noOfActiveSession="1">
                    <activeSession>
                        <calling_station_id>AA:BB:CC:DD:EE:03</calling_station_id>
                    </activeSession>
                </activeList>
                """;

        List<IseActiveSession> result = client.parseActiveList(xml);
        assertEquals(1, result.size());
        assertEquals("AA:BB:CC:DD:EE:03", result.get(0).mac());
        assertEquals(null, result.get(0).ip());
    }

    @Test
    void parseActiveList_missingMacAddress_isSkipped() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <activeList noOfActiveSession="1">
                    <activeSession>
                        <framed_ip_address>192.168.1.105</framed_ip_address>
                    </activeSession>
                </activeList>
                """;

        List<IseActiveSession> result = client.parseActiveList(xml);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseActiveList_malformedXml_throwsException() {
        String malformedXml = "<activeList><activeSession><calling_station_id>AA:BB:CC";
        assertThrows(Exception.class, () -> client.parseActiveList(malformedXml));
    }
}
