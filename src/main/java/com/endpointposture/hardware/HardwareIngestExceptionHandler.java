package com.endpointposture.hardware;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a rejected hardware-health report (missing MAC, or missing
 * CPU/memory/storage data that can't be scored — see
 * {@link HardwareIngestService#ingest}) into a clean {@code 400 Bad Request}
 * instead of an unhandled {@code 500}.
 */
@RestControllerAdvice(assignableTypes = { HardwareIngestController.class })
public class HardwareIngestExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleBadInput(IllegalArgumentException e) {
        return e.getMessage();
    }
}