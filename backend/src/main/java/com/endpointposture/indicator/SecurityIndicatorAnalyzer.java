package com.endpointposture.indicator;

import com.endpointposture.indicator.EndpointSecurityIndicator.RiskLevel;
import com.endpointposture.indicator.dto.SecurityReportRequest.Connection;
import com.endpointposture.indicator.dto.SecurityReportRequest.Sample;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Heuristics over repeated snapshots of established TCP connections. Every
 * finding is a POSSIBLE indicator for a human to review; nothing here is
 * allowed to cause an enforcement action. Thresholds are illustrative.
 */
@Component
public class SecurityIndicatorAnalyzer {

    static final Set<Integer> ADMIN_PORTS = Set.of(22, 135, 445, 3389, 5985, 5986);
    static final Set<Integer> COMMON_PORTS = Set.of(53, 80, 123, 143, 443, 465, 587, 853, 993, 995, 8080, 8443);
    static final Set<Integer> SUSPECT_PORTS = Set.of(1337, 4444, 6667, 9001, 31337);
    static final int FANOUT_MEDIUM = 5;
    static final int FANOUT_HIGH = 10;
    static final int MIN_SAMPLES_FOR_BEACON = 6;
    static final int MIN_BEACON_STARTS = 4;
    static final double MAX_INTERVAL_CV = 0.25;
    static final int UNCOMMON_PORT_THRESHOLD = 5;

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    public record Result(RiskLevel risk, List<Map<String, Object>> findings, Map<String, Object> summary) {}

    public Result analyze(List<Sample> samples, int fallbackIntervalSec) {
        List<Map<String, Object>> findings = new ArrayList<>();
        int n = samples.size();

        Set<String> internal = new TreeSet<>();
        Set<String> external = new TreeSet<>();
        Set<String> adminTargets = new TreeSet<>();
        Map<Integer, Set<String>> adminByPort = new TreeMap<>();
        Map<Integer, Set<String>> externalPortProcs = new TreeMap<>();
        Map<String, boolean[]> presence = new LinkedHashMap<>();
        Map<String, Set<String>> procsByDest = new HashMap<>();

        for (int i = 0; i < n; i++) {
            for (Connection c : conns(samples.get(i))) {
                String addr = c.remoteAddress();
                Integer rp = c.remotePort();
                if (!isIpv4(addr) || rp == null || isIgnorable(addr)) continue;
                String proc = c.process() == null ? "unknown" : c.process();

                if (isPrivate(addr)) {
                    internal.add(addr);
                    boolean outboundAdmin = ADMIN_PORTS.contains(rp)
                            && (c.localPort() == null || !ADMIN_PORTS.contains(c.localPort()));
                    if (outboundAdmin) {
                        adminTargets.add(addr);
                        adminByPort.computeIfAbsent(rp, k -> new TreeSet<>()).add(addr);
                    }
                } else {
                    external.add(addr);
                    externalPortProcs.computeIfAbsent(rp, k -> new TreeSet<>()).add(proc);
                    String key = addr + ":" + rp;
                    presence.computeIfAbsent(key, k -> new boolean[n])[i] = true;
                    procsByDest.computeIfAbsent(key, k -> new TreeSet<>()).add(proc);
                }
            }
        }

        // 1. Lateral movement: fan-out to many internal hosts on admin ports.
        if (adminTargets.size() >= FANOUT_MEDIUM) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("targetCount", adminTargets.size());
            ev.put("targets", adminTargets.stream().limit(20).toList());
            Map<String, Integer> perPort = new LinkedHashMap<>();
            adminByPort.forEach((p, s) -> perPort.put(String.valueOf(p), s.size()));
            ev.put("hostsPerPort", perPort);
            findings.add(finding("LATERAL_MOVEMENT",
                    adminTargets.size() >= FANOUT_HIGH ? RiskLevel.HIGH : RiskLevel.MEDIUM,
                    "Possible lateral movement",
                    "Outbound connections to " + adminTargets.size()
                            + " internal hosts on remote-administration ports (SMB/RPC/RDP/WinRM/SSH) within one sampling window.",
                    ev));
        }

