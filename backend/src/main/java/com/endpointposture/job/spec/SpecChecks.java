package com.endpointposture.job.spec;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

final class SpecChecks {
    private SpecChecks() {}

    // A target cannot begin with '-' (PowerShell parameter confusion) or contain whitespace/shell syntax.
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,252}$");
    private static final Pattern MAC = Pattern.compile("^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$");

    static String host(String value, String field) {
        if (value == null || !HOST.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a valid host name or IPv4 address: " + value);
        }
        return value;
    }

    static String mac(String value) {
        if (value == null || !MAC.matcher(value).matches()) {
            throw new IllegalArgumentException("mac is not a valid MAC address: " + value);
        }
        return value;
    }

    static int positive(int value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive: " + value);
        return value;
    }

    /** '|' is the list delimiter and '"' breaks Windows argument quoting. */
    static List<String> patterns(List<String> values) {
        List<String> source = Objects.requireNonNullElse(values, List.of());
        for (String value : source) {
            if (value == null || value.isBlank() || value.indexOf('|') >= 0 || value.indexOf('"') >= 0
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Unsupported policy pattern: " + value);
            }
        }
        return List.copyOf(source);
    }
}
