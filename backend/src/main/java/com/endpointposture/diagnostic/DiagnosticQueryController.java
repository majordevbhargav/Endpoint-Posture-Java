package com.endpointposture.diagnostic;

import com.endpointposture.diagnostic.dto.DiagnosticResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Read side: an endpoint's diagnostic history. Requires a bearer token. */
@RestController
@RequestMapping("/api/v1/endpoints/{id}/diagnostics")
@Tag(name = "Diagnostics history", description = "Read an endpoint's diagnostic runs. Requires a bearer token.")
public class DiagnosticQueryController {

    private final DiagnosticService service;

    public DiagnosticQueryController(DiagnosticService service) {
        this.service = service;
    }

    @Operation(summary = "Diagnostic history for an endpoint, newest first")
    @GetMapping
    public List<DiagnosticResponse> history(@PathVariable UUID id) {
        return service.history(id);
    }

    @Operation(summary = "Latest diagnostic run for an endpoint",
            description = "Returns 204 No Content when the endpoint has never been diagnosed.")
    @GetMapping("/latest")
    public ResponseEntity<DiagnosticResponse> latest(@PathVariable UUID id) {
        return service.latest(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}