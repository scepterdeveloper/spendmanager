package com.everrich.spendmanager.entities;

/**
 * User roles in the application.
 */
public enum UserRole {
    /**
     * Owner of a tenant/registration. Has full access to tenant data.
     */
    OWNER,
    
    /**
     * Regular user within a tenant. Has limited access based on permissions.
     */
    USER,
    
    /**
     * Super administrator with access to the default schema.
     * Can manage all tenants and system-wide settings.
     * Created manually in the database, not through the registration flow.
     */
    SUPERADMIN
}
