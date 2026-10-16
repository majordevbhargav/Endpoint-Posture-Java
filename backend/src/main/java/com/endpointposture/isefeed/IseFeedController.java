package com.endpointposture.isefeed;

import com.endpointposture.isefeed.IseFeedService.Feed;
import com.endpointposture.isefeed.SharingRuleService.Rule;
import com.endpointposture.isefeed.SharingRuleService.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ISE feed and its standing sharing rule (X1, DECISIONS D3).
 *
 * <p>The feed route accepts HTTP Basic (the scoped ISE credential, see
 * {@code IseFeedAuthFilter}) or an ADMIN JWT for preview. Changing the rule is
 * ADMIN only. Nothing here restricts or clears an endpoint.</p>
 */
@RestController
@RequestMapping("/api/v1/integrations/ise")
@Tag(name = "ISE feed", description = "Flat JSON feed for ISE pxGrid Direct and the admin-approved sharing rule behind it.")
public class IseFeedController {

    public record UpdateRuleRequest(@NotBlank String scope) {}

    private final IseFeedService feedService;
    private final SharingRuleService ruleService;

    public IseFeedController(IseFeedService feedService, SharingRuleService ruleService) {
        this.feedService = feedService;
        this.ruleService = ruleService;
    }

    @Operation(summary = "Posture feed for ISE",
            description = "Only endpoints covered by the active sharing rule. Basic auth (ISE feed credential) or ADMIN JWT. "
                    + "page is zero-based; size 1..50000. Every fetch is audited (counts only).")
    @GetMapping("/endpoints")
    public Feed feed(@RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "5000") int size,
                     Authentication auth) {
        return feedService.fetch(page, size, auth.getName());
    }

    @Operation(summary = "The active sharing rule")
    @GetMapping("/sharing-rule")
    public Rule activeRule() {
        return ruleService.getActive();
    }

    @Operation(summary = "Every sharing-rule version, newest first")
    @GetMapping("/sharing-rule/history")
    public List<Rule> history() {
        return ruleService.history();
    }

    @Operation(summary = "Approve a new sharing rule (ADMIN)",
            description = "Scope NONE, CONNECTED or CONNECTED_NON_COMPLIANT. Creates the next version, attributed to the caller.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/sharing-rule")
    public Rule replace(@Valid @RequestBody UpdateRuleRequest req, Authentication auth) {
        Scope scope;
        try {
            scope = Scope.valueOf(req.scope().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("scope must be one of NONE, CONNECTED, CONNECTED_NON_COMPLIANT");
        }
        return ruleService.replaceActive(scope, auth.getName());
    }

    /** JSON body so the frontend's api.ts shows the reason. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}