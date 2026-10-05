package com.endpointposture.posture;

import com.endpointposture.posture.dto.AssessmentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** S3: "never assessed" is a 204, so the frontend no longer needs the whole history to find out. */
class PostureQueryControllerTest {

    private final AssessmentService service = mock(AssessmentService.class);
    private final PostureQueryController controller = new PostureQueryController(service);
    private final UUID id = UUID.randomUUID();

    @Test
    void neverAssessedIs204WithNoBody() {
        when(service.findLatestForEndpoint(id)).thenReturn(Optional.empty());

        ResponseEntity<AssessmentResponse> res = controller.latest(id);

        assertEquals(204, res.getStatusCode().value());
        assertNull(res.getBody());
    }

    @Test
    void anExistingAssessmentIs200WithTheBody() {
        AssessmentResponse a = new AssessmentResponse(UUID.randomUUID(), id, null,
                AssessmentStatus.COMPLIANT, "ok", Instant.now(), Instant.now(), List.of());
        when(service.findLatestForEndpoint(id)).thenReturn(Optional.of(a));

        ResponseEntity<AssessmentResponse> res = controller.latest(id);

        assertEquals(200, res.getStatusCode().value());
        assertEquals(a, res.getBody());
    }
}