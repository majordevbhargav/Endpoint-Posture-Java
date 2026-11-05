package com.endpointposture.worker;

import com.endpointposture.security.LoginRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workers")
@Tag(name="Workers",description="Remote worker enrollment and job operations.")
public class WorkerController {
    public record EnrollRequest(@NotBlank String token) {}
    public record CompleteRequest(@Min(1) int attempt) {}
    public record FailRequest(@Min(1) int attempt,String reason) {}
    private final WorkerRegistryService registry;
    private final WorkerJobService jobService;
    private final WorkerAuditService audit;
    private final LoginRateLimiter enrollLimiter=new LoginRateLimiter(true,10,10,60);

    public WorkerController(WorkerRegistryService registry,WorkerJobService jobService,WorkerAuditService audit) {
        this.registry=registry; this.jobService=jobService; this.audit=audit;
    }
    @Operation(summary="Redeem a single-use enrollment token. The API key is returned once.")
    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(@Valid @RequestBody EnrollRequest req,HttpServletRequest http) {
        String ip=http.getRemoteAddr();
        if(!enrollLimiter.tryAcquire(ip)) return ResponseEntity.status(429).body(Map.of("message","Too many requests"));
        try {
            var e=registry.enroll(req.token());
            audit.record("ENROLLED",e.workerId(),e.name(),"shard="+e.shard(),ip);
            return ResponseEntity.ok(e);
        } catch(WorkerRegistryService.EnrollmentRejectedException ex) {
            audit.record("ENROLL_REJECTED",null,null,"token rejected",ip);
            return ResponseEntity.status(401).body(Map.of("message",ex.getMessage()));
        }
    }
    @Operation(summary="Claim the next eligible job in this worker's shard.")
    @PostMapping("/jobs/claim")
    public ResponseEntity<WorkerJobService.Claimed> claim(@AuthenticationPrincipal WorkerPrincipal worker) {
        return jobService.claim(worker).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.noContent().build());
    }
    @Operation(summary="Complete a claimed job. Returns 409 for a stale or unowned attempt.")
    @PostMapping("/jobs/{id}/complete")
    public ResponseEntity<?> complete(@AuthenticationPrincipal WorkerPrincipal worker,@PathVariable UUID id,
            @Valid @RequestBody CompleteRequest req) {
        return jobService.complete(worker,id,req.attempt())?ResponseEntity.ok(Map.of("accepted",true)):conflict();
    }
    @Operation(summary="Fail a claimed job. Returns 409 for a stale or unowned attempt.")
    @PostMapping("/jobs/{id}/fail")
    public ResponseEntity<?> fail(@AuthenticationPrincipal WorkerPrincipal worker,@PathVariable UUID id,
            @Valid @RequestBody FailRequest req) {
        return jobService.fail(worker,id,req.attempt(),req.reason())?ResponseEntity.ok(Map.of("accepted",true)):conflict();
    }
    private static ResponseEntity<?> conflict() {
        return ResponseEntity.status(409).body(Map.of("message","Attempt is stale, expired or not owned by this worker"));
    }
}
