package com.endpointposture.endpoint;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class EndpointNotFoundException extends RuntimeException {
    public EndpointNotFoundException(String id) {
        super("No endpoint found with id " + id);
    }
}