package com.endpointposture.workerruntime.execution;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PowerShellOutputContractTest {
    private static final Map<String,String> AGENTS=Map.of(
        "posture_agent.ps1","POSTURE_CHECK",
        "hardware_health_agent.ps1","HARDWARE_CHECK",
        "diagnostic_agent.ps1","DIAGNOSTIC_CHECK",
        "security_indicator_agent.ps1","SECURITY_CHECK"
    );
    @Test void allAgentsExposeOutputOnlyAndPreserveLegacySubmission() throws Exception {
        for (var entry:AGENTS.entrySet()) {
            String script=Files.readString(Path.of("..","scripts",entry.getKey()));
            assertTrue(script.contains("[switch]$OutputOnly"),entry.getKey());
            assertTrue(script.contains("function Write-WorkerOutputEnvelope"),entry.getKey());
            assertTrue(script.contains("schemaVersion = 1"),entry.getKey());
            assertTrue(script.contains("jobId = $JobId"),entry.getKey());
            assertTrue(script.contains("jobType = $JobType"),entry.getKey());
            assertTrue(script.contains("collectionOutcome = $CollectionOutcome"),entry.getKey());
            assertTrue(script.contains("reportPayload = $ReportPayload"),entry.getKey());
            assertTrue(script.contains("errors ="),entry.getKey());
            assertTrue(script.contains("warnings ="),entry.getKey());
            assertTrue(script.contains("Invoke-RestMethod"),entry.getKey());
            assertTrue(script.contains("$env:POSTURE_API_KEY"),entry.getKey());
            int branch=script.lastIndexOf("if ($OutputOnly) {");
            int close=script.indexOf("\n}\n",branch);
            int indentedClose=script.indexOf("\n    }\n",branch);
            int headers=script.indexOf("$Headers =",branch);
            int apiKey=script.indexOf("$ApiKey =",branch);
            int auth=script.indexOf("# Authentication:",branch);
            if(close<0 || (indentedClose>=0 && indentedClose<close)) close=indentedClose;
            if(close<0 || (headers>=0 && headers<close)) close=headers;
            if(close<0 || (apiKey>=0 && apiKey<close)) close=apiKey;
            if(close<0 || (auth>=0 && auth<close)) close=auth;
            assertTrue(branch>=0 && close>branch,entry.getKey()+" output-only branch is not closed");
            String outputOnly=script.substring(branch,close);
            assertFalse(outputOnly.contains("Invoke-RestMethod"),entry.getKey());
            assertFalse(outputOnly.contains("$env:POSTURE_API_KEY"),entry.getKey());
            assertTrue(script.indexOf("Invoke-RestMethod",close)>close,entry.getKey()+" legacy submit path moved");
            if ("posture_agent.ps1".equals(entry.getKey())) {
                assertTrue(outputOnly.contains("$AllChecks | Where-Object { $_.status -eq \"ERROR\" }"),
                    "POSTURE_CHECK must derive output-only diagnostics from every ERROR check");
                assertTrue(outputOnly.contains("$($_.checkType): $Summary"),
                    "POSTURE_CHECK diagnostics must identify the check and preserve its detail");
                assertTrue(outputOnly.contains("PARTIAL posture result had no check-level diagnostic"),
                    "POSTURE_CHECK PARTIAL must have a nonblank fallback diagnostic");
            }
        }
    }
}