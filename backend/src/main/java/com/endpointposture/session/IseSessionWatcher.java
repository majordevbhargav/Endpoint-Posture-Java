package com.endpointposture.session;

import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.endpoint.EndpointRepository.ConnectedRow;
import com.endpointposture.session.IseSessionClient.SessionPoll;
import com.endpointposture.session.dto.IseActiveSession;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Polls ISE for active sessions and brings endpoint connection state in line with it,
 * as a set difference rather than device by device:
 *
 * <ol>
 *   <li>one light query returns the MAC and IP of every connected endpoint;</li>
 *   <li>devices in the poll but not connected are <b>appeared</b>: they are connected in
 *       bulk and get a posture recheck (subject to the reconnect gap);</li>
 *   <li>devices in both only get a changed IP or a refreshed last-seen time;</li>
 *   <li>devices connected but missing from the poll are <b>missing</b>: after
 *       {@code app.ise.disconnect-grace-polls} consecutive misses they are disconnected.</li>
 * </ol>
 *
 * <p>If the poll itself fails, state is left untouched (and no misses are counted). A
 * successful poll with zero sessions is a real answer. Nothing here calls ISE actions;
 * this class only observes.</p>
 */
@Component
public class IseSessionWatcher {

    private static final Logger log = LoggerFactory.getLogger(IseSessionWatcher.class);

    private final IseSessionClient client;
    private final EndpointRepository endpoints;
    private final SessionBatchWriter batch;
    private final IseLinkHealth linkHealth;
    private final int gracePolls;
    private final Timer watcherTickTimer;

    /** Consecutive missed polls per (normalized) MAC. */
    private final Map<String, Integer> misses = new ConcurrentHashMap<>();

    public IseSessionWatcher(IseSessionClient client, EndpointRepository endpoints,
                             SessionBatchWriter batch, IseLinkHealth linkHealth,
                             int gracePolls) {
        this(client, endpoints, batch, linkHealth, gracePolls, new SimpleMeterRegistry());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IseSessionWatcher(IseSessionClient client, EndpointRepository endpoints,
                             SessionBatchWriter batch, IseLinkHealth linkHealth,
                             @Value("${app.ise.disconnect-grace-polls:2}") int gracePolls,
                             MeterRegistry registry) {
        this.client = client;
        this.endpoints = endpoints;
        this.batch = batch;
        this.linkHealth = linkHealth;
        this.gracePolls = Math.max(1, gracePolls);
        this.watcherTickTimer = Timer.builder("ise_watcher_tick_seconds")
                .description("Duration of ISE session watcher tick")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${app.ise.session-poll-interval-ms:15000}")
    public void tick() {
        long started = System.nanoTime();
        try {
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

        // MAC -> IP (IP may be null). Duplicates in the poll collapse to one entry.
        Map<String, String> active = new LinkedHashMap<>();
        for (IseActiveSession s : poll.sessions()) {
            if (s.mac() == null || s.mac().isBlank()) continue;
            String mac = normalize(s.mac());
            String ip = blankToNull(s.ip());
            if (!active.containsKey(mac) || active.get(mac) == null) {
                active.put(mac, ip);
            }
        }

        Map<String, String> connected = new HashMap<>();
        for (ConnectedRow row : endpoints.findConnectedRows()) {
            connected.put(row.getMacAddress(), row.getIpAddress());
        }

        Map<String, String> appeared = new LinkedHashMap<>();
        Map<String, String> ipChanged = new LinkedHashMap<>();
        List<String> stillPresent = new ArrayList<>();

        for (Map.Entry<String, String> e : active.entrySet()) {
            String mac = e.getKey();
            String ip = e.getValue();
            if (!connected.containsKey(mac)) {
                appeared.put(mac, ip);
            } else {
                stillPresent.add(mac);
                if (ip != null && !ip.equals(connected.get(mac))) {
                    ipChanged.put(mac, ip);
                }
            }
        }

        // Grace period: only devices still flagged connected but absent are counted.
        Set<String> missing = new HashSet<>(connected.keySet());
        missing.removeAll(active.keySet());
        misses.keySet().retainAll(missing); // devices that came back reset to zero

        List<String> toDisconnect = new ArrayList<>();
        for (String mac : missing) {
            int count = misses.merge(mac, 1, Integer::sum);
            if (count >= gracePolls) {
                toDisconnect.add(mac);
                misses.remove(mac);
            }
        }

        int queued = 0;
        if (!appeared.isEmpty()) {
            batch.markConnected(appeared);
            queued = batch.enqueueReconnectChecks(new ArrayList<>(appeared.keySet()));
        }
        batch.updateIps(ipChanged);
        if (!stillPresent.isEmpty()) {
            batch.touchSeen(stillPresent);
        }
        int disconnected = toDisconnect.isEmpty() ? 0 : batch.markDisconnected(toDisconnect);

        long ms = (System.nanoTime() - started) / 1_000_000;
        String summary = "ISE tick: active={} +{} connected, -{} disconnected, {} ip changes, "
                + "{} rechecks queued, {} ms";
        if (!appeared.isEmpty() || disconnected > 0) {
            log.info(summary, active.size(), appeared.size(), disconnected, ipChanged.size(), queued, ms);
        } else {
            log.debug(summary, active.size(), appeared.size(), disconnected, ipChanged.size(), queued, ms);
        }
        } finally {
            watcherTickTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String normalize(String mac) {
        return mac.trim().replace('-', ':').toUpperCase(Locale.ROOT);
    }
}