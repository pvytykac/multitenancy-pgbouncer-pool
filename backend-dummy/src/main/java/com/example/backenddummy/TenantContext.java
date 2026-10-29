package com.example.backenddummy;

public class TenantContext {

    private String customerId;
    private String tenantId;
    private String dbName;
    private String schema;
    private String service;

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    public TenantContext() {
    }

    public TenantContext(String customerId, String tenantId, String dbName, String schema, String service) {
        this.customerId = customerId;
        this.tenantId = tenantId;
        this.dbName = dbName;
        this.schema = schema;
        this.service = service;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public static void set(TenantContext context) {
        CURRENT.set(context);
    }

    public static TenantContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
