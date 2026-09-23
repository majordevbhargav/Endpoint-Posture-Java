package com.endpointposture.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only view of every ISE action ever taken from this platform -
 * success or failure, always logged by
 * {@link com.endpointposture.ise.IseActionService}.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "ISE audit trail", description = "Every Share Posture / Restrict / Clear Restriction action ever taken. Requires a bearer token.")
public class AuditQueryController {

    private final IseActionAuditRepository repository;

    public AuditQueryController(IseActionAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * @param endpointId optional filter to one endpoint's history
     * @return matching audit rows, newest first
     */
    @Operation(summary = "List ISE actions, newest first, optionally filtered to one endpoint")
    @GetMapping("/ise-actions")
    public List<IseActionAudit> list(@RequestParam(required = false) UUID endpointId) {
        return endpointId != null
                ? repository.findAllByEndpointIdOrderByOccurredAtDesc(endpointId)
                : repository.findAllByOrderByOccurredAtDesc();
    }
}