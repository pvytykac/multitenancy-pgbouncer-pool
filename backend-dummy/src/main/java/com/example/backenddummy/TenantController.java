package com.example.backenddummy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {org.springframework.web.bind.annotation.RequestMethod.GET, org.springframework.web.bind.annotation.RequestMethod.POST, org.springframework.web.bind.annotation.RequestMethod.PUT, org.springframework.web.bind.annotation.RequestMethod.DELETE, org.springframework.web.bind.annotation.RequestMethod.OPTIONS})
public class TenantController {

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;
    private final String serviceName;

    public TenantController(DataSource dataSource,
                            @Value("${TABLE_NAME:items}") String tableName,
                            @Value("${SERVICE_NAME:order-service}") String serviceName) {
        if (tableName == null || !tableName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("TABLE_NAME must be a simple identifier");
        }
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.tableName = tableName;
        this.serviceName = serviceName;
    }

    @GetMapping("/whoami")
    public Map<String, Object> whoami() {
        TenantContext ctx = TenantContext.get();
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        String db = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        String user = jdbcTemplate.queryForObject("SELECT current_user", String.class);
        String schema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);

        return Map.of(
                "service", serviceName,
                "customer", ctx != null ? ctx.getCustomerId() : null,
                "tenant", ctx != null ? ctx.getTenantId() : null,
                "db", db,
                "user", user,
                "schema", schema
        );
    }

    @GetMapping("/items")
    public List<Map<String, Object>> items() {
        String sql = "SELECT * FROM \"" + tableName + "\"";
        return jdbcTemplate.queryForList(sql);
    }

    @PostMapping("/items")
    public Map<String, Object> createItem(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        UUID id = UUID.randomUUID();
        String sql = "INSERT INTO \"" + tableName + "\" (id, name) VALUES (?, ?)";
        jdbcTemplate.update(sql, id, name);
        return Map.of("status", "created", "id", id.toString());
    }

    @PutMapping("/items/{id}")
    public Map<String, Object> updateItem(@PathVariable String id, @RequestBody Map<String, String> body) {
        String name = body.get("name");
        String sql = "UPDATE \"" + tableName + "\" SET name = ? WHERE id = ?::uuid";
        int updated = jdbcTemplate.update(sql, name, id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found");
        }
        return Map.of("status", "updated", "id", id);
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@PathVariable String id) {
        String sql = "DELETE FROM \"" + tableName + "\" WHERE id = ?::uuid";
        int deleted = jdbcTemplate.update(sql, id);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found");
        }
    }
}
