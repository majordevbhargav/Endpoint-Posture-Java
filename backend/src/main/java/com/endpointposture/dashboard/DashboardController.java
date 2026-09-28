package com.endpointposture.dashboard;

import com.endpointposture.dashboard.DashboardDtos.CategoryRate;
import com.endpointposture.dashboard.DashboardDtos.Summary;
import com.endpointposture.dashboard.DashboardDtos.TrendPoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Read-only fleet numbers for the overview page. Requires a bearer token. */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Fleet summary, compliance trend and per-check pass rates.")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @Operation(summary = "Fleet summary: connected, posture counts, unassessed and stale")
    @GetMapping("/summary")
    public Summary summary() {
        return service.summary();
    }

    @Operation(summary = "Daily compliant percentage over the last N days (1 to 90)")
    @GetMapping("/trend")
    public List<TrendPoint> trend(@RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be between 1 and 90");
        }
        return service.trend(days);
    }

    @Operation(summary = "Pass rate per check type, from each endpoint's latest assessment")
    @GetMapping("/categories")
    public List<CategoryRate> categories() {
        return service.categories();
    }
}