        // 2. Beaconing: a destination that keeps re-appearing at regular intervals.
        boolean beaconAnalyzed = n >= MIN_SAMPLES_FOR_BEACON;
        if (beaconAnalyzed) {
            List<Map<String, Object>> beacons = new ArrayList<>();
            for (Map.Entry<String, boolean[]> e : presence.entrySet()) {
                boolean[] p = e.getValue();
                List<Double> starts = new ArrayList<>();
                for (int i = 1; i < n; i++) { // index 0 is not a real "new connection"
                    if (p[i] && !p[i - 1]) starts.add(offset(samples, i, fallbackIntervalSec));
                }
                if (starts.size() < MIN_BEACON_STARTS) continue;
                double[] gaps = new double[starts.size() - 1];
                for (int i = 1; i < starts.size(); i++) gaps[i - 1] = starts.get(i) - starts.get(i - 1);
                double mean = Arrays.stream(gaps).average().orElse(0);
                if (mean <= 0) continue;
                double var = Arrays.stream(gaps).map(g -> (g - mean) * (g - mean)).average().orElse(0);
                double cv = Math.sqrt(var) / mean;
                if (cv <= MAX_INTERVAL_CV) {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("destination", e.getKey());
                    ev.put("reconnects", starts.size());
                    ev.put("meanIntervalSec", Math.round(mean * 10) / 10.0);
                    ev.put("processes", procsByDest.get(e.getKey()));
                    beacons.add(ev);
                }
            }
            beacons.sort((a, b) -> Integer.compare((Integer) b.get("reconnects"), (Integer) a.get("reconnects")));
            for (Map<String, Object> ev : beacons.stream().limit(5).toList()) {
                findings.add(finding("BEACONING",
                        (Integer) ev.get("reconnects") >= 6 ? RiskLevel.MEDIUM : RiskLevel.LOW,
                        "Possible beaconing",
                        "Connection to " + ev.get("destination") + " re-opened at a regular interval (about "
                                + ev.get("meanIntervalSec") + " s).",
                        ev));
            }
        }

        // 3. Unusual external ports.
        List<Integer> suspect = externalPortProcs.keySet().stream().filter(SUSPECT_PORTS::contains).toList();
        if (!suspect.isEmpty()) {
            Map<String, Object> ev = new LinkedHashMap<>();
            suspect.forEach(p -> ev.put(String.valueOf(p), externalPortProcs.get(p)));
            findings.add(finding("SUSPECT_PORT", RiskLevel.MEDIUM, "Connection to a port often used by remote-access tools",
                    "External connection on port(s) " + suspect + ". These are also used legitimately, so review the process.", ev));
        }
        List<Integer> uncommon = externalPortProcs.keySet().stream().filter(p -> !COMMON_PORTS.contains(p)).toList();
        if (uncommon.size() >= UNCOMMON_PORT_THRESHOLD) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("portCount", uncommon.size());
            ev.put("ports", uncommon.stream().limit(20).toList());
            findings.add(finding("UNUSUAL_EXTERNAL_PORTS", RiskLevel.LOW, "Many uncommon external ports",
                    uncommon.size() + " distinct uncommon destination ports in one window.", ev));
        }

        RiskLevel risk = RiskLevel.NONE;
        for (Map<String, Object> f : findings) {
            RiskLevel r = RiskLevel.valueOf((String) f.get("severity"));
            if (r.ordinal() > risk.ordinal()) risk = r;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sampleCount", n);
        summary.put("internalDestinations", internal.size());
        summary.put("externalDestinations", external.size());
        summary.put("adminPortTargets", adminTargets.size());
        summary.put("beaconingAnalyzed", beaconAnalyzed);
        return new Result(risk, findings, summary);
    }

    private static List<Connection> conns(Sample s) {
        return s == null || s.connections() == null ? List.of()
                : s.connections().stream().filter(Objects::nonNull).toList();
    }

    private static double offset(List<Sample> samples, int i, int fallback) {
        Double o = samples.get(i).offsetSec();
        return o != null ? o : (double) i * fallback;
    }

    private static Map<String, Object> finding(String type, RiskLevel sev, String title, String detail, Map<String, Object> ev) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", type);
        f.put("severity", sev.name());
        f.put("title", title);
        f.put("detail", detail);
        f.put("evidence", ev);
        return f;
    }

    static boolean isIpv4(String s) { return s != null && IPV4.matcher(s).matches(); }

    private static int[] octets(String s) {
        String[] p = s.split("\\.");
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) o[i] = Integer.parseInt(p[i]);
        return o;
    }

    /** Loopback, unspecified, link-local, multicast and above: never analysed. */
    static boolean isIgnorable(String ip) {
        int[] o = octets(ip);
        return o[0] == 0 || o[0] == 127 || (o[0] == 169 && o[1] == 254) || o[0] >= 224;
    }

    static boolean isPrivate(String ip) {
        int[] o = octets(ip);
        return o[0] == 10 || (o[0] == 172 && o[1] >= 16 && o[1] <= 31) || (o[0] == 192 && o[1] == 168);
    }
}