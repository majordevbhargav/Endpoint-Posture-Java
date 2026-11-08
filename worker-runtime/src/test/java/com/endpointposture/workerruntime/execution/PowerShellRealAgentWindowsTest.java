package com.endpointposture.workerruntime.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class PowerShellRealAgentWindowsTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final UUID JOB_ID=UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final Path SCRIPTS=Path.of("..","scripts").toAbsolutePath().normalize();

    private record Agent(String file,String type,List<String> args,List<String> payloadFields) {}
    private List<Agent> agents() {
        return List.of(
            new Agent("posture_agent.ps1","POSTURE_CHECK",
                List.of("-CimOperationTimeoutSec","15","-PortProbeTimeoutMs","50"),
                List.of("checks","inventory","hardware","collectedAt")),
            new Agent("hardware_health_agent.ps1","HARDWARE_CHECK",
                List.of("-CimOperationTimeoutSec","15"),
                List.of("endpoint","cpuMemory","storage","hardwareEvents","proactiveRecommendations")),
            new Agent("diagnostic_agent.ps1","DIAGNOSTIC_CHECK",
                List.of("-DnsTestName","localhost","-InternetTarget","127.0.0.1"),
                List.of("endpoint","gateway","dns","internet","tcp443","traceroute")),
            new Agent("security_indicator_agent.ps1","SECURITY_CHECK",
                List.of("-SampleCount","1","-SampleIntervalSec","1","-MaxConnectionsPerSample","10"),
                List.of("endpoint","windowSeconds","intervalSeconds","samples"))
        );
    }

    @Test void allProductionScriptsPassPowerShellAstParser() throws Exception {
        for (Agent agent:agents()) {
            Path script=SCRIPTS.resolve(agent.file());
            assertTrue(Files.isRegularFile(script),agent.file());
            ProcessBuilder builder=new ProcessBuilder("powershell.exe","-NoLogo","-NoProfile","-NonInteractive",
                "-Command","$tokens=$null;$parseErrors=$null;[System.Management.Automation.Language.Parser]::ParseFile($env:EP_SCRIPT_TO_PARSE,[ref]$tokens,[ref]$parseErrors)|Out-Null;if($parseErrors.Count){$parseErrors|ForEach-Object{[Console]::Error.WriteLine($_.ToString())};exit 1}");
            builder.redirectErrorStream(true);
            builder.environment().put("EP_SCRIPT_TO_PARSE",script.toString());
            Process process=builder.start();
            String output=readWithTimeout(process,30);
            assertEquals(0,process.exitValue(),agent.file()+" PowerShell AST parser failed: "+output);
            assertTrue(output.isBlank(),agent.file()+": "+output);
        }
    }

    @Test void realAgentsExecuteOutputOnlyAndPreserveCompletePayload() throws Exception {
        for (Agent agent:agents()) {
            Path script=SCRIPTS.resolve(agent.file());
            ProcessBuilder builder=new ProcessBuilder();
            var command=builder.command();
            command.addAll(List.of("powershell.exe","-NoLogo","-NoProfile","-NonInteractive",
                "-ExecutionPolicy","Bypass","-File",script.toString(),
                "-ComputerName",System.getenv().getOrDefault("COMPUTERNAME","localhost"),
                "-JobId",JOB_ID.toString(),"-OutputOnly"));
            command.addAll(agent.args());
            builder.redirectErrorStream(true);
            // A deliberately invalid endpoint/key makes accidental legacy submission fail.
            // A valid output-only result must not depend on either value.
            var env=builder.environment();
            env.put("POSTURE_API_KEY","pr13-output-only-test-must-not-submit");
            env.put("EP_TEST_SUBMIT_SENTINEL","http://127.0.0.1:1");
            Process process=builder.start();
            String output=readWithTimeout(process,180);
            assertTrue(output.contains("RESULT_JSON:"),agent.file()+": "+output);
            String jsonLine=output.lines().filter(line->line.startsWith("RESULT_JSON:"))
                .reduce((first,last)->last).orElseThrow();
            JsonNode envelope=JSON.readTree(jsonLine.substring("RESULT_JSON:".length()).trim());
            assertEquals(1,envelope.path("schemaVersion").asInt(),agent.file());
            assertEquals(JOB_ID.toString(),envelope.path("jobId").asText(),agent.file());
            assertEquals(agent.type(),envelope.path("jobType").asText(),agent.file());
            assertTrue(List.of("SUCCESS","PARTIAL","FAILED").contains(envelope.path("collectionOutcome").asText()),agent.file());
            assertTrue(envelope.path("errors").isArray(),agent.file());
            assertTrue(envelope.path("warnings").isArray(),agent.file());
            JsonNode payload=envelope.path("reportPayload");
            assertTrue(payload.isObject(),agent.file()+" returned no complete payload: "+output);
            for(String field:agent.payloadFields()) {
                assertTrue(payload.has(field),agent.file()+" omitted payload field "+field);
            }
            // The complete nested payload must survive JSON serialization and reparsing.
            JsonNode roundTrip=JSON.readTree(JSON.writeValueAsBytes(envelope));
            assertEquals(payload,roundTrip.path("reportPayload"),agent.file());
            int exitCode = process.exitValue();
            String outcome = envelope.path("collectionOutcome").asText();
            assertTrue(exitCode >= 0, agent.file() + " returned a negative process exit code");
            switch (outcome) {
                case "SUCCESS" -> {
                    assertEquals(0, exitCode, agent.file() + " SUCCESS must exit zero");
                    assertTrue(envelope.path("errors").isEmpty(), agent.file() + " SUCCESS must not contain errors");
                }
                case "PARTIAL" -> assertTrue(
                        hasNonBlankDiagnostic(envelope.path("errors")) || hasNonBlankDiagnostic(envelope.path("warnings")),
                        agent.file() + " PARTIAL must explain incomplete collection");
                case "FAILED" -> {
                    assertTrue(exitCode > 0, agent.file() + " FAILED must exit non-zero");
                    assertTrue(hasNonBlankDiagnostic(envelope.path("errors")),
                            agent.file() + " FAILED must contain a non-blank error");
                }
                default -> fail(agent.file() + " returned an unsupported collection outcome: " + outcome);
            }
        }
    }

    private static boolean hasNonBlankDiagnostic(JsonNode values) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) {
            if (value.isTextual() && !value.asText().isBlank()) return true;
        }
        return false;
    }

    private static String readWithTimeout(Process process,long seconds) throws Exception {
        CompletableFuture<String> output=CompletableFuture.supplyAsync(()->{
            try{return new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);}
            catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
        });
        if(!process.waitFor(seconds,TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("PowerShell process timed out after "+seconds+" seconds");
        }
        return output.get(10,TimeUnit.SECONDS);
    }
}