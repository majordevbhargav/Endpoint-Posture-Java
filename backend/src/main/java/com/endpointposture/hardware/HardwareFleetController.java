package com.endpointposture.hardware;

import com.endpointposture.endpoint.dto.PageResponse;
import com.endpointposture.hardware.dto.HardwareHealthResponse;
import com.endpointposture.hardware.dto.HardwareListItem;
import com.endpointposture.hardware.dto.HardwareSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Fleet-wide hardware reads. Large screens should use /page and /summary, not /latest. */
@RestController
@RequestMapping("/api/v1/hardware-health")
@Tag(name = "Hardware health fleet", description = "Fleet hardware reads. Requires a bearer token.")
public class HardwareFleetController {

    private final HardwareHealthService service;
    private final HardwareQueryService queryService;

    public HardwareFleetController(HardwareHealthService service, HardwareQueryService queryService) {
        this.service = service;
        this.queryService = queryService;
    }

    @Operation(summary = "Latest hardware report for every endpoint (unbounded; small fleets only)")
    @GetMapping("/latest")
    public List<HardwareHealthResponse> latestForAll() {
        return service.getLatestForAllEndpoints();
    }

    @Operation(summary = "One page of endpoints with their hardware health, worst first",
            description = "page is zero-based; size 1..200. band: HEALTHY, WARNING, DEGRADED, CRITICAL, FAILED, LAST_FAILED, NO_REPORT.")
    @GetMapping("/page")
    public PageResponse<HardwareListItem> page(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "25") int size,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) String band) {
        return queryService.page(page, size, q, band);
    }

    @Operation(summary = "Fleet hardware summary numbers")
    @GetMapping("/summary")
    public HardwareSummary summary() {
        return queryService.summary();
    }
}