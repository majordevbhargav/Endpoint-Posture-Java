package com.endpointposture.endpoint;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an endpoint lookup by ID finds nothing. Spring turns it into
 * an HTTP {@code 404 Not Found}.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class EndpointNotFoundException extends RuntimeException {

    /**
     * @param id the ID that was looked up (used only in the message)
     */
    public EndpointNotFoundException(String id) {
        super("No endpoint found with id " + id);
    }
}
