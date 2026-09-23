// src/main/java/com/endpointposture/session/IseSessionWatcher.java
package com.endpointposture.session;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.endpoint.EndpointService;
import com.endpointposture.job.JobService;
import com.endpointposture.job.JobType;
import com.endpointposture.session.dto.IseActiveSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Polls ISE for active sessions, updates endpoint connect/disconnect state,
 * and enqueues a posture recheck for anything that just reconnected.
 *
 * <p>Never throws out of {@link #tick()} — {@link IseSessionClient} already
 * degrades to an empty list on any failure, so a bad poll simply does
 * nothing this cycle rather than killing the {@code @Scheduled} thread for
 * every future tick.</p>
 */
@Component
public class IseSessionWatcher {

    private static final Logger log = LoggerFactory.getLogger(IseSessionWatcher.class);

    private final IseSessionClient client;
    private final EndpointRepository endpoints;
    private final EndpointService endpointService;
    private final JobService jobService;

    public IseSessionWatcher(IseSessionClient client, EndpointRepository endpoints,
                              EndpointService endpointService, JobService jobService) {
        this.client = client;
        this.endpoints = endpoints;
        this.endpointService = endpointService;
        this.jobService = jobService;
    }

    @Scheduled(fixedDelayString = "${app.ise.session-poll-interval-ms:15000}")
    public void tick() {
        List<IseActiveSession> active = client.fetchActiveSessions();
        if (active.isEmpty()) {
            return; // nothing reported this tick — either no sessions, or ISE unreachable (already logged)
        }

        Set<String> activeMacs = active.stream()
                .map(s -> normalizeMacForCompare(s.mac()))
                .collect(Collectors.toSet());

        for (IseActiveSession session : active) {
            boolean wasConnected = endpoints.findByMacAddress(normalizeMacForCompare(session.mac()))
                    .map(Endpoint::isConnected)
                    .orElse(false);

            endpointService.markConnected(session.mac(), session.ip());

            if (!wasConnected) {
                Endpoint ep = endpoints.findByMacAddress(normalizeMacForCompare(session.mac())).orElseThrow();
                log.info("Endpoint {} reconnected — enqueuing posture recheck", ep.getMacAddress());
                jobService.enqueueIfDue(ep.getId(), JobType.POSTURE_CHECK);
            }
        }

        endpoints.findAllByConnectedTrue().stream()
                .filter(ep -> !activeMacs.contains(ep.getMacAddress()))
                .forEach(ep -> {
                    log.info("Endpoint {} dropped off ISE's active list — marking disconnected", ep.getMacAddress());
                    endpointService.markDisconnected(ep.getMacAddress());
                });
    }

    // findByMacAddress expects the already-normalized (uppercase, colon-separated)
    // form EndpointService.normalizeMac produces; ISE's own MAC formatting can vary,
    // so this mirrors that same normalization before every lookup.
    private String normalizeMacForCompare(String mac) {
        return mac.trim().replace('-', ':').toUpperCase(java.util.Locale.ROOT);
    }
}