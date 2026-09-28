package com.endpointposture.session;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.endpoint.EndpointService;
import com.endpointposture.job.JobService;
import com.endpointposture.job.JobType;
import com.endpointposture.session.IseSessionClient.SessionPoll;
import com.endpointposture.session.dto.IseActiveSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Polls ISE for active sessions, updates endpoint connect/disconnect state,
 * and enqueues a posture recheck for anything that just reconnected.
 *
 * <p>If the poll itself fails (ISE unreachable, bad credentials, malformed
 * response) endpoint state is left untouched: we do not know who is
 * connected, so we do not guess. {@link IseLinkHealth} records the outage
 * so the UI can show sessions as "last known". A successful poll with zero
 * sessions is a real answer, and marks everyone disconnected.</p>
 */
@Component
public class IseSessionWatcher {

    private static final Logger log = LoggerFactory.getLogger(IseSessionWatcher.class);

    private final IseSessionClient client;
    private final EndpointRepository endpoints;
    private final EndpointService endpointService;
    private final JobService jobService;
    private final IseLinkHealth linkHealth;

    public IseSessionWatcher(IseSessionClient client, EndpointRepository endpoints,
                             EndpointService endpointService, JobService jobService,
                             IseLinkHealth linkHealth) {
        this.client = client;
        this.endpoints = endpoints;
        this.endpointService = endpointService;
        this.jobService = jobService;
        this.linkHealth = linkHealth;
    }

    @Scheduled(fixedDelayString = "${app.ise.session-poll-interval-ms:15000}")
    public void tick() {
        SessionPoll poll = client.fetchActiveSessions();

        if (!poll.ok()) {
            boolean wasReachable = linkHealth.reachable();
            linkHealth.failure(poll.error());
            if (wasReachable && !linkHealth.reachable()) {
                log.warn("ISE marked unreachable - endpoint session state frozen at last known values");
            }
            return; // we don't know who is connected: leave state alone
        }

        if (!linkHealth.reachable()) {
            log.info("ISE reachable again - resuming session tracking");
        }
        linkHealth.success();

        List<IseActiveSession> active = poll.sessions(); // empty now genuinely means nobody is connected

        Set<String> activeMacs = active.stream()
                .map(s -> normalizeMacForCompare(s.mac()))
                .collect(Collectors.toSet());

        for (IseActiveSession session : active) {
            String mac = normalizeMacForCompare(session.mac());
            boolean wasConnected = endpoints.findByMacAddress(mac)
                    .map(Endpoint::isConnected)
                    .orElse(false);

            endpointService.markConnected(session.mac(), session.ip());

            if (!wasConnected) {
                Endpoint ep = endpoints.findByMacAddress(mac).orElseThrow();
                log.info("Endpoint {} connected - enqueuing posture recheck", ep.getMacAddress());
                if (hasTarget(ep)) {
                    jobService.enqueueIfDue(ep.getId(), JobType.POSTURE_CHECK);
                } else {
                    log.info("Endpoint {} has no IP or hostname yet - skipping posture job", ep.getMacAddress());
                }
            }
        }

        endpoints.findAllByConnectedTrue().stream()
                .filter(ep -> !activeMacs.contains(ep.getMacAddress()))
                .forEach(ep -> {
                    log.info("Endpoint {} dropped off ISE's active list - marking disconnected", ep.getMacAddress());
                    endpointService.markDisconnected(ep.getMacAddress());
                });
    }

    private static boolean hasTarget(Endpoint ep) {
        return (ep.getIpAddress() != null && !ep.getIpAddress().isBlank())
                || (ep.getHostname() != null && !ep.getHostname().isBlank());
    }

    private String normalizeMacForCompare(String mac) {
        return mac.trim().replace('-', ':').toUpperCase(Locale.ROOT);
    }
}