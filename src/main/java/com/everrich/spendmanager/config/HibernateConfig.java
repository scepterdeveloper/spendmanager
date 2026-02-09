package com.everrich.spendmanager.config;

import com.everrich.spendmanager.multitenancy.CurrentTenantIdentifierResolverImpl;
import com.everrich.spendmanager.multitenancy.SchemaBasedMultiTenantConnectionProvider;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Hibernate configuration for multi-tenancy support.
 * Configures Hibernate to use schema-based multi-tenancy.
 */
@Configuration
public class HibernateConfig {

    private final CurrentTenantIdentifierResolverImpl tenantIdentifierResolver;
    private final SchemaBasedMultiTenantConnectionProvider connectionProvider;

    public HibernateConfig(CurrentTenantIdentifierResolverImpl tenantIdentifierResolver,
                           SchemaBasedMultiTenantConnectionProvider connectionProvider) {
        this.tenantIdentifierResolver = tenantIdentifierResolver;
        this.connectionProvider = connectionProvider;
    }

    /**
     * Customizes Hibernate properties to enable multi-tenancy.
     */
    @Bean
    public HibernatePropertiesCustomizer hibernatePropertiesCustomizer() {
        return (Map<String, Object> hibernateProperties) -> {
            // Enable schema-based multi-tenancy
            hibernateProperties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
            hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantIdentifierResolver);
        };
    }
}