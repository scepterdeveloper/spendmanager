package com.everrich.spendmanager.multitenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Hibernate tenant identifier resolver.
 * This class is responsible for determining the current tenant identifier
 * for each database operation.
 */
@Component
public class CurrentTenantIdentifierResolverImpl implements CurrentTenantIdentifierResolver<String> {

    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenantId = TenantContext.getTenantId();
        
        // If no tenant is set, return the default schema
        if (tenantId == null || tenantId.isEmpty()) {
            return TenantContext.DEFAULT_SCHEMA;
        }
        
        // If it's explicitly set to the default schema, return it
        if (TenantContext.DEFAULT_SCHEMA.equals(tenantId)) {
            return TenantContext.DEFAULT_SCHEMA;
        }
        
        // Return the tenant-specific schema name
        return TenantContext.TENANT_SCHEMA_PREFIX + tenantId;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        // Return true to validate that all sessions belong to the current tenant
        return true;
    }
}