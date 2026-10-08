package com.endpointposture.endpoint;

public class FleetListLimitExceededException extends RuntimeException {

    /** Standard message for list endpoints that have a paged alternative. */
    public FleetListLimitExceededException(long total, long max) {
        this(total, max, "use /page or /latest/batch");
    }

    /** Same cap, with a hint that fits the endpoint that refused. */
    public FleetListLimitExceededException(long total, long max, String hint) {
        super(String.format("Fleet has %d endpoints, exceeding the maximum allowed (%d); %s", total, max, hint));
    }
}