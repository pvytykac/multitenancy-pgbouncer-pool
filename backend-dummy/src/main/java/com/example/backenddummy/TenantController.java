package com.example.backenddummy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class TenantController {

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;
    private final String serviceName;

    public TenantController(DataSource dataSource,
                            @Value("${TABLE_NAME:items}") String tableName,
                            @Value("${SERVICE_NAME:order-service}") String serviceName) {
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
        String sql = "INSERT INTO \"" + tableName + "\" (name) VALUES (?)";
        jdbcTemplate.update(sql, name);
        return Map.of("status", "created");
    }
}
