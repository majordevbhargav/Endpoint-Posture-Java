package com.endpointposture.endpoint;

import com.endpointposture.hardware.HardwareFleetController;
import com.endpointposture.hardware.HardwareHealthService;
import com.endpointposture.hardware.HardwareQueryService;
import com.endpointposture.posture.AssessmentService;
import com.endpointposture.posture.PostureFleetController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FleetListMaxEndpointsTest {

    private final EndpointRepository endpointRepository = mock(EndpointRepository.class);
    private final EndpointService endpointService = mock(EndpointService.class);
    private final EndpointQueryService endpointQueryService = mock(EndpointQueryService.class);
    private final AssessmentService assessmentService = mock(AssessmentService.class);
    private final HardwareHealthService hardwareService = mock(HardwareHealthService.class);
    private final HardwareQueryService hardwareQueryService = mock(HardwareQueryService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new EndpointController(endpointService, endpointQueryService, endpointRepository, 50),
            new PostureFleetController(assessmentService, endpointRepository, 50),
            new HardwareFleetController(hardwareService, hardwareQueryService, endpointRepository, 50)
    ).setControllerAdvice(new FleetListLimitExceptionHandler()).build();

    @Test
    void endpointsListAllThrows400WhenExceedingCap() throws Exception {
        when(endpointRepository.count()).thenReturn(51L);

        mvc.perform(get("/api/v1/endpoints"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Fleet has 51 endpoints, exceeding the maximum allowed (50); use /page or /latest/batch"));
    }

    @Test
    void postureLatestThrows400WhenExceedingCap() throws Exception {
        when(endpointRepository.count()).thenReturn(51L);

        mvc.perform(get("/api/v1/posture/latest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Fleet has 51 endpoints, exceeding the maximum allowed (50); use /page or /latest/batch"));
    }

    @Test
    void hardwareLatestThrows400WhenExceedingCap() throws Exception {
        when(endpointRepository.count()).thenReturn(51L);

        mvc.perform(get("/api/v1/hardware-health/latest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Fleet has 51 endpoints, exceeding the maximum allowed (50); use /page or /latest/batch"));
    }

    @Test
    void endpointsListAllSucceedsWhenWithinCap() throws Exception {
        when(endpointRepository.count()).thenReturn(50L);

        mvc.perform(get("/api/v1/endpoints"))
                .andExpect(status().isOk());
    }
}
