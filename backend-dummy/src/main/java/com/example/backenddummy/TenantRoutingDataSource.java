package com.example.backenddummy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@org.springframework.context.annotation.Primary
public class TenantRoutingDataSource extends AbstractRoutingDataSource {

    private final MeterRegistry meterRegistry;
    private final String host;
    private final String port;
    private final Map<TenantKey, HikariDataSource> pools = new ConcurrentHashMap<>();

    public TenantRoutingDataSource(MeterRegistry meterRegistry,
                                  @Value("${PGBouncer_HOST:pgbouncer}") String host,
                                  @Value("${PGBouncer_PORT:6432}") String port) {
        this.meterRegistry = meterRegistry;
        this.host = host;
        this.port = port;
        setTargetDataSources(new HashMap<>());
    }

    @Override
    protected Object determineCurrentLookupKey() {
        TenantContext ctx = TenantContext.get();
        if (ctx == null) {
            return null;
        }
        return new TenantKey(ctx.getCustomerId(), ctx.getTenantId(), ctx.getService());
    }

    @Override
    protected DataSource determineTargetDataSource() {
        Object key = determineCurrentLookupKey();
        if (key == null) {
            throw new IllegalStateException(
                    "No tenant context; DataSource is not available for non-tenant requests");
        }
        return resolveDataSource((TenantKey) key);
    }

    public HikariDataSource resolveDataSource(TenantKey key) {
        return pools.computeIfAbsent(key, k -> createPool(k));
    }

    private HikariDataSource createPool(TenantKey key) {
        TenantCredentialService.Credentials creds =
                TenantCredentialService.derive(key.customer(), key.tenant(), key.service());

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format(
                "jdbc:postgresql://%s:%s/%s?prepareThreshold=0&preparedStatementCacheQueries=0&ApplicationName=%s",
                host, port, creds.dbName(), creds.service()));
        config.setUsername(creds.username());
        config.setPassword(creds.password());
        config.setMinimumIdle(1);
        config.setMaximumPoolSize(2);
        config.setIdleTimeout(60000);
        config.setMaxLifetime(1800000);
        config.setConnectionTimeout(30000);
        config.setPoolName(creds.service() + "-" + key.customer() + "-" + key.tenant());
        config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));

        return new HikariDataSource(config);
    }
}
