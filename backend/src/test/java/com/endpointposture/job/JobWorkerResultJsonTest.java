package com.endpointposture.job;

import com.endpointposture.hardware.HardwareHealthService;
import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.policy.PolicyService;
import com.endpointposture.posture.AssessmentService;
import com.endpointposture.posture.config.PostureAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** The RESULT_JSON line is the only contract between the agents and the worker. */
class JobWorkerResultJsonTest {

    JobWorker worker;

    @BeforeEach
    void setUp() {
        worker = new JobWorker(mock(JobService.class), mock(AssessmentService.class),
                mock(HardwareHealthService.class), new PostureAgentProperties(),
                new HardwareAgentProperties(), mock(PolicyService.class), new ObjectMapper());
    }

    @Test
    void noResultLineReturnsNull() {
        assertNull(worker.extractResultJson("Host: x\nSubmitted OK\n"));
    }

    @Test
    void parsesTheResultLineAmongOtherOutput() {
        JsonNode n = worker.extractResultJson("noise\nRESULT_JSON:{\"submitted\":true}\nmore\n");
        assertTrue(n.path("submitted").asBoolean());
    }

    @Test
    void lastResultLineWins() {
        JsonNode n = worker.extractResultJson(
                "RESULT_JSON:{\"submitted\":false}\nRESULT_JSON:{\"submitted\":true}\n");
        assertTrue(n.path("submitted").asBoolean());
    }

    @Test
    void windowsLineEndingsAndIndentationAreTolerated() {
        JsonNode n = worker.extractResultJson("  RESULT_JSON:{\"submitted\":true}\r\n");
        assertTrue(n.path("submitted").asBoolean());
    }

    @Test
    void malformedLastLineYieldsNullSoTheJobFailsRatherThanPasses() {
        assertNull(worker.extractResultJson("RESULT_JSON:{\"submitted\":true}\nRESULT_JSON:{broken\n"));
    }

    @Test
    void linesThatMerelyContainThePrefixAreIgnored() {
        assertNull(worker.extractResultJson("echo RESULT_JSON:{\"submitted\":true}\n"));
    }
}