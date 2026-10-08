package com.endpointposture.endpoint;

import com.endpointposture.endpoint.dto.EndpointListItem;
import com.endpointposture.endpoint.dto.EndpointResponse;
import com.endpointposture.endpoint.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only REST API for endpoints: {@code /api/v1/endpoints}. Endpoints are created by collectors, not here. */
@RestController
@RequestMapping("/api/v1/endpoints")
@Tag(name = "Endpoints", description = "Devices discovered through Cisco ISE or posture ingestion. Requires a bearer token.")
public class EndpointController {

    private final EndpointService service;
    private final EndpointQueryService queryService;
    private final EndpointRepository endpointRepository;
    private final long fleetListMaxEndpoints;

    public EndpointController(EndpointService service, EndpointQueryService queryService,
                              EndpointRepository endpointRepository,
                              @org.springframework.beans.factory.annotation.Value("${app.api.fleet-list-max-endpoints:2000}") long fleetListMaxEndpoints) {
        this.service = service;
        this.queryService = queryService;
        this.endpointRepository = endpointRepository;
        this.fleetListMaxEndpoints = fleetListMaxEndpoints;
    }

    @Operation(summary = "List all known endpoints",
            description = "Returns every endpoint. Fine for small fleets; large screens should use /page.")
    @GetMapping
    public List<EndpointResponse> listAll() {
        long count = endpointRepository.count();
        if (count > fleetListMaxEndpoints) {
            throw new FleetListLimitExceededException(count, fleetListMaxEndpoints);
        }
        return service.listAll();
    }

    @Operation(summary = "One page of endpoints with their latest posture status",
            description = "page is zero-based; size 1..200. status is a comma list of COMPLIANT, NON_COMPLIANT, ERROR, UNASSESSED. "
                    + "q matches hostname, MAC, IP or OS.")
    @GetMapping("/page")
    public PageResponse<EndpointListItem> page(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "25") int size,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) Boolean connected,
                                               @RequestParam(required = false) String status) {
        return queryService.page(page, size, q, connected, status);
    }

    @Operation(summary = "Display names (hostname, else MAC) for up to 100 endpoint ids")
    @GetMapping("/names")
    public Map<String, String> names(@RequestParam List<UUID> ids) {
        return queryService.names(ids);
    }
    @Operation(summary = "Hostname, MAC and IP for up to 100 endpoint ids")
    @GetMapping("/briefs")
    public Map<String, com.endpointposture.endpoint.dto.EndpointBrief> briefs(@RequestParam List<UUID> ids) {
        return queryService.briefs(ids);
    }

    @Operation(summary = "Get one endpoint by its internal ID")
    @GetMapping("/{id}")
    public EndpointResponse getById(@Parameter(description = "Internal endpoint UUID") @PathVariable UUID id) {
        return service.getById(id);
    }
}