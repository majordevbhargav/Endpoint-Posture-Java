package com.endpointposture.worker;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Authenticated delivery of a worker's fenced result envelope. */
@RestController
@RequestMapping("/api/v1/workers/jobs")
public class WorkerResultController {
    private final WorkerResultService results;

    public WorkerResultController(WorkerResultService results) {
        this.results = results;
    }

    @Operation(summary = "Submit the result for the authenticated worker's current job attempt")
    @PostMapping("/{id}/result")
    public ResponseEntity<WorkerResultResponse> submit(
            @AuthenticationPrincipal WorkerPrincipal worker,
            @PathVariable UUID id,
            @Valid @RequestBody WorkerResultRequest request) {
        return ResponseEntity.ok(results.submit(id, worker, request));
    }

    @ExceptionHandler(WorkerResultService.WorkerResultConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(WorkerResultService.WorkerResultConflictException e) {
        return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "Invalid worker result envelope or payload"));
    }
}
