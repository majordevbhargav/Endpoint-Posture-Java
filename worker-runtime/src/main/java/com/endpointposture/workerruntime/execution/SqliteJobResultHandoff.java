package com.endpointposture.workerruntime.execution;

import com.endpointposture.workerruntime.api.ClaimedJob;
import com.endpointposture.workerruntime.api.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Durable local outbox. accept() returns only after a FULL-synchronous commit. */
public final class SqliteJobResultHandoff implements JobResultHandoff, AutoCloseable {
    private static final int SCHEMA_VERSION = 3;
    private static final int BUSY_TIMEOUT_MS = 10_000;
    private static final ObjectMapper JSON = Json.mapper();
    private final Path database;
    private final UUID workerId;
    private final String shard;
    private final Duration deliveryGrace;

    public SqliteJobResultHandoff(Path database, UUID workerId, String shard, Duration deliveryGrace) {
        this.database = database.toAbsolutePath().normalize();
        this.workerId = java.util.Objects.requireNonNull(workerId);
        this.shard = java.util.Objects.requireNonNull(shard);
        this.deliveryGrace = java.util.Objects.requireNonNull(deliveryGrace);
        if (shard.isBlank() || deliveryGrace.isNegative() || deliveryGrace.isZero())
            throw new IllegalArgumentException("shard and positive delivery grace are required");
        initialize();
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA synchronous=FULL");
        }
        return c;
    }

    private void initialize() {
        try {
            Files.createDirectories(database.getParent());
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement s = c.createStatement()) {
                s.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
                try(ResultSet mode=s.executeQuery("PRAGMA journal_mode=WAL")) {
                    if(!mode.next()||!"wal".equalsIgnoreCase(mode.getString(1)))
                        throw new SQLException("SQLite refused WAL mode");
                }
                s.execute("PRAGMA synchronous=FULL");
                s.execute("PRAGMA foreign_keys=ON");
                int version;
                try(ResultSet schemaVersion=s.executeQuery("PRAGMA user_version")) {
                    if(!schemaVersion.next()) throw new SQLException("SQLite did not return its schema version");
                    version=schemaVersion.getInt(1);
                }
                if (version > SCHEMA_VERSION) throw new SQLException("Unsupported outbox schema version " + version);
                if (version == 0) {
                    c.setAutoCommit(false);
                    try {
                        s.execute("""
                            CREATE TABLE result_outbox (
                              id INTEGER PRIMARY KEY,
                              worker_id TEXT NOT NULL, shard TEXT NOT NULL,
                              job_id TEXT NOT NULL, attempt INTEGER NOT NULL CHECK(attempt > 0),
                              lease_expires_at TEXT NOT NULL, job_type TEXT NOT NULL,
                              snapshot_version INTEGER NOT NULL CHECK(snapshot_version = 1),
                              snapshot_json BLOB NOT NULL, request_payload BLOB NOT NULL,
                              payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256) = 64),
                              state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','DELIVERED','PERMANENT_FAILURE')),
                              retry_count INTEGER NOT NULL DEFAULT 0,
                              next_attempt_at TEXT NOT NULL,
                              delivery_deadline TEXT NOT NULL,
                              cumulative_lease_extension_seconds INTEGER NOT NULL DEFAULT 0,
                              acknowledgement_json BLOB,
                              last_error TEXT,
                              created_at TEXT NOT NULL,
                              UNIQUE(job_id, attempt)
                            )
                            """);
                        s.execute("CREATE INDEX idx_result_outbox_pending ON result_outbox(state, next_attempt_at, id)");
                        s.execute("CREATE TABLE worker_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                        s.execute("""
                            CREATE TRIGGER result_outbox_immutable
                            BEFORE UPDATE OF worker_id, shard, job_id, attempt, lease_expires_at, job_type,
                              snapshot_version, snapshot_json, request_payload, payload_sha256, created_at
                            ON result_outbox BEGIN SELECT RAISE(ABORT, 'result identity and payload are immutable'); END
                            """);
                        createStateGuards(s);
                        s.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                        c.commit();
                    } catch (SQLException e) { c.rollback(); throw e; }
                    finally { c.setAutoCommit(true); }
                } else if(version==1) {
                    c.setAutoCommit(false);
                    try {
                        s.execute("CREATE TABLE worker_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                        createStateGuards(s);
                        s.execute("PRAGMA user_version=3");
                        c.commit();
                    } catch(SQLException e) { c.rollback(); throw e; }
                    finally { c.setAutoCommit(true); }
                } else if(version==2) {
                    c.setAutoCommit(false);
                    try {
                        createStateGuards(s);
                        s.execute("PRAGMA user_version=3");
                        c.commit();
                    } catch(SQLException e) { c.rollback(); throw e; }
                    finally { c.setAutoCommit(true); }
                }
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Cannot initialize durable worker result store at " + database + ": " + e.getMessage(), e);
        }
    }

    private static void createStateGuards(Statement s) throws SQLException {
        s.execute("""
            CREATE TRIGGER result_outbox_state_transition
            BEFORE UPDATE OF state ON result_outbox
            WHEN NOT (OLD.state='PENDING' AND NEW.state IN ('DELIVERED','PERMANENT_FAILURE'))
            BEGIN SELECT RAISE(ABORT, 'invalid result state transition'); END
            """);
        s.execute("""
            CREATE TRIGGER result_outbox_pending_delete
            BEFORE DELETE ON result_outbox WHEN OLD.state<>'DELIVERED'
            BEGIN SELECT RAISE(ABORT, 'unresolved result cannot be deleted'); END
            """);
    }

    @Override public void accept(CompletedJobResult result) throws Exception {
        byte[] snapshot = JSON.writeValueAsBytes(result);
        byte[] request = requestPayload(result);
        String hash = sha256(snapshot, request);
        ClaimedJob claim = result.claimedJob();
        Instant now = Instant.now();
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement p = c.prepareStatement("""
                    INSERT INTO result_outbox(worker_id,shard,job_id,attempt,lease_expires_at,job_type,
                      snapshot_version,snapshot_json,request_payload,payload_sha256,next_attempt_at,delivery_deadline,created_at)
                    VALUES(?,?,?,?,?,?,1,?,?,?,?,?,?)
                    ON CONFLICT(job_id,attempt) DO NOTHING
                    """)) {
                p.setString(1, workerId.toString()); p.setString(2, shard);
                p.setString(3, claim.jobId().toString()); p.setInt(4, claim.attempt());
                p.setString(5, claim.leaseExpiresAt().toString()); p.setString(6, claim.jobType());
                p.setBytes(7, snapshot); p.setBytes(8, request); p.setString(9, hash);
                p.setString(10, now.toString()); p.setString(11, now.plus(deliveryGrace).toString());
                p.setString(12, now.toString());
                int inserted = p.executeUpdate();
                if (inserted == 0) {
                    try (PreparedStatement q = c.prepareStatement("SELECT worker_id,shard,payload_sha256 FROM result_outbox WHERE job_id=? AND attempt=?")) {
                        q.setString(1, claim.jobId().toString()); q.setInt(2, claim.attempt());
                        try (ResultSet rs = q.executeQuery()) {
                            if (!rs.next() || !workerId.toString().equals(rs.getString(1))
                                    || !shard.equals(rs.getString(2)) || !hash.equals(rs.getString(3)))
                                throw new SQLException("Conflicting result already exists for this job attempt");
                        }
                    }
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
    }

    private static byte[] requestPayload(CompletedJobResult completed) throws IOException {
        var envelope = completed.envelope();
        ObjectNode request = JSON.createObjectNode();
        request.put("attempt", completed.claimedJob().attempt());
        request.put("schemaVersion", envelope.path("schemaVersion").asInt());
        request.put("jobType", completed.claimedJob().jobType());
        request.put("collectionOutcome", envelope.path("collectionOutcome").asText());
        request.set("reportPayload", envelope.path("reportPayload").deepCopy());
        request.set("errors", envelope.path("errors").deepCopy());
        request.set("warnings", envelope.path("warnings").deepCopy());
        request.put("processExitCode", completed.processExitCode());
        return JSON.writeValueAsBytes(request);
    }

    private static String sha256(byte[] snapshot, byte[] request) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        d.update("worker-result-outbox-v1\0".getBytes(StandardCharsets.UTF_8));
        d.update(intBytes(snapshot.length)); d.update(snapshot);
        d.update(intBytes(request.length)); d.update(request);
        return HexFormat.of().formatHex(d.digest());
    }
    private static byte[] intBytes(int i) { return new byte[]{(byte)(i>>>24),(byte)(i>>>16),(byte)(i>>>8),(byte)i}; }

    @Override public boolean hasPendingResults() { return pendingCount() > 0; }
    @Override public boolean durable() { return true; }
    @Override public boolean retryPersistenceFailure(Exception failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof SQLException sql) {
                int code=sql.getErrorCode();
                return code==5 || code==6 || code==8 || code==10 || code==13 || code==14 || code==15;
            }
        }
        return false;
    }
    public int pendingCount() {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM result_outbox WHERE state<>'DELIVERED'")) { return r.getInt(1); }
        catch (SQLException e) { throw storeError(e); }
    }

    public String journalMode() {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+database);Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA journal_mode")){return r.getString(1);}
        catch(SQLException e){throw storeError(e);}
    }
    public int synchronousMode() {
        try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA synchronous")){return r.getInt(1);}
        catch(SQLException e){throw storeError(e);}
    }
    public boolean foreignKeysEnabled() {
        try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA foreign_keys")){return r.getInt(1)==1;}
        catch(SQLException e){throw storeError(e);}
    }
    public String state(UUID jobId,int attempt) {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT state FROM result_outbox WHERE job_id=? AND attempt=?")){
            p.setString(1,jobId.toString());p.setInt(2,attempt);try(ResultSet r=p.executeQuery()){return r.next()?r.getString(1):null;}
        }catch(SQLException e){throw storeError(e);}
    }
    public OutboxStatistics statistics() {
        String unresolved=null; long pending=0,failed=0,delivered=0,retries=0;
        try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("""
                SELECT sum(CASE WHEN state='PENDING' THEN 1 ELSE 0 END),
                       sum(CASE WHEN state='PERMANENT_FAILURE' THEN 1 ELSE 0 END),
                       sum(CASE WHEN state='DELIVERED' THEN 1 ELSE 0 END),
                       coalesce(sum(retry_count),0),min(CASE WHEN state<>'DELIVERED' THEN created_at END)
                FROM result_outbox
                """)) {
            if(r.next()){pending=r.getLong(1);failed=r.getLong(2);delivered=r.getLong(3);retries=r.getLong(4);unresolved=r.getString(5);}
        }catch(SQLException e){throw storeError(e);}
        long bytes=0;
        for(Path p:List.of(database,Path.of(database+"-wal"),Path.of(database+"-shm")))
            try{bytes+=Files.size(p);}catch(IOException ignored){}
        return new OutboxStatistics(pending,failed,delivered,retries,unresolved==null?null:Instant.parse(unresolved),bytes);
    }
    public void recordBackendSuccess(Instant at) {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("INSERT INTO worker_metadata(key,value) VALUES('last_backend_success',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
            p.setString(1,at.toString());p.executeUpdate();
        }catch(SQLException e){throw storeError(e);}
    }
    public Instant lastBackendSuccess() {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT value FROM worker_metadata WHERE key='last_backend_success'");ResultSet r=p.executeQuery()){
            return r.next()?Instant.parse(r.getString(1)):null;
        }catch(SQLException e){throw storeError(e);}
    }

    public List<PersistedJobResult> pending(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("""
                SELECT worker_id,shard,snapshot_json,request_payload,payload_sha256,retry_count,next_attempt_at,delivery_deadline,cumulative_lease_extension_seconds
                FROM result_outbox WHERE state='PENDING' ORDER BY id LIMIT ?
                """)) {
            p.setInt(1, limit);
            try (ResultSet r=p.executeQuery()) {
                var found = new java.util.ArrayList<PersistedJobResult>();
                while (r.next()) {
                    byte[] snap=r.getBytes(3), req=r.getBytes(4); String hash=r.getString(5);
                    if (!hash.equals(sha256(snap,req))) throw new SQLException("Stored worker result integrity hash mismatch");
                    CompletedJobResult result=JSON.readValue(snap,CompletedJobResult.class);
                    found.add(new PersistedJobResult(UUID.fromString(r.getString(1)),r.getString(2),result,req,hash,
                        r.getInt(6),Instant.parse(r.getString(7)),Instant.parse(r.getString(8)),r.getInt(9)));
                }
                return List.copyOf(found);
            }
        } catch (Exception e) { throw storeError(e); }
    }

    public void updateDelivery(UUID jobId,int attempt,int retryCount,Instant nextAttempt,int leaseExtension,String error) {
        update("UPDATE result_outbox SET retry_count=?,next_attempt_at=?,cumulative_lease_extension_seconds=?,last_error=? WHERE job_id=? AND attempt=? AND state='PENDING'",
                retryCount,nextAttempt.toString(),leaseExtension,safe(error),jobId.toString(),attempt);
    }
    /** Reserve extension budget durably before making a renewal request, so a crash cannot reset the cap. */
    public boolean reserveLeaseExtension(UUID jobId,int attempt,int expected,int requested) {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE result_outbox SET cumulative_lease_extension_seconds=? WHERE job_id=? AND attempt=? AND state='PENDING' AND cumulative_lease_extension_seconds=?")){
            p.setInt(1,requested);p.setString(2,jobId.toString());p.setInt(3,attempt);p.setInt(4,expected);
            return p.executeUpdate()==1;
        }catch(SQLException e){throw storeError(e);}
    }
    public void markDelivered(UUID jobId,int attempt,byte[] acknowledgement) {
        update("UPDATE result_outbox SET state='DELIVERED',acknowledgement_json=?,last_error=NULL WHERE job_id=? AND attempt=? AND state='PENDING'",
                acknowledgement,jobId.toString(),attempt);
    }
    public void markPermanentFailure(UUID jobId,int attempt,String error) {
        update("UPDATE result_outbox SET state='PERMANENT_FAILURE',last_error=? WHERE job_id=? AND attempt=? AND state='PENDING'",
                safe(error),jobId.toString(),attempt);
    }
    private void update(String sql,Object... args) {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) p.setObject(i+1,args[i]); p.executeUpdate();
        } catch(SQLException e){throw storeError(e);}
    }
    private static String safe(String s) { if(s==null) return null; return s.length()>500?s.substring(0,500):s; }
    private static IllegalStateException storeError(Exception e) { return new IllegalStateException("Worker result store failure: "+e.getClass().getSimpleName()+": "+e.getMessage(),e); }
    public Path databasePath() { return database; }
    @Override public void close() { /* connections are scoped to each operation */ }
}
