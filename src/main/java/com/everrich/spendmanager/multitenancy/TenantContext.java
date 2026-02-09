package com.everrich.spendmanager.multitenancy;

/**
 * Thread-local storage for the current tenant identifier.
 * This context is set during request processing and used by Hibernate
 * to determine which schema to use for database operations.
 */
public final class TenantContext {

    /**
     * Default schema name used for shared tables (app_user, registration)
     * and for SUPERADMIN users.
     */
    public static final String DEFAULT_SCHEMA = "public";
    
    /**
     * Prefix used for tenant-specific schemas.
     * Schema names follow the pattern: tenant_<registrationId>
     */
    public static final String TENANT_SCHEMA_PREFIX = "tenant_";

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
        // Utility class - prevent instantiation
    }

    /**
     * Sets the current tenant identifier for the current thread.
     * 
     * @param tenantId The tenant identifier (typically the registration ID)
     */
    public static void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    /**
     * Gets the current tenant identifier for the current thread.
     * 
     * @return The current tenant identifier, or null if not set
     */
    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    /**
     * Clears the current tenant identifier.
     * Should be called at the end of request processing to prevent memory leaks.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /**
     * Gets the full schema name for the current tenant.
     * 
     * @return The schema name (e.g., "tenant_100001" or "public" if no tenant set)
     */
    public static String getCurrentSchema() {
        String tenantId = getTenantId();
        if (tenantId == null || tenantId.isEmpty() || DEFAULT_SCHEMA.equals(tenantId)) {
            return DEFAULT_SCHEMA;
        }
        return TENANT_SCHEMA_PREFIX + tenantId;
    }

    /**
     * Generates the schema name for a given tenant/registration ID.
     * 
     * @param registrationId The registration ID
     * @return The schema name (e.g., "tenant_100001")
     */
    public static String getSchemaNameForTenant(String registrationId) {
        if (registrationId == null || registrationId.isEmpty()) {
            return DEFAULT_SCHEMA;
        }
        return TENANT_SCHEMA_PREFIX + registrationId;
    }

    /**
     * Checks if the current context is set to the default schema.
     * 
     * @return true if using the default schema (no tenant or SUPERADMIN)
     */
    public static boolean isDefaultSchema() {
        String tenantId = getTenantId();
        return tenantId == null || tenantId.isEmpty() || DEFAULT_SCHEMA.equals(tenantId);
    }
}