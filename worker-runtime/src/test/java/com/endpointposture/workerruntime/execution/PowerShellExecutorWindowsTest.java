package com.endpointposture.workerruntime.execution;

import com.endpointposture.workerruntime.api.ClaimedJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class PowerShellExecutorWindowsTest {
    private static final UUID ID=UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    @TempDir Path scripts;
    private ClaimedJob claimed(Duration lease) throws Exception {
        return new ClaimedJob(ID,1,"HARDWARE_CHECK",new ObjectMapper().readTree("{}"),Instant.now().plus(lease));
    }
    private ValidatedJob job(Duration timeout) {
        return new ValidatedJob(ID,1,"HARDWARE_CHECK","hardware_health_agent.ps1",List.of("-OutputOnly"),timeout);
    }
    private PowerShellExecutor executor(String exe,int cap) {
        return new PowerShellExecutor(new PowerShellExecutionConfig(true,exe,scripts,Duration.ofSeconds(1),cap));
    }
    private String envelope(String outcome,String payload) {
        return "{\"schemaVersion\":1,\"jobId\":\""+ID+"\",\"jobType\":\"HARDWARE_CHECK\","
                + "\"collectionOutcome\":\""+outcome+"\",\"reportPayload\":"+payload+","
                + "\"errors\":[\"collection warning\"],\"warnings\":[]}";
    }
    private void script(String source) throws Exception {
        Files.writeString(scripts.resolve("hardware_health_agent.ps1"),source);
    }
    @Test void parsesCompleteStructuredEnvelope() throws Exception {
        script("Write-Output 'RESULT_JSON:"+envelope("SUCCESS","{\"report\":{\"all\":true}}")+"'");
        var result=executor("powershell.exe",4096).execute(claimed(Duration.ofMinutes(2)),job(Duration.ofSeconds(20)));
        assertEquals(ID.toString(),result.result().path("jobId").asText());
        assertTrue(result.result().path("reportPayload").path("report").path("all").asBoolean());
        assertEquals(0,result.exitCode());
    }
    @Test void preservesPartialReportWhenPowerShellExitsNonZero() throws Exception {
        script("Write-Output 'RESULT_JSON:"+envelope("PARTIAL","{\"partial\":true}")+"'; exit 7");
        var result=executor("powershell.exe",4096).execute(claimed(Duration.ofMinutes(2)),job(Duration.ofSeconds(20)));
        assertEquals(7,result.exitCode());
        assertEquals("PARTIAL",result.result().path("collectionOutcome").asText());
        assertTrue(result.result().path("reportPayload").path("partial").asBoolean());
    }
    @Test void boundsRetainedStdoutAndStderr() throws Exception {
        script("Write-Output 'RESULT_JSON:"+envelope("SUCCESS","{}")+"'; 1..500 | ForEach-Object { Write-Output ('x' * 100) }; [Console]::Error.WriteLine('y' * 5000)");
        var result=executor("powershell.exe",1024).execute(claimed(Duration.ofMinutes(2)),job(Duration.ofSeconds(20)));
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
        assertEquals(ID.toString(),result.result().path("jobId").asText());
    }
    @Test void enforcesProcessTimeoutAndLeaseSafetyDeadline() throws Exception {
        script("Start-Sleep -Seconds 15");
        assertThrows(java.util.concurrent.TimeoutException.class,()->executor("powershell.exe",4096).execute(claimed(Duration.ofMinutes(2)),job(Duration.ofSeconds(1))));
        assertThrows(IllegalStateException.class,()->executor("powershell.exe",4096).execute(claimed(Duration.ofSeconds(1)),job(Duration.ofSeconds(20))));
    }
    @Test void rejectsUnapprovedScriptAndMissingExecutable() throws Exception {
        script("Write-Output 'RESULT_JSON:"+envelope("SUCCESS","{}")+"'");
        assertThrows(IllegalArgumentException.class,()->executor("powershell.exe",4096).resolveApprovedScript("../posture_agent.ps1"));
        assertThrows(java.io.IOException.class,()->executor("no-such-powershell-executable",4096).execute(claimed(Duration.ofMinutes(2)),job(Duration.ofSeconds(20))));
    }

    @Test void killsChildThatKeepsOutputPipesOpenAfterParentExits() throws Exception {
        Path childPidFile = scripts.resolve("orphan-child.pid");
        String pidPath = childPidFile.toString().replace("'", "''");
        String parent = "$psi = New-Object System.Diagnostics.ProcessStartInfo; "
                + "$psi.FileName = 'powershell.exe'; "
                + "$psi.Arguments = '-NoProfile -NonInteractive -Command Start-Sleep -Seconds 60'; "
                + "$psi.UseShellExecute = $false; "
                + "$child = [System.Diagnostics.Process]::Start($psi); "
                + "Set-Content -LiteralPath '" + pidPath + "' -Value $child.Id; "
                + "Start-Sleep -Milliseconds 250; exit 0";
        script(parent);

        assertThrows(java.io.IOException.class,
                () -> executor("powershell.exe", 4096).execute(
                        claimed(Duration.ofMinutes(2)), job(Duration.ofSeconds(20))));

        assertTrue(Files.exists(childPidFile), "parent must record child PID before exiting");
        long childPid = Long.parseLong(Files.readString(childPidFile).trim());
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                "child process must be terminated after parent exits and output capture times out");
    }

    @Test void rejectsMalformedAndMismatchedResultJson() throws Exception {
        script("Write-Output 'RESULT_JSON:not-json'");
        assertThrows(java.io.IOException.class,()->executor("powershell.exe",4096).execute(claimed(Duration.ofMinutes(2)),job(Duration.ofSeconds(20))));
        script("Write-Output 'RESULT_JSON:{\"schemaVersion\":1,\"jobId\":\"00000000-0000-0000-0000-000000000000\",\"jobType\":\"HARDWARE_CHECK\",\"collectionOutcome\":\"SUCCESS\",\"reportPayload\":{},\"errors\":[],\"warnings\":[]}'");
        assertThrows(java.io.IOException.class,()->executor("powershell.exe",4096).execute(claimed(Duration.ofMinutes(2)),job(Duration.ofSeconds(20))));
    }
}