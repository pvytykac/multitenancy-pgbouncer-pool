# Task 02 — Phase 1: Backend dummy service (`backend-dummy` module)

> Prerequisites: `00-overview-and-conventions.md`, `01-spikes.md` findings. Timebox 2–3
> days. This task also creates the multi-module Maven parent (`pom.xml`) and the
> `backend-dummy` module.

## Objective

Build one Spring Boot image (`backend-dummy`) differentiated by `SERVICE_NAME` env, with
tenant context, locally derived credentials, a routing Hikari→PgBouncer DataSource,
Actuator/Prometheus metrics, and demo CRUD endpoints.

## Project scaffolding (do first)

Create `/home/paly/projects/pgbouncer-pooling-demo/` (or reuse) with:

```
pgbouncer-pooling-demo/
├── pom.xml                      # parent (multi-module)
├── backend-dummy/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/...
```

Parent `pom.xml`:
- groupId `com.example` (or chosen), artifactId `pgbouncer-pooling-demo`, packaging `pom`.
- modules: `backend-dummy`, `provisioner` (added in task 03).
- Spring Boot 4.1.2 (verify availability; fall back to latest 4.0.x if needed — see
  open question 5). Use `spring-boot-starter-parent` as parent.
- Java version per repo convention (suggest 21).
- Common dependency management (Lombok allowed).

`backend-dummy/pom.xml` dependencies:
- `spring-boot-starter-web`
- `spring-boot-starter-jdbc`
- `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`
- `postgresql` (JDBC driver)
- `hikari` (transitive via spring-boot-starter-jdbc, but ensure present)
- Lombok (optional, per convention)

Dockerfile (backend-dummy): multi-stage Maven build → JRE runtime; expose 8080.

## Configuration (`application.yml`)

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      # exclude HibernateJpaAutoConfiguration only if JPA not used (we use JdbcTemplate)
  jpa:
    hibernate:
      ddl-auto: none
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  health:
    db:
      enabled: false
server:
  port: 8080
```

- Routing DataSource is `@Primary` and created programmatically.
- Prefer `JdbcTemplate` over JPA; no startup connection validation.
- If JPA is used instead: pin `spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect`
  and set `spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false`; exclude
  `HibernateJpaAutoConfiguration` only when JPA is not used.

## Environment / config

- `SERVICE_NAME` (e.g. `order-service`)
- `TABLE_NAME` (target table for CRUD, e.g. `orders`)
- `PGBouncer_HOST` (default `pgbouncer`)
- `PGBouncer_PORT` (default `6432`)

## Components to implement

### 1. Sanitization utility

`Sanitizer` (duplicated in provisioner later; no shared module):
- `sanitize(value)`: lowercase, strip non-`[a-z0-9]` chars → `[a-z0-9]+`.
- `sanitizeServiceName` same.
- `validateLength(name, limit=63)` rejecting over-long *derived* names.
- `validateCustomerId`, `validateTenantId` ensure `[a-z0-9_]+` on input.

### 2. `TenantContext`

ThreadLocal holding `customerId`, `tenantId`, `dbName`, `schema`, `service`. Provide
getter/setter/clear. Used by filter and routing DS.

### 3. `TenantFilter` (OncePerRequestFilter)

Parses `X-Customer-Id` / `X-Tenant-Id` headers (or path vars) into `TenantContext`;
clears in `finally`. Non-tenant requests (actuator, probes) leave context null.

### 4. `TenantCredentialService`

Derives credentials locally:
- `username = "app_" + sanitizedTenant + "_" + sanitizedService`
- `password = tenantId` (simplified for demo)
- `dbName = "cust_" + sanitizedCustomer`
- `schema = "tenant_" + sanitizedTenant`
Validate inputs and derived name lengths (≤ 63).

### 5. `TenantRoutingDataSource extends AbstractRoutingDataSource` (`@Primary`)

- Lookup key = composite of `customer`, `tenant`, `service`. Use a **record** type (not
  string concatenation) to avoid delimiter collisions.
- `determineCurrentLookupKey()` returns key or `null` when unset.
- Override `determineTargetDataSource()` to avoid throwing for non-tenant requests
  (actuator, probes); non-tenant callers must not touch the DataSource. With no default
  target set, any caller that does invoke `getConnection()` without tenant context gets a
  clear `IllegalStateException`.
- `resolveDataSource(key)` lazily builds a Hikari pool, guarded against concurrent
  creation for the same key (`ConcurrentHashMap.computeIfAbsent` with a lock) so duplicate
  pools are not created under load:
  ```
  jdbc:postgresql://${PGBouncer_HOST:pgbouncer}:${PGBouncer_PORT:6432}/<dbName>
    ?prepareThreshold=0&preparedStatementCacheQueries=0&ApplicationName=<service>
  username = derived user
  password = derived password
  minimumIdle = 1
  maximumPoolSize = 2
  idleTimeout = 60000
  maxLifetime = 1800000
  connectionTimeout = 30000
  poolName = <service>-<customer>-<tenant>
  ```
- Register each created pool with `MeterRegistry` (Micrometer Hikari binder) so
  `hikaricp_*` metrics are published.
- No pool eviction in demo (decision: skip LRU; pools are small and bounded by JVM
  memory; acceptable for demo scale).

### 6. `TenantRepository` / endpoints

Controller `TenantController` (or separate):
- `GET /api/v1/whoami` → runs `SELECT 1` + reads `current_schema()`, `current_user`;
  returns `{service, customer, tenant, db, user, schema}`. `db` = `current_database()`.
- `GET /api/v1/items` → list rows from the service's table (TABLE_NAME) in tenant schema.
- `POST /api/v1/items` → insert a row (`name` from body) into TABLE_NAME.

Use `JdbcTemplate`; set search_path not needed (role default handles it), but queries
should reference table by name (role's search_path resolves). For safety, quote
identifiers.

### 7. Actuator

`/actuator/health`, `/actuator/prometheus` exposed. `management.health.db.enabled=false`.

## Tests

- Unit: sanitizer validation (length, charset, derived names).
- Unit: credential derivation matches expected (`app_t1_orderservice`, password `t1`).
- Integration (optional, Testcontainers Postgres): routing DS creates pool; whoami returns
  correct db/user/schema. May be deferred if cluster unavailable.
- Concurrency test: parallel requests for same key create one pool (computeIfAbsent).

## Acceptance criteria

- `mvn -pl backend-dummy -am package` builds.
- Docker image builds.
- Running with env vars, `GET /actuator/health` returns 200 without tenant context.
- With `X-Customer-Id: acme`, `X-Tenant-Id: t1`, `GET /api/v1/whoami` returns
  `{db: cust_acme, user: app_t1_orderservice, schema: tenant_t1}` when pointed at a
  provisioned DB/PgBouncer.
- Hikari metrics visible at `/actuator/prometheus`.

## Risks / notes

- ThreadLocal must be cleared to avoid leaks across pooled request threads.
- Composite key record must implement hashCode/equals (record does).
- No default DataSource → any non-tenant getConnection throws; ensure no startup path
  calls it (health db disabled, no JPA validation).
