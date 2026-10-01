package com.endpointposture.ise.dto;

import com.endpointposture.audit.IseActionAudit;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary of an endpoint's latest ISE restriction state derived from its audit trail.
 */
public record IseActionStateResponse(
        UUID endpointId,
        String actionType,
        boolean succeeded,
        String operator,
        String detail,
        Instant occurredAt
) {
    public static IseActionStateResponse from(IseActionAudit audit) {
        return new IseActionStateResponse(
                audit.getEndpointId(),
                audit.getActionType(),
                audit.isSucceeded(),
                audit.getOperator(),
                audit.getDetail(),
                audit.getOccurredAt()
        );
    }
}
