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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Polls ISE for active sessions, updates endpoint connect/disconnect state,
 * and enqueues a posture recheck for anything that just reconnected.
 *
 * <p>If the poll itself fails, endpoint state is left untouched (and no misses
 * are counted). A successful poll with zero sessions is a real answer.</p>
 *
 * <p>An endpoint is only marked disconnected after it is missing from
 * {@code app.ise.disconnect-grace-polls} consecutive successful polls, so a
 * roaming device or a single odd poll does not flap its state.</p>
 */
@Component
public class IseSessionWatcher {

    private static final Logger log = LoggerFactory.getLogger(IseSessionWatcher.class);

    private final IseSessionClient client;
    private final EndpointRepository endpoints;
    private final EndpointService endpointService;
    private final JobService jobService;
    private final IseLinkHealth linkHealth;
    private final int gracePolls;

    /** Consecutive missed polls per (normalized) MAC. */
    private final Map<String, Integer> misses = new ConcurrentHashMap<>();

    public IseSessionWatcher(IseSessionClient client, EndpointRepository endpoints,
                             EndpointService endpointService, JobService jobService,
                             IseLinkHealth linkHealth,
                             @Value("${app.ise.disconnect-grace-polls:2}") int gracePolls) {
        this.client = client;
        this.endpoints = endpoints;
        this.endpointService = endpointService;
        this.jobService = jobService;
        this.linkHealth = linkHealth;
        this.gracePolls = Math.max(1, gracePolls);
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
            return; // we don't know who is connected: leave state and miss counters alone
        }

        if (!linkHealth.reachable()) {
            log.info("ISE reachable again - resuming session tracking");
        }
        linkHealth.success();

        List<IseActiveSession> active = poll.sessions();

        Set<String> activeMacs = active.stream()
                .map(s -> normalizeMacForCompare(s.mac()))
                .collect(Collectors.toSet());

        for (IseActiveSession session : active) {
            String mac = normalizeMacForCompare(session.mac());
            misses.remove(mac); // seen this poll: reset its miss counter

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

        List<Endpoint> connected = endpoints.findAllByConnectedTrue();

        // Drop counters for endpoints that are no longer flagged connected.
        Set<String> connectedMacs = connected.stream().map(Endpoint::getMacAddress).collect(Collectors.toSet());
        misses.keySet().retainAll(connectedMacs);

        for (Endpoint ep : connected) {
            if (activeMacs.contains(ep.getMacAddress())) continue;

            int count = misses.merge(ep.getMacAddress(), 1, Integer::sum);
            if (count >= gracePolls) {
                log.info("Endpoint {} missing from {} consecutive ISE polls - marking disconnected",
                        ep.getMacAddress(), count);
                endpointService.markDisconnected(ep.getMacAddress());
                misses.remove(ep.getMacAddress());
            } else {
                log.debug("Endpoint {} missing from ISE poll ({}/{})", ep.getMacAddress(), count, gracePolls);
            }
        }
    }

    private static boolean hasTarget(Endpoint ep) {
        return (ep.getIpAddress() != null && !ep.getIpAddress().isBlank())
                || (ep.getHostname() != null && !ep.getHostname().isBlank());
    }

    private String normalizeMacForCompare(String mac) {
        return mac.trim().replace('-', ':').toUpperCase(Locale.ROOT);
    }
}