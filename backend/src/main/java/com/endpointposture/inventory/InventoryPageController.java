package com.endpointposture.inventory;

import com.endpointposture.endpoint.dto.PageResponse;
import com.endpointposture.inventory.InventoryController.AppRow;
import com.endpointposture.inventory.InventoryController.PortRow;
import com.endpointposture.inventory.InventoryQueryService.AppSummary;
import com.endpointposture.inventory.InventoryQueryService.PortSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paged inventory reads (S13). The unpaged {@code /applications} and {@code /ports}
 * in {@link InventoryController} stay for small fleets and keep their fleet cap;
 * the dashboard uses these. Read-only; never calls ISE.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Inventory (paged)", description = "Server-side paged installed software and listening ports. Requires a bearer token.")
public class InventoryPageController {

    private final InventoryQueryService service;

    public InventoryPageController(InventoryQueryService service) {
        this.service = service;
    }

    @Operation(summary = "One page of installed applications from each endpoint's latest run",
            description = "page is zero-based; size 1..200. status: COMPLIANT (required present) or NON_COMPLIANT (blocked found). q matches name, publisher, hostname, MAC.")
    @GetMapping("/applications/page")
    public PageResponse<AppRow> applications(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "25") int size,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false) String status) {
        return service.applications(page, size, q, status);
    }

    @Operation(summary = "Installed-software KPI numbers for the whole fleet")
    @GetMapping("/applications/summary")
    public AppSummary applicationsSummary() {
        return service.applicationsSummary();
    }

    @Operation(summary = "One page of listening ports from each endpoint's latest run",
            description = "page is zero-based; size 1..200. reach: REACHABLE or BLOCKED. q matches port, process, hostname, MAC.")
    @GetMapping("/ports/page")
    public PageResponse<PortRow> ports(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "25") int size,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) String reach) {
        return service.ports(page, size, q, reach);
    }

    @Operation(summary = "Listening-ports KPI numbers for the whole fleet")
    @GetMapping("/ports/summary")
    public PortSummary portsSummary() {
        return service.portsSummary();
    }
}