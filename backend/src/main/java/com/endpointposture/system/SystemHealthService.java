package com.endpointposture.system;

import com.endpointposture.job.JobStatus;
import com.endpointposture.job.JobWorkerPool;
import com.endpointposture.job.PostureJob;
import com.endpointposture.job.PostureJobRepository;
import com.endpointposture.session.IseLinkHealth;
import com.endpointposture.system.SystemHealthDtos.Database;
import com.endpointposture.system.SystemHealthDtos.Ise;
import com.endpointposture.system.SystemHealthDtos.Queue;
import com.endpointposture.system.SystemHealthDtos.SystemHealth;
import com.endpointposture.system.SystemHealthDtos.Workers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds one snapshot of the platform's own health: database, ISE poll,
 * job queue and worker pool. Read-only; never calls ISE itself (it only
 * reads what {@link IseLinkHealth} already recorded).
 *
 * <p>Status rules: {@code DOWN} if the database is unreachable, otherwise
 * {@code DEGRADED} if any warning applies, otherwise {@code UP}.</p>
 */
@Service
public class SystemHealthService {

    /** A queued job older than this suggests workers are stuck or overloaded. */
    static final Duration STUCK_QUEUE_AFTER = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final PostureJobRepository jobs;
    private final IseLinkHealth iseHealth;
    private final ObjectProvider<JobWorkerPool> workerPool;

    public SystemHealthService(JdbcTemplate jdbc, PostureJobRepository jobs,
                               IseLinkHealth iseHealth, ObjectProvider<JobWorkerPool> workerPool) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.iseHealth = iseHealth;
        this.workerPool = workerPool;
    }

    /** @return a fresh snapshot; never throws (a broken database is reported, not propagated) */
    public SystemHealth snapshot() {
        Instant now = Instant.now();

        Database db = checkDatabase();
        IseLinkHealth.Status iseStatus = iseHealth.status();
        Ise ise = new Ise(iseStatus.reachable(), iseStatus.lastSuccessAt(), iseStatus.lastError());

        JobWorkerPool pool = workerPool.getIfAvailable();
        Workers workers = new Workers(pool != null, pool != null ? pool.getThreads() : 0);

        // Without a database there is no queue to inspect.
        Queue queue = db.reachable() ? readQueue(now) : new Queue(0, 0, 0, 0, null, 0);

        List<String> warnings = warnings(db.reachable(), ise.reachable(),
                queue.oldestQueuedAgeSeconds(), workers.enabled());
        return new SystemHealth(overallStatus(db.reachable(), warnings), now, db, ise, queue, workers, warnings);
    }

    private Database checkDatabase() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return new Database(true, null);
        } catch (Exception e) {
            return new Database(false, e.getMessage());
        }
    }

    private Queue readQueue(Instant now) {
        Long oldestAge = jobs.findFirstByStatusOrderByCreatedAtAsc(JobStatus.QUEUED)
                .map(j -> ageSeconds(j, now))
                .orElse(null);

        return new Queue(
                jobs.countByStatus(JobStatus.QUEUED),
                jobs.countByStatus(JobStatus.RUNNING),
                jobs.countByStatus(JobStatus.COMPLETE),
                jobs.countByStatus(JobStatus.FAILED),
                oldestAge,
                jobs.countByStatusAndCompletedAtAfter(JobStatus.FAILED, now.minus(Duration.ofHours(24))));
    }

    /** A retry waiting out its backoff is not "stuck", so age counts from when it became eligible. */
    private static long ageSeconds(PostureJob job, Instant now) {
        Instant eligibleSince = job.getNextAttemptAt() != null ? job.getNextAttemptAt() : job.getCreatedAt();
        return Math.max(0, Duration.between(eligibleSince, now).getSeconds());
    }

    static List<String> warnings(boolean dbReachable, boolean iseReachable,
                                 Long oldestQueuedAgeSeconds, boolean workersEnabled) {
        List<String> out = new ArrayList<>();
        if (!dbReachable) out.add("Database is unreachable.");
        if (!iseReachable) out.add("Cisco ISE is unreachable; session state is frozen at its last known values.");
        if (!workersEnabled) out.add("The job worker pool is disabled; queued jobs will not run.");
        if (oldestQueuedAgeSeconds != null && oldestQueuedAgeSeconds > STUCK_QUEUE_AFTER.getSeconds()) {
            out.add("The oldest queued job has waited over " + STUCK_QUEUE_AFTER.toMinutes()
                    + " minutes; workers may be stuck or overloaded.");
        }
        return out;
    }

    static String overallStatus(boolean dbReachable, List<String> warnings) {
        if (!dbReachable) return "DOWN";
        return warnings.isEmpty() ? "UP" : "DEGRADED";
    }
}