package com.endpointposture.hardware;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointService;
import com.endpointposture.hardware.dto.HardwareHealthResponse;
import com.endpointposture.hardware.dto.HardwareReportRequest;
import com.endpointposture.hardware.scoring.BatteryScorer;
import com.endpointposture.hardware.scoring.CpuScorer;
import com.endpointposture.hardware.scoring.MemoryScorer;
import com.endpointposture.hardware.scoring.StorageScorer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class HardwareIngestService {

    private final EndpointService endpointService;
    private final HardwareHealthService healthService;
    private final CpuScorer cpuScorer;
    private final MemoryScorer memoryScorer;
    private final StorageScorer storageScorer;
    private final BatteryScorer batteryScorer;

    public HardwareIngestService(EndpointService endpointService,
                                  HardwareHealthService healthService,
                                  CpuScorer cpuScorer,
                                  MemoryScorer memoryScorer,
                                  StorageScorer storageScorer,
                                  BatteryScorer batteryScorer) {
        this.endpointService = endpointService;
        this.healthService = healthService;
        this.cpuScorer = cpuScorer;
        this.memoryScorer = memoryScorer;
        this.storageScorer = storageScorer;
        this.batteryScorer = batteryScorer;
    }

    @Transactional
    public HardwareHealthResponse ingest(HardwareReportRequest req) {
        if (req.endpoint() == null || isBlank(req.endpoint().mac())) {
            throw new IllegalArgumentException("endpoint.mac is required");
        }

        Endpoint endpoint = endpointService.upsertByMac(
                req.endpoint().mac(), req.endpoint().ip(), req.endpoint().hostname(), null, null);

        endpointService.updateHardware(endpoint.getId(),
                req.endpoint().manufacturer(), req.endpoint().model(), req.endpoint().serialNumber());

        Map<String, Object> cpuSection = sub(req.cpuMemory(), "cpu");
        Map<String, Object> memorySection = sub(req.cpuMemory(), "memory");

        // PowerShell's ConvertTo-Json collapses a single-element array into
        // a bare JSON object instead of a one-item array (a well-known
        // quirk). A machine with exactly one disk or one battery therefore
        // sends {..} rather than [{..}], which Jackson deserializes as a
        // LinkedHashMap, not a List — asListOfMaps() below normalizes
        // both shapes so this never throws a ClassCastException again.
        List<Map<String, Object>> disks = asListOfMaps(
                req.storage() == null ? null : req.storage().get("physical_disks"));
        List<Map<String, Object>> batteryStatic = asListOfMaps(
                req.battery() == null ? null : req.battery().get("battery_static"));

        Integer cpuScore = cpuScorer.score(cpuSection);
        Integer memoryScore = memoryScorer.score(memorySection);
        Integer storageScore = storageScorer.score(disks);
        Integer batteryScore = batteryScorer.score(batteryStatic);

        if (cpuScore == null || memoryScore == null || storageScore == null) {
            throw new IllegalArgumentException(
                    "Report is missing required CPU, memory, or storage data — cannot score");
        }

        Integer eventCount = req.hardwareEvents() == null
                ? null : asInteger(req.hardwareEvents().get("event_count"));
        String warrantyStatus = req.warranty() == null
                ? null : asString(req.warranty().get("status"));
        Integer warrantyDaysRemaining = req.warranty() == null
                ? null : asInteger(req.warranty().get("days_remaining"));

        // proactive_recommendations has the exact same single-element quirk
        // as disks/battery — normalize it the same way.
        List<Map<String, Object>> recommendationMaps = asListOfMaps(req.proactiveRecommendations());
        List<HardwareHealthService.RecommendationInput> recs = new ArrayList<>();
        for (Map<String, Object> r : recommendationMaps) {
            recs.add(new HardwareHealthService.RecommendationInput(
                    asString(r.get("priority")), asString(r.get("area")), asString(r.get("action"))));
        }

        return healthService.recordReport(
                endpoint.getId(),
                parseUuidOrNull(req.jobId()),
                req.endpoint().manufacturer(),
                req.endpoint().model(),
                req.endpoint().serialNumber(),
                req.endpoint().biosVersion(),
                cpuScore, memoryScore, storageScore, batteryScore,
                eventCount, warrantyStatus, warrantyDaysRemaining,
                toRawReportMap(req),
                Instant.now(),
                recs
        );
    }

    /**
     * Normalizes a value that should be a list of maps but, thanks to
     * PowerShell's ConvertTo-Json single-element quirk, may have arrived
     * as a bare map instead. Returns an empty list for null/anything else
     * unrecognized, never throws.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map) out.add((Map<String, Object>) item);
            }
            return out;
        }
        if (v instanceof Map) {
            return List.of((Map<String, Object>) v);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sub(Map<String, Object> parent, String key) {
        if (parent == null) return null;
        Object v = parent.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private Integer asInteger(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private UUID parseUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private Map<String, Object> toRawReportMap(HardwareReportRequest req) {
        return Map.of(
                "jobId", req.jobId() == null ? "" : req.jobId(),
                "endpoint", req.endpoint() == null ? Map.of() : Map.of(
                        "manufacturer", nullToEmpty(req.endpoint().manufacturer()),
                        "model", nullToEmpty(req.endpoint().model()),
                        "serialNumber", nullToEmpty(req.endpoint().serialNumber()),
                        "biosVersion", nullToEmpty(req.endpoint().biosVersion()),
                        "mac", nullToEmpty(req.endpoint().mac()),
                        "hostname", nullToEmpty(req.endpoint().hostname()),
                        "ip", nullToEmpty(req.endpoint().ip())
                ),
                "cpuMemory", req.cpuMemory() == null ? Map.of() : req.cpuMemory(),
                "storage", req.storage() == null ? Map.of() : req.storage(),
                "battery", req.battery() == null ? Map.of() : req.battery(),
                "hardwareEvents", req.hardwareEvents() == null ? Map.of() : req.hardwareEvents(),
                "warranty", req.warranty() == null ? Map.of() : req.warranty(),
                "proactiveRecommendations", req.proactiveRecommendations() == null ? List.of() : req.proactiveRecommendations()
        );
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}