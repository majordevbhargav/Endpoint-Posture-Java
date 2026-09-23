package com.endpointposture.ise;

/**
 * The two enforcement actions an operator can request against Cisco ISE.
 * Never selected automatically — always the direct result of a
 * {@code POST /api/v1/ise/enforcement/restrict} or {@code /clear} call.
 */
public enum EnforcementAction {
    RESTRICT,
    CLEAR
}