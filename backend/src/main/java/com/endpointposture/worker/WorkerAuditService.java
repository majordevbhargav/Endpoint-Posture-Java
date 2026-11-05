package com.endpointposture.worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;
/** Best-effort append-only audit for worker credential lifecycle events. */
@Service
public class WorkerAuditService {
    private static final Logger log=LoggerFactory.getLogger(WorkerAuditService.class);
    private final JdbcTemplate jdbc;
    public WorkerAuditService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void record(String type,UUID workerId,String workerName,String detail,String remoteAddr){
        try{
            String d=detail!=null&&detail.length()>500?detail.substring(0,500):detail;
            jdbc.update("INSERT INTO worker_auth_event (event_type,worker_id,worker_name,detail,remote_addr) VALUES (?,?,?,?,?)",
                type,workerId,workerName,d,remoteAddr);
        }catch(RuntimeException e){log.error("Could not write worker auth event {}",type,e);}
    }
}