package com.endpointposture.hardware.scoring;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Scores battery wear as full-charge capacity over design capacity, from
 * {@code battery.battery_static} (as reported by
 * {@code hardware_health_agent.ps1}'s {@code BatteryStaticData} CIM
 * query).
 *
 * <p>Returns {@code null} — not zero — when there is no usable battery
 * data at all. That means "desktop, not applicable" rather than "battery
 * in critical condition." This distinction matters downstream in
 * {@link com.endpointposture.hardware.HardwareHealthService#recordReport}:
 * a {@code null} battery score is excluded from the overall-score
 * average entirely, rather than dragging every desktop's score down as
 * if it had a dead battery.</p>
 */
@Component
public class BatteryScorer implements ComponentScorer<List<Map<String, Object>>> {

    @Override
    public Integer score(List<Map<String, Object>> batteryStatic) {
        if (batteryStatic == null || batteryStatic.isEmpty()) {
            return null;
        }

        double designSum = 0;
        double fullSum = 0;
        boolean any = false;

        for (Map<String, Object> b : batteryStatic) {
            Double design = toDoubleOrNull(b.get("DesignedCapacity"));
            Double full = toDoubleOrNull(b.get("FullChargedCapacity"));

            // A zero or missing design capacity means this entry can't be
            // scored (would divide by zero, or the OEM never populated it)
            // — skip it rather than let it corrupt the aggregate.
            if (design != null && design > 0 && full != null) {
                designSum += design;
                fullSum += full;
                any = true;
            }
        }

        if (!any) {
            return null;
        }

        int wearPct = (int) Math.round((fullSum / designSum) * 100);
        return Math.max(0, Math.min(100, wearPct));
    }

    private Double toDoubleOrNull(Object v) {
        if (v == null) return null;
        try {
            return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}