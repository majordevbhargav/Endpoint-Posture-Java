package com.endpointposture.security;

/**
 * Roles a platform user can hold. Higher roles include the abilities of lower
 * ones, but this is enforced with explicit {@code @PreAuthorize} rules (not a
 * hierarchy bean) so every rule is visible at the call site.
 *
 * <p>The {@code ROLE_AGENT} authority granted by {@link PostureApiKeyFilter} is
 * <em>not</em> a user role: it is attached to a request only and never stored
 * in {@code app_user}.</p>
 */
public enum Role {
    ADMIN, OPERATOR, ANALYST, VIEWER
}