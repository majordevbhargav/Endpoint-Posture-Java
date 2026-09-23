package com.endpointposture.endpoint;

import com.endpointposture.endpoint.dto.EndpointResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Read-only REST API for endpoints: {@code /api/v1/endpoints}.
 *
 * <p>Endpoints are created by the collectors (posture ingestion, ISE
 * watcher), not through this controller.</p>
 */
@RestController
@RequestMapping("/api/v1/endpoints")
@Tag(name = "Endpoints", description = "Devices discovered through Cisco ISE or posture ingestion. Requires a bearer token.")
public class EndpointController {

    private final EndpointService service;

    public EndpointController(EndpointService service) {
        this.service = service;
    }

    /** @return every known endpoint */
    @Operation(
            summary = "List all known endpoints",
            description = "Returns every endpoint this platform has ever seen, connected "
                    + "or not. Connection state is tracked independently of posture status "
                    + "- see the 'connected' field."
    )
    @GetMapping
    public List<EndpointResponse> listAll() {
        return service.listAll();
    }

    /**
     * @param id the endpoint's internal UUID
     * @return the endpoint, or {@code 404} if the ID is unknown
     */
    @Operation(
            summary = "Get one endpoint by its internal ID",
            description = "Looks up by the platform's internal UUID, not the device's MAC "
                    + "address. MAC address is the real business key used by ingestion; "
                    + "the UUID exists purely as a stable external API reference."
    )
    @GetMapping("/{id}")
    public EndpointResponse getById(
            @Parameter(description = "Internal endpoint UUID, from the list response above")
            @PathVariable UUID id
    ) {
        return service.getById(id);
    }
}
