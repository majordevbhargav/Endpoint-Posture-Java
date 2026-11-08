package com.endpointposture.workerruntime.execution;

import com.endpointposture.workerruntime.api.ClaimedJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JobSpecParserTest {
    private static final UUID ID=UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private final JobSpecParser parser=new JobSpecParser(java.net.URI.create("http://localhost:8090"));
    private ClaimedJob job(String type,String spec) throws Exception {
        return new ClaimedJob(ID,1,type,new ObjectMapper().readTree(spec),Instant.now().plusSeconds(300));
    }
    @Test void mapsAllFourApprovedJobTypesToOutputOnly() throws Exception {
        ValidatedJob posture=parser.parse(job("POSTURE_CHECK",base()+",\"cimTimeoutSec\":30,\"serverTimeoutSec\":20,\"policyVersion\":1,\"requiredApps\":[\"Cisco Secure Client\"],\"blockedApps\":[]}"));
        ValidatedJob hardware=parser.parse(job("HARDWARE_CHECK",base()+",\"cimTimeoutSec\":30,\"submitTimeoutSec\":20}"));
        ValidatedJob diagnostic=parser.parse(job("DIAGNOSTIC_CHECK",base()+",\"mac\":\"AA:BB:CC:DD:EE:FF\",\"winrmOpenTimeoutSec\":20,\"winrmOperationTimeoutSec\":70,\"submitTimeoutSec\":20,\"dnsTestName\":\"example.com\",\"internetTarget\":\"8.8.8.8\"}"));
        ValidatedJob security=parser.parse(job("SECURITY_CHECK",base()+",\"mac\":\"AA:BB:CC:DD:EE:FF\",\"winrmOpenTimeoutSec\":20,\"winrmOperationTimeoutSec\":100,\"submitTimeoutSec\":20,\"sampleCount\":12,\"sampleIntervalSec\":5}"));
        assertEquals("posture_agent.ps1",posture.scriptName());
        assertEquals("hardware_health_agent.ps1",hardware.scriptName());
        assertEquals("diagnostic_agent.ps1",diagnostic.scriptName());
        assertEquals("security_indicator_agent.ps1",security.scriptName());
        for (ValidatedJob parsed : new ValidatedJob[]{posture,hardware,diagnostic,security}) {
            assertTrue(parsed.arguments().contains("-OutputOnly"),parsed.jobType());
        }
    }
    @Test void rejectsUnknownTypeAndJobIdMismatch() throws Exception {
        assertThrows(IllegalArgumentException.class,()->parser.parse(job("DELETE_ALL",base()+"}")));
        assertThrows(IllegalArgumentException.class,()->parser.parse(job("HARDWARE_CHECK",base().replace(ID.toString(),"00000000-0000-0000-0000-000000000000")+",\"cimTimeoutSec\":30,\"submitTimeoutSec\":20}")));
    }
    @Test void rejectsInvalidTargetAndTimeout() throws Exception {
        assertThrows(IllegalArgumentException.class,()->parser.parse(job("HARDWARE_CHECK",base().replace("host-01","../evil")+",\"cimTimeoutSec\":30,\"submitTimeoutSec\":20}")));
        assertThrows(IllegalArgumentException.class,()->parser.parse(job("HARDWARE_CHECK",base()+",\"cimTimeoutSec\":0,\"submitTimeoutSec\":20}")));
    }
    @Test void arbitraryScriptFieldsAreIgnoredAndArgsStaySeparated() throws Exception {
        ValidatedJob parsed=parser.parse(job("HARDWARE_CHECK",base()+",\"cimTimeoutSec\":30,\"submitTimeoutSec\":20,\"script\":\"Remove-Item\"}"));
        assertTrue(parsed.arguments().contains("-ComputerName"));
        assertTrue(parsed.arguments().contains("-OutputOnly"));
        assertFalse(parsed.arguments().stream().anyMatch(s->s.contains("Remove-Item")));
    }
    private static String base() {
        return "{\"jobId\":\""+ID+"\",\"target\":\"host-01\",\"processTimeoutSeconds\":120";
    }
}