package com.endpointposture.warranty;

/**
 * Thrown by {@link WarrantyService} when a CSV file cannot be parsed.
 * Caught by {@link WarrantyController} and returned as a 400 response.
 */
public class WarrantyParseException extends RuntimeException {
    public WarrantyParseException(String message) { super(message); }
    public WarrantyParseException(String message, Throwable cause) { super(message, cause); }
}
