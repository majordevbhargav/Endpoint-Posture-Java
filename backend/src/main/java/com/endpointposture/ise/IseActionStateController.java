package com.endpointposture.ise;

import com.endpointposture.ise.dto.IseActionStateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes fleet-wide ISE enforcement state derived from the audit log.
 * Open to any authenticated user.
 */
@RestController
@RequestMapping("/api/v1/ise/actions")
@Tag(name = "ISE action state", description = "Fleet-wide ISE enforcement states derived from audit history.")
public class IseActionStateController {

    private final IseActionStateService service;

    public IseActionStateController(IseActionStateService service) {
        this.service = service;
    }

    @Operation(summary = "Latest RESTRICT or CLEAR_RESTRICTION action per endpoint")
    @GetMapping("/state")
    public List<IseActionStateResponse> getEnforcementStates() {
        return service.getLatestEnforcementStates();
    }
}
