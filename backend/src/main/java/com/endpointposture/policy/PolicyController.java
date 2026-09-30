package com.endpointposture.policy;

import com.endpointposture.policy.PolicyService.PolicySnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Application policy API. Reading needs any valid token; changing it is
 * restricted to ADMIN in {@code SecurityConfig}. Never calls ISE.
 */
@RestController
@RequestMapping("/api/v1/policy/apps")
@Tag(name = "Application policy", description = "Required and blocked applications. PUT requires ADMIN.")
public class PolicyController {

    /** Body of {@code PUT /api/v1/policy/apps}. */
    public record UpdateRequest(@NotNull List<String> requiredApps, @NotNull List<String> blockedApps) {}

    private final PolicyService service;

    public PolicyController(PolicyService service) {
        this.service = service;
    }

    @Operation(summary = "The active application policy")
    @GetMapping
    public PolicySnapshot active() {
        return service.getActive();
    }

    @Operation(summary = "Every policy version, newest first")
    @GetMapping("/history")
    public List<PolicySnapshot> history() {
        return service.history();
    }

    @Operation(summary = "Replace the active policy with a new version",
            description = "Creates version N+1 with these lists and deactivates the current one. "
                    + "The next posture checks use it; nothing is redeployed.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public PolicySnapshot replace(@Valid @RequestBody UpdateRequest req, Authentication auth) {
        return service.replaceActive(req.requiredApps(), req.blockedApps(), auth.getName());
    }

    /** JSON body so the frontend's api.ts can show the message. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}