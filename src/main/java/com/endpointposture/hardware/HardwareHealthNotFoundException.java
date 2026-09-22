package com.endpointposture.hardware;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class HardwareHealthNotFoundException extends RuntimeException {
    public HardwareHealthNotFoundException(String endpointId) {
        super("No hardware health report found for endpoint " + endpointId);
    }
}