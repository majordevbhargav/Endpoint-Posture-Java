package com.endpointposture.sim;

/**
 * Deterministic fake identities for the scale simulator. Device number {@code i}
 * always has the same MAC and IP, so the seed SQL, the fake ISE client and the fake
 * agent runner agree without sharing state. The IP maps back to the number.
 */
public final class SimIdentity {

    private SimIdentity() {}

    /** @return e.g. {@code 02:00:00:00:00:2A} for 42 */
    public static String mac(int i) {
        return String.format("02:00:%02X:%02X:%02X:%02X",
                (i >> 24) & 255, (i >> 16) & 255, (i >> 8) & 255, i & 255);
    }

    /** @return e.g. {@code 10.0.0.42} for 42 */
    public static String ip(int i) {
        return "10." + ((i >> 16) & 255) + "." + ((i >> 8) & 255) + "." + (i & 255);
    }

    /** Inverse of {@link #ip(int)}. @throws IllegalArgumentException if not a simulated address */
    public static int indexFromIp(String ip) {
        String[] p = ip == null ? new String[0] : ip.split("\\.");
        if (p.length != 4 || !"10".equals(p[0])) {
            throw new IllegalArgumentException("Not a simulated address: " + ip);
        }
        return (Integer.parseInt(p[1]) << 16) | (Integer.parseInt(p[2]) << 8) | Integer.parseInt(p[3]);
    }
}