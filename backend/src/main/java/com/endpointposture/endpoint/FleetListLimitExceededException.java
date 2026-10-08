package com.endpointposture.endpoint;

public class FleetListLimitExceededException extends RuntimeException {
    public FleetListLimitExceededException(long total, long max) {
        super(String.format("Fleet has %d endpoints, exceeding the maximum allowed (%d); use /page or /latest/batch", total, max));
    }
}
