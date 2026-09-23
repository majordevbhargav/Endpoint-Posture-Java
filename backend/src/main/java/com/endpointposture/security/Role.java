package com.endpointposture.security;

/**
 * Roles a platform user can hold.
 *
 * <p>Only {@code ADMIN} exists for now. The other roles from the design
 * documents are added when there is a second role that is actually
 * enforced differently somewhere in the app, not speculatively.</p>
 *
 * <p>Note: the {@code ROLE_AGENT} authority granted by
 * {@link PostureApiKeyFilter} is <em>not</em> a user role. It is attached to
 * a request only, never stored in {@code app_user}, so it does not belong in
 * this enum.</p>
 */
public enum Role {
    ADMIN
    // SECURITY_ANALYST, NETWORK_OPERATOR, VIEWER - added when there's
    // an actual second role to enforce differently, not speculatively.
}
