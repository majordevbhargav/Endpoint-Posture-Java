package com.endpointposture.worker;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/workers")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name="Worker admin",description="Enrollment tokens and worker revocation. ADMIN only.")
public class WorkerAdminController {
    public record CreateTokenRequest(@NotBlank String workerName,@NotBlank String shard,Integer ttlMinutes) {}
    private final WorkerRegistryService registry;
    private final WorkerAuditService audit;
    public WorkerAdminController(WorkerRegistryService registry,WorkerAuditService audit) {
        this.registry=registry; this.audit=audit;
    }
    @Operation(summary="Create a single-use enrollment token. The token is shown once.")
    @PostMapping("/enrollment-tokens")
    public ResponseEntity<WorkerRegistryService.EnrollmentToken> createToken(
            @Valid @RequestBody CreateTokenRequest req,Authentication auth,HttpServletRequest http) {
        var t=registry.createEnrollmentToken(req.workerName(),req.shard(),req.ttlMinutes(),auth.getName());
        audit.record("TOKEN_CREATED",null,t.workerName(),"shard="+t.shard()+" by "+auth.getName(),http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(t);
    }
    @GetMapping
    public List<WorkerRegistryService.WorkerView> list(){return registry.list();}
    @Operation(summary="Revoke a worker. Its key stops working immediately.")
    @PostMapping("/{id}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable UUID id,Authentication auth,HttpServletRequest http) {
        String name=registry.revoke(id,auth.getName());
        audit.record("WORKER_REVOKED",id,name,"by "+auth.getName(),http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String,String>> conflict(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(WorkerRegistryService.WorkerNotFoundException.class)
    public ResponseEntity<Map<String,String>> notFound(WorkerRegistryService.WorkerNotFoundException e){return ResponseEntity.status(404).body(Map.of("message",e.getMessage()));}
}
