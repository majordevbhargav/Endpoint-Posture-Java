package com.endpointposture.inventory;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.endpoint.FleetListLimitExceededException;
import com.endpointposture.policy.PolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Fleet-wide inventory read API used by the Installed Software and
 * Listening Ports pages. Read-only; never calls ISE.
 *
 * <p>Required and blocked application patterns come from the active
 * {@link PolicyService} policy, the same one handed to the posture agent,
 * so this page and the agent's verdict cannot drift apart.</p>
 *
 * <p>Both lists return one row per application or port for every endpoint, so they
 * grow with fleet size. Above {@code app.api.fleet-list-max-endpoints} they refuse
 * with a {@code 400} instead of loading everything into memory. Real server-side
 * paging is roadmap item S13.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Inventory", description = "Latest installed applications and listening ports per endpoint. Requires a bearer token.")
public class InventoryController {

    private final EndpointInventoryRepository inventory;
    private final EndpointRepository endpoints;
    private final PolicyService policyService;
    private final long fleetListMaxEndpoints;

    public InventoryController(EndpointInventoryRepository inventory, EndpointRepository endpoints,
                               PolicyService policyService,
                               @Value("${app.api.fleet-list-max-endpoints:2000}") long fleetListMaxEndpoints) {
        this.inventory = inventory;
        this.endpoints = endpoints;
        this.policyService = policyService;
        this.fleetListMaxEndpoints = fleetListMaxEndpoints;
    }

    /** Shape matches frontend/lib/inventory.ts AppRow. */
    public record AppRow(String name, String version, String publisher,
                         String hostname, String macAddress, String status, String summary) {}

    /** Shape matches frontend/lib/inventory.ts PortRow. */
    public record PortRow(int port, String process, Integer pid, Boolean reachable,
                          String hostname, String macAddress, String status) {}

    @Operation(summary = "Installed applications from each endpoint's latest posture run",
            description = "Refused with 400 when the fleet exceeds app.api.fleet-list-max-endpoints.")
    @GetMapping("/applications")
    public List<AppRow> applications() {
        checkFleetCap();
        PolicyService.PolicySnapshot policy = policyService.getActive();
        Map<UUID, Endpoint> byId = endpointsById();
        List<AppRow> rows = new ArrayList<>();

        for (EndpointInventory inv : inventory.findLatestPerEndpoint()) {
            Endpoint ep = byId.get(inv.getEndpointId());
            if (ep == null || inv.getInstalledApps() == null) continue;

            for (Map<String, Object> app : inv.getInstalledApps()) {
                String name = str(app.get("name"));
                if (name == null || name.isBlank()) continue;

                String status = null;
                String summary = null;
                if (matchesAny(name, policy.blockedApps())) {
                    status = "NON_COMPLIANT";
                    summary = "Blocked application installed (policy v" + policy.version() + ")";
                } else if (matchesAny(name, policy.requiredApps())) {
                    status = "COMPLIANT";
                    summary = "Required application present (policy v" + policy.version() + ")";
                }

                rows.add(new AppRow(name, str(app.get("version")), str(app.get("publisher")),
                        ep.getHostname(), ep.getMacAddress(), status, summary));
            }
        }
        return rows;
    }

    @Operation(summary = "Listening TCP ports (with probe reachability) from each endpoint's latest posture run",
            description = "Refused with 400 when the fleet exceeds app.api.fleet-list-max-endpoints.")
    @GetMapping("/ports")
    public List<PortRow> ports() {
        checkFleetCap();
        Map<UUID, Endpoint> byId = endpointsById();
        List<PortRow> rows = new ArrayList<>();

        for (EndpointInventory inv : inventory.findLatestPerEndpoint()) {
            Endpoint ep = byId.get(inv.getEndpointId());
            if (ep == null || inv.getListeningPorts() == null) continue;

            for (Map<String, Object> p : inv.getListeningPorts()) {
                Object portObj = p.get("port");
                if (!(portObj instanceof Number portNum)) continue;

                Object pidObj = p.get("pid");
                Integer pid = pidObj instanceof Number n ? n.intValue() : null;
                Boolean reachable = p.get("reachable") instanceof Boolean b ? b : null;
                String status = reachable == null ? null : (reachable ? "COMPLIANT" : "BLOCKED");

                rows.add(new PortRow(portNum.intValue(), str(p.get("process")), pid, reachable,
                        ep.getHostname(), ep.getMacAddress(), status));
            }
        }
        return rows;
    }

    private void checkFleetCap() {
        long count = endpoints.count();
        if (count > fleetListMaxEndpoints) {
            throw new FleetListLimitExceededException(count, fleetListMaxEndpoints,
                    "this inventory list is not paged yet, so it is limited to smaller fleets");
        }
    }

    private Map<UUID, Endpoint> endpointsById() {
        Map<UUID, Endpoint> map = new HashMap<>();
        for (Endpoint e : endpoints.findAll()) map.put(e.getId(), e);
        return map;
    }

    private static boolean matchesAny(String name, List<String> needles) {
        String lower = name.toLowerCase(Locale.ROOT);
        return needles.stream().anyMatch(n -> lower.contains(n.toLowerCase(Locale.ROOT)));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}