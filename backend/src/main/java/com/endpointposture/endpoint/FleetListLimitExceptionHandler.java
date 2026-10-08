package com.endpointposture.endpoint;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class FleetListLimitExceptionHandler {

    @ExceptionHandler(FleetListLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleFleetLimit(FleetListLimitExceededException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }
}
