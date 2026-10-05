package com.endpointposture.sim;

import com.endpointposture.ise.config.IseProperties;
import com.endpointposture.session.IseSessionClient;
import com.endpointposture.session.dto.IseActiveSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for ISE in the {@code sim} profile. Returns a growing set of fake
 * sessions: it ramps from 0 to {@code app.sim.sessions} over {@code app.sim.ramp-ticks}
 * polls, like a morning log-in wave.
 *
 * <p>It also records how long the watcher took per tick. The gap between two polls is
 * the previous tick's work plus the configured delay, so
 * {@code gap - delay} approximates the tick duration. {@link #lastTickMillis()} exposes it.</p>
 */
@Component
@Primary
@Profile("sim")
public class SimulatedIseSessionClient extends IseSessionClient {

    private final int maxSessions;
    private final int rampTicks;
    private final long pollDelayMs;
    private final AtomicInteger tick = new AtomicInteger();
    private volatile long lastCallNanos = 0;
    private volatile long lastTickMillis = 0;

    public SimulatedIseSessionClient(IseProperties props,
                                     @Value("${app.sim.sessions:20000}") int maxSessions,
                                     @Value("${app.sim.ramp-ticks:20}") int rampTicks,
                                     @Value("${app.ise.session-poll-interval-ms:15000}") long pollDelayMs) {
        super(props);
        this.maxSessions = maxSessions;
        this.rampTicks = Math.max(1, rampTicks);
        this.pollDelayMs = pollDelayMs;
    }

    @Override
    public SessionPoll fetchActiveSessions() {
        long now = System.nanoTime();
        if (lastCallNanos != 0) {
            lastTickMillis = Math.max(0, (now - lastCallNanos) / 1_000_000 - pollDelayMs);
        }
        lastCallNanos = now;

        int t = Math.min(tick.incrementAndGet(), rampTicks);
        int count = (int) ((long) maxSessions * t / rampTicks);

        List<IseActiveSession> sessions = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            sessions.add(new IseActiveSession(SimIdentity.mac(i), SimIdentity.ip(i)));
        }
        return new SessionPoll(true, sessions, null);
    }

    /** @return approximate duration of the watcher's previous tick, in milliseconds */
    public long lastTickMillis() {
        return lastTickMillis;
    }

    /** @return how many sessions the latest poll returned */
    public int currentSessionCount() {
        int t = Math.min(tick.get(), rampTicks);
        return (int) ((long) maxSessions * t / rampTicks);
    }
}