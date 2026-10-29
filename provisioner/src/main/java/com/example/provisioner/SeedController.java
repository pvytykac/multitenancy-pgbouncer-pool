package com.example.provisioner;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class SeedController {

    private final ProvisioningService service;

    public SeedController(ProvisioningService service) {
        this.service = service;
    }

    @PostMapping("/api/seed")
    public ResponseEntity<?> seed(@RequestBody(required = false) Map<String, Object> body) {
        int customers = intParam(body, 1, "customers", "n", "customerCount", "customer_count");
        int tenants = intParam(body, 1, "tenants", "tenantsPerCustomer", "m", "tenantCount", "tenant_count");
        return ResponseEntity.ok(service.seed(customers, tenants));
    }

    private static int intParam(Map<String, Object> body, int def, String... keys) {
        if (body == null) {
            return def;
        }
        for (String key : keys) {
            Object v = body.get(key);
            if (v != null) {
                try {
                    return Integer.parseInt(String.valueOf(v));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return def;
    }
}
