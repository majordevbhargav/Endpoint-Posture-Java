package com.endpointposture.diagnostic;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the agent's measurements into a 0-100 score with a transparent list of
 * deductions (the "root cause" model). Starts at 100 and subtracts points.
 *
 * <p>A section the agent did not report is simply not scored: absence of data is
 * not a penalty. ICMP to the internet target is weighted low because many
 * networks filter it; the TCP 443 probe is the stronger internet signal.
 * Bands use the same illustrative 85/70/50 thresholds as hardware health.</p>
 */
@Component
public class DiagnosticScorer {

    private static final int HEALTHY_THRESHOLD = 85;
    private static final int WARNING_THRESHOLD = 70;
    private static final int DEGRADED_THRESHOLD = 50;

    /** The score, its band, and why points were lost. */
    public record Result(int score, EndpointDiagnostic.Band band, List<Map<String, Object>> deductions) {}

    public Result score(Map<String, Object> gateway,
                        Map<String, Object> dns,
                        Map<String, Object> internet,
                        Map<String, Object> tcp443) {
        List<Map<String, Object>> deductions = new ArrayList<>();

        if (gateway != null) {
            Boolean reachable = bool(gateway.get("reachable"));
            if (Boolean.FALSE.equals(reachable)) {
                deduct(deductions, "GATEWAY", 40, "Default gateway did not answer ping");
            } else if (Boolean.TRUE.equals(reachable)) {
                Double avg = num(gateway.get("avgMs"));
                if (avg != null && avg > 100) {
                    deduct(deductions, "GATEWAY", 20, "Gateway latency very high (" + Math.round(avg) + " ms)");
                } else if (avg != null && avg > 30) {
                    deduct(deductions, "GATEWAY", 10, "Gateway latency high (" + Math.round(avg) + " ms)");
                }
                Double loss = num(gateway.get("lossPct"));
                if (loss != null && loss > 0) {
                    deduct(deductions, "GATEWAY", (int) Math.min(20, Math.round(loss)),
                            "Packet loss to gateway (" + Math.round(loss) + "%)");
                }
            }
        }

        if (dns != null) {
            Boolean resolved = bool(dns.get("resolved"));
            if (Boolean.FALSE.equals(resolved)) {
                deduct(deductions, "DNS", 25, "DNS lookup failed");
            } else if (Boolean.TRUE.equals(resolved)) {
                Double ms = num(dns.get("ms"));
                if (ms != null && ms > 200) {
                    deduct(deductions, "DNS", 10, "DNS lookup slow (" + Math.round(ms) + " ms)");
                }
            }
        }

        if (internet != null) {
            Boolean reachable = bool(internet.get("reachable"));
            if (Boolean.FALSE.equals(reachable)) {
                deduct(deductions, "INTERNET_PING", 10, "Internet target did not answer ping (ICMP may be filtered)");
            } else if (Boolean.TRUE.equals(reachable)) {
                Double avg = num(internet.get("avgMs"));
                if (avg != null && avg > 150) {
                    deduct(deductions, "INTERNET_PING", 10, "Internet latency high (" + Math.round(avg) + " ms)");
                }
                Double loss = num(internet.get("lossPct"));
                if (loss != null && loss > 0) {
                    deduct(deductions, "INTERNET_PING", (int) Math.min(10, Math.round(loss)),
                            "Packet loss to internet target (" + Math.round(loss) + "%)");
                }
            }
        }

        if (tcp443 != null) {
            Boolean connected = bool(tcp443.get("connected"));
            if (Boolean.FALSE.equals(connected)) {
                deduct(deductions, "TCP_443", 30, "TCP 443 connection failed");
            } else if (Boolean.TRUE.equals(connected)) {
                Double ms = num(tcp443.get("ms"));
                if (ms != null && ms > 500) {
                    deduct(deductions, "TCP_443", 10, "TCP 443 connect slow (" + Math.round(ms) + " ms)");
                }
            }
        }

        int lost = deductions.stream().mapToInt(d -> (Integer) d.get("points")).sum();
        int score = Math.max(0, 100 - lost);
        return new Result(score, bandFor(score), deductions);
    }

    static EndpointDiagnostic.Band bandFor(int score) {
        if (score >= HEALTHY_THRESHOLD) return EndpointDiagnostic.Band.HEALTHY;
        if (score >= WARNING_THRESHOLD) return EndpointDiagnostic.Band.WARNING;
        if (score >= DEGRADED_THRESHOLD) return EndpointDiagnostic.Band.DEGRADED;
        return EndpointDiagnostic.Band.CRITICAL;
    }

    private static void deduct(List<Map<String, Object>> out, String check, int points, String reason) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("check", check);
        d.put("points", points);
        d.put("reason", reason);
        out.add(d);
    }

    private static Boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return null;
        String s = v.toString().trim().toLowerCase();
        if (s.equals("true")) return Boolean.TRUE;
        if (s.equals("false")) return Boolean.FALSE;
        return null;
    }

    private static Double num(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}