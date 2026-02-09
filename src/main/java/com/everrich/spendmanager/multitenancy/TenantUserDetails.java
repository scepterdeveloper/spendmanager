package com.everrich.spendmanager.multitenancy;

import com.everrich.spendmanager.entities.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/**
 * Custom UserDetails implementation that includes tenant information.
 * This extends Spring Security's User class to add tenant-specific data
 * that can be accessed during the user's session.
 */
public class TenantUserDetails extends User {

    private final String tenantId;
    private final Long userId;
    private final String fullName;
    private final UserRole userRole;

    public TenantUserDetails(String username, 
                             String password, 
                             Collection<? extends GrantedAuthority> authorities,
                             String tenantId,
                             Long userId,
                             String fullName,
                             UserRole userRole) {
        super(username, password, authorities);
        this.tenantId = tenantId;
        this.userId = userId;
        this.fullName = fullName;
        this.userRole = userRole;
    }

    /**
     * Gets the tenant ID (registration ID) for this user.
     * For SUPERADMIN users, this returns null or the default schema.
     *
     * @return The tenant ID
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Gets the user's database ID.
     *
     * @return The user ID
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * Gets the user's full name.
     *
     * @return The full name
     */
    public String getFullName() {
        return fullName;
    }

    /**
     * Gets the user's role.
     *
     * @return The user role
     */
    public UserRole getUserRole() {
        return userRole;
    }

    /**
     * Checks if this user is a SUPERADMIN.
     *
     * @return true if the user is a SUPERADMIN
     */
    public boolean isSuperAdmin() {
        return UserRole.SUPERADMIN.equals(userRole);
    }

    /**
     * Gets the schema name for this user's tenant.
     * SUPERADMIN users use the default (public) schema.
     *
     * @return The schema name
     */
    public String getSchemaName() {
        if (isSuperAdmin() || tenantId == null || tenantId.isEmpty()) {
            return TenantContext.DEFAULT_SCHEMA;
        }
        return TenantContext.TENANT_SCHEMA_PREFIX + tenantId;
    }
}