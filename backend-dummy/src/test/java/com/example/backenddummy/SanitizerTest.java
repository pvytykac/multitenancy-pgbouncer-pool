package com.example.backenddummy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SanitizerTest {

    @Test
    void sanitizeLowercasesAndStripsNonAlphanumeric() {
        assertEquals("orderservice", Sanitizer.sanitize("Order-Service!"));
        assertEquals("abc123", Sanitizer.sanitize("ABC 123"));
    }

    @Test
    void validateLengthRejectsOverLong() {
        assertThrows(IllegalArgumentException.class, () -> Sanitizer.validateLength("a".repeat(64), 63));
    }

    @Test
    void validateCustomerIdRequiresCanonical() {
        Sanitizer.validateCustomerId("acme_1");
        assertThrows(IllegalArgumentException.class, () -> Sanitizer.validateCustomerId("Acme-1"));
    }

    @Test
    void validateTenantIdRequiresCanonical() {
        Sanitizer.validateTenantId("t1");
        assertThrows(IllegalArgumentException.class, () -> Sanitizer.validateTenantId("T1"));
    }
}
