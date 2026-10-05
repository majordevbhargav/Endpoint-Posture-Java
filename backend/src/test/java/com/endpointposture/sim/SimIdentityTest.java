package com.endpointposture.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimIdentityTest {

    @Test
    void macAndIpAreDeterministic() {
        assertEquals("02:00:00:00:00:2A", SimIdentity.mac(42));
        assertEquals("10.0.0.42", SimIdentity.ip(42));
        assertEquals("10.0.195.80", SimIdentity.ip(50000));
    }

    @Test
    void ipMapsBackToTheDeviceNumber() {
        for (int i : new int[]{1, 255, 256, 20000, 50000}) {
            assertEquals(i, SimIdentity.indexFromIp(SimIdentity.ip(i)));
        }
    }

    @Test
    void aNonSimulatedAddressIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SimIdentity.indexFromIp("192.168.1.5"));
        assertThrows(IllegalArgumentException.class, () -> SimIdentity.indexFromIp(null));
    }
}