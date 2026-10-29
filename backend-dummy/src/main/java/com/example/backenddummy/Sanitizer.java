package com.example.backenddummy;

import java.util.Locale;
import java.util.regex.Pattern;

public final class Sanitizer {

    private static final Pattern INPUT_PATTERN = Pattern.compile("[a-z0-9_]+");

    private Sanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        String result = sb.toString();
        if (result.isEmpty()) {
            throw new IllegalArgumentException("sanitized value is empty");
        }
        return result;
    }

    public static String sanitizeServiceName(String value) {
        return sanitize(value);
    }

    public static void validateLength(String name, int limit) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (name.length() > limit) {
            throw new IllegalArgumentException(
                    "derived name '" + name + "' exceeds " + limit + " characters");
        }
    }

    public static void validateLength(String name) {
        validateLength(name, 63);
    }

    public static void validateCustomerId(String customerId) {
        validateInput(customerId, "customerId");
    }

    public static void validateTenantId(String tenantId) {
        validateInput(tenantId, "tenantId");
    }

    private static void validateInput(String value, String field) {
        if (value == null || !INPUT_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " must match [a-z0-9_]+ but was: " + value);
        }
    }
}
