package com.example.backenddummy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TenantCredentialServiceTest {

    @Test
    void derivesExpectedCredentials() {
        var creds = TenantCredentialService.derive("acme", "t1", "order-service");
        assertEquals("app_t1_orderservice", creds.username());
        assertEquals("t1", creds.password());
        assertEquals("cust_acme", creds.dbName());
        assertEquals("tenant_t1", creds.schema());
    }
}
