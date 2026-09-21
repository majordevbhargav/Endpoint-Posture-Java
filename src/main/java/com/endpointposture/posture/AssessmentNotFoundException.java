package com.endpointposture.posture;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an endpoint has no assessments yet. Spring turns it into an
 * HTTP {@code 404 Not Found}.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class AssessmentNotFoundException extends RuntimeException {

    /**
     * @param endpointId the endpoint that has no assessment (used only in the message)
     */
    public AssessmentNotFoundException(String endpointId) {
        super("No assessment found for endpoint " + endpointId);
    }
}
