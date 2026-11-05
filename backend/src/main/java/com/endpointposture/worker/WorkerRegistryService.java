package com.endpointposture.worker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
/** Enrollment, authentication and revocation. Token redemption is a single atomic UPDATE. */
@Service
public class WorkerRegistryService {
    static final Pattern NAME=Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{1,62}$");
    static final Pattern SHARD=Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$");
    public enum Status{OK,UNKNOWN_KEY,REVOKED}
    public record AuthResult(Status status,UUID workerId,String name,String shard){
        public WorkerPrincipal principal(){return new WorkerPrincipal(workerId,name,shard);}
    }
    public record EnrollmentToken(String token,String workerName,String shard,Instant expiresAt){}
    public record Enrolled(UUID workerId,String name,String shard,String apiKey){}
    /** Derived operational state; never stored, so it cannot go out of date between sweeps. */
    public enum WorkerState{ACTIVE,STALE,REVOKED}
    public record WorkerView(UUID id,String name,String shard,boolean enabled,Instant createdAt,Instant lastSeenAt,Instant revokedAt,String createdBy,WorkerState status){}
    public record Heartbeat(WorkerState status,Instant lastSeenAt,long heartbeatIntervalSeconds,long staleAfterSeconds){}
    public static class EnrollmentRejectedException extends RuntimeException{
        public EnrollmentRejectedException(){super("Invalid, expired or already used enrollment token");}
    }
    public static class WorkerNotFoundException extends RuntimeException{
        public WorkerNotFoundException(UUID id){super("No worker found with id "+id);}
    }
    private record TokenRow(UUID id,String workerName,String shard,String createdBy){}
    private final JdbcTemplate jdbc;
    private final Duration heartbeatInterval;
    private final Duration staleAfter;
    public WorkerRegistryService(JdbcTemplate jdbc,
            @Value("${app.workers.heartbeat-interval-seconds:30}") long heartbeatIntervalSeconds,
            @Value("${app.workers.stale-after-seconds:90}") long staleAfterSeconds){
        if(heartbeatIntervalSeconds<1)throw new IllegalArgumentException("app.workers.heartbeat-interval-seconds must be at least 1");
        if (heartbeatIntervalSeconds > staleAfterSeconds / 2)
            throw new IllegalArgumentException("app.workers.stale-after-seconds must be at least twice the heartbeat interval");
        this.jdbc=jdbc;this.heartbeatInterval=Duration.ofSeconds(heartbeatIntervalSeconds);this.staleAfter=Duration.ofSeconds(staleAfterSeconds);
    }

    /** REVOKED when disabled; STALE when never seen or silent past the threshold; otherwise ACTIVE. */
    static WorkerState stateOf(boolean enabled,Instant lastSeenAt,Instant now,Duration staleAfter){
        if(!enabled)return WorkerState.REVOKED;
        if(lastSeenAt==null||lastSeenAt.isBefore(now.minus(staleAfter)))return WorkerState.STALE;
        return WorkerState.ACTIVE;
    }

    /**
     * Records a heartbeat for the already-authenticated worker. The id comes from the principal only.
     * Returns empty when the worker is not enabled (revoked between authentication and now).
     */
    @Transactional
    public java.util.Optional<Heartbeat> heartbeat(UUID workerId){
        List<Timestamp> rows=jdbc.query("UPDATE worker_registry SET last_seen_at=now() WHERE id=? AND enabled RETURNING last_seen_at",
          (rs,i)->rs.getTimestamp(1),workerId);
        if(rows.isEmpty())return java.util.Optional.empty();
        return java.util.Optional.of(new Heartbeat(WorkerState.ACTIVE,rows.get(0).toInstant(),heartbeatInterval.toSeconds(),staleAfter.toSeconds()));
    }

    @Transactional
    public EnrollmentToken createEnrollmentToken(String workerName,String shard,Integer ttlMinutes,String admin){
        String name=require(workerName,NAME,"workerName must be 2-63 characters: letters, digits, '.', '_' or '-'");
        String sh=require(shard,SHARD,"shard must be 1-63 characters: letters, digits, '.', '_' or '-'");
        int ttl=ttlMinutes==null?60:Math.max(1,Math.min(ttlMinutes,1440));
        Integer taken=jdbc.queryForObject("SELECT count(*) FROM worker_registry WHERE lower(name)=lower(?)",Integer.class,name);
        if(taken!=null&&taken>0)throw new IllegalStateException("A worker named '"+name+"' already exists");
        Integer pending=jdbc.queryForObject("""
                SELECT count(*) FROM worker_enrollment_token
                 WHERE lower(worker_name)=lower(?) AND redeemed_at IS NULL AND expires_at > now()
                """,Integer.class,name);
        if(pending!=null&&pending>0)throw new IllegalStateException("An active enrollment token already exists for '"+name+"'");
        String token=WorkerCredentials.newEnrollmentToken();
        Timestamp expires=jdbc.queryForObject("""
          INSERT INTO worker_enrollment_token(token_hash,worker_name,shard,expires_at,created_by)
          VALUES(?,?,?,now()+(?::int*interval '1 minute'),?) RETURNING expires_at
          """,Timestamp.class,WorkerCredentials.sha256Hex(token),name,sh,ttl,admin);
        return new EnrollmentToken(token,name,sh,expires.toInstant());
    }

