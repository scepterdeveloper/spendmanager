package com.everrich.spendmanager.multitenancy;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Multi-tenant connection provider that uses PostgreSQL schema switching.
 * This provider uses a single DataSource and switches schemas using
 * SET search_path for each tenant.
 */
@Component
public class SchemaBasedMultiTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private static final Logger logger = LoggerFactory.getLogger(SchemaBasedMultiTenantConnectionProvider.class);

    private final DataSource dataSource;

    public SchemaBasedMultiTenantConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection connection = getAnyConnection();
        try {
            // Set the search_path to the tenant schema, with public as fallback
            // This allows access to both tenant-specific tables and shared tables in public
            String searchPath;
            if (TenantContext.DEFAULT_SCHEMA.equals(tenantIdentifier)) {
                searchPath = TenantContext.DEFAULT_SCHEMA;
            } else {
                // Include both the tenant schema and public schema in search path
                // This allows access to shared tables (app_user, registration) from the public schema
                searchPath = tenantIdentifier + ", " + TenantContext.DEFAULT_SCHEMA;
            }
            
            connection.createStatement().execute("SET search_path TO " + searchPath);
            logger.debug("Switched to tenant schema: {} (search_path: {})", tenantIdentifier, searchPath);
        } catch (SQLException e) {
            logger.error("Failed to switch to tenant schema: {}", tenantIdentifier, e);
            throw new SQLException("Could not switch to schema: " + tenantIdentifier, e);
        }
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        try {
            // Reset to default schema before returning to pool
            connection.createStatement().execute("SET search_path TO " + TenantContext.DEFAULT_SCHEMA);
        } catch (SQLException e) {
            logger.warn("Failed to reset search_path to default schema", e);
        }
        connection.close();
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        return null;
    }
}