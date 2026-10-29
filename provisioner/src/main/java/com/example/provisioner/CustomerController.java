package com.example.provisioner;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final ProvisioningService service;

    public CustomerController(ProvisioningService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> body) {
        String name = stringValue(body, "name", "id", "customerId");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        try {
            String id = service.createCustomer(name);
            return ResponseEntity.ok(Map.of("customerId", id));
        } catch (DuplicateResourceException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.listCustomers();
    }

    @GetMapping("/{c}/tenants")
    public List<Map<String, Object>> tenants(@PathVariable String c) {
        return service.listTenants(c);
    }

    @PostMapping("/{c}/tenants")
    public ResponseEntity<?> createTenant(@PathVariable String c,
                                         @RequestBody(required = false) Map<String, Object> body) {
        String name = stringValue(body, "name", "id", "tenant", "tenantId");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        try {
            String id = service.createTenant(c, name);
            return ResponseEntity.ok(Map.of("tenantId", id));
        } catch (DuplicateResourceException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (NotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{c}")
    public ResponseEntity<Void> deleteCustomer(@PathVariable String c) {
        service.deleteCustomer(c);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{c}/tenants/{t}")
    public ResponseEntity<Void> deleteTenant(@PathVariable String c, @PathVariable String t) {
        service.deleteTenant(c, t);
        return ResponseEntity.noContent().build();
    }

    private static String stringValue(Map<String, Object> body, String... keys) {
        if (body == null) {
            return null;
        }
        for (String key : keys) {
            Object v = body.get(key);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return null;
    }
}