    @Transactional
    public Enrolled enroll(String token){
        if(!WorkerCredentials.looksLikeToken(token))throw new EnrollmentRejectedException();
        List<TokenRow> rows=jdbc.query("""
          UPDATE worker_enrollment_token SET redeemed_at=now()
          WHERE token_hash=? AND redeemed_at IS NULL AND expires_at>now()
          RETURNING id,worker_name,shard,created_by
          """,(rs,i)->new TokenRow(rs.getObject("id",UUID.class),rs.getString("worker_name"),rs.getString("shard"),rs.getString("created_by")),
          WorkerCredentials.sha256Hex(token));
        if(rows.isEmpty())throw new EnrollmentRejectedException();
        TokenRow row=rows.get(0); String key=WorkerCredentials.newKey(); UUID id;
        try{
            id=jdbc.queryForObject("""
              INSERT INTO worker_registry(name,shard,key_hash,created_by) VALUES(?,?,?,?) RETURNING id
              """,UUID.class,row.workerName(),row.shard(),WorkerCredentials.sha256Hex(key),row.createdBy());
        }catch(DuplicateKeyException e){throw new IllegalStateException("A worker named '"+row.workerName()+"' already exists");}
        jdbc.update("UPDATE worker_enrollment_token SET redeemed_worker_id=? WHERE id=?",id,row.id());
        return new Enrolled(id,row.workerName(),row.shard(),key);
    }

    @Transactional
    public AuthResult authenticate(String key){
        List<AuthResult> rows=jdbc.query("SELECT id,name,shard,enabled FROM worker_registry WHERE key_hash=?",
          (rs,i)->new AuthResult(rs.getBoolean("enabled")?Status.OK:Status.REVOKED,
            rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("shard")),WorkerCredentials.sha256Hex(key));
        if(rows.isEmpty())return new AuthResult(Status.UNKNOWN_KEY,null,null,null);
        AuthResult r=rows.get(0);
        if (r.status() == Status.OK) {
    jdbc.update(
        "UPDATE worker_registry SET last_seen_at=now() " +
        "WHERE id=? AND (last_seen_at IS NULL OR last_seen_at < now() - (? * interval '1 second'))",
        r.workerId(),
        heartbeatInterval.toSeconds()
    );
}
        return r;
    }

    @Transactional(readOnly=true)
    public List<WorkerView> list(){
        Instant now=Instant.now();
        return jdbc.query("SELECT id,name,shard,enabled,created_at,last_seen_at,revoked_at,created_by FROM worker_registry ORDER BY created_at DESC",
          (rs,i)->{
            boolean enabled=rs.getBoolean("enabled");Instant seen=inst(rs.getTimestamp("last_seen_at"));
            return new WorkerView(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("shard"),enabled,
              rs.getTimestamp("created_at").toInstant(),seen,inst(rs.getTimestamp("revoked_at")),rs.getString("created_by"),
              stateOf(enabled,seen,now,staleAfter));
          });
    }

    @Transactional
    public String revoke(UUID id,String admin){
        List<String> changed=jdbc.query("UPDATE worker_registry SET enabled=false,revoked_at=now(),revoked_by=? WHERE id=? AND enabled RETURNING name",
          (rs,i)->rs.getString(1),admin,id);
        if(!changed.isEmpty())return changed.get(0);
        List<String> existing=jdbc.query("SELECT name FROM worker_registry WHERE id=?",(rs,i)->rs.getString(1),id);
        if(existing.isEmpty())throw new WorkerNotFoundException(id);
        return existing.get(0);
    }
    private static String require(String v,Pattern p,String message){
        if(v==null||!p.matcher(v.trim()).matches())throw new IllegalArgumentException(message);
        return v.trim();
    }
    private static Instant inst(Timestamp t){return t==null?null:t.toInstant();}
}