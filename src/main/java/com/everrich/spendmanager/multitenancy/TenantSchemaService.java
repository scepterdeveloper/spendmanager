package com.everrich.spendmanager.multitenancy;

import com.everrich.spendmanager.entities.Registration;
import com.everrich.spendmanager.entities.TenantCreationStatus;
import com.everrich.spendmanager.repository.RegistrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Service responsible for creating and managing tenant schemas.
 * Handles the asynchronous creation of schema and copying of default data.
 */
@Service
public class TenantSchemaService {

    private static final Logger logger = LoggerFactory.getLogger(TenantSchemaService.class);

    private final DataSource dataSource;
    private final MultiTenancyProperties multiTenancyProperties;
    private final RegistrationRepository registrationRepository;

    public TenantSchemaService(DataSource dataSource,
                               MultiTenancyProperties multiTenancyProperties,
                               RegistrationRepository registrationRepository) {
        this.dataSource = dataSource;
        this.multiTenancyProperties = multiTenancyProperties;
        this.registrationRepository = registrationRepository;
    }

    /**
     * Asynchronously creates a tenant schema for the given registration.
     * This method:
     * 1. Creates a new schema with the tenant prefix + registration ID
     * 2. Copies table structures (excluding configured tables)
     * 3. Copies default data for configured tables (e.g., categories)
     * 4. Updates the registration's tenant creation status
     *
     * @param registration The registration for which to create the tenant schema
     */
    @Async
    public void createTenantSchemaAsync(Registration registration) {
        String registrationId = registration.getRegistrationId();
        String schemaName = multiTenancyProperties.getSchemaName(registrationId);
        
        logger.info("Starting async tenant schema creation for registration: {}, schema: {}", 
                registrationId, schemaName);

        try {
            createSchema(schemaName);
            copyTableStructures(schemaName);
            copyDefaultData(schemaName);
            
            // Update status to COMPLETED
            updateTenantCreationStatus(registration.getId(), TenantCreationStatus.COMPLETED);
            logger.info("Successfully created tenant schema: {}", schemaName);
            
        } catch (Exception e) {
            logger.error("Failed to create tenant schema: {}", schemaName, e);
            updateTenantCreationStatus(registration.getId(), TenantCreationStatus.FAILED);
        }
    }

    /**
     * Creates the tenant schema.
     */
    private void createSchema(String schemaName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement()) {
            
            String sql = "CREATE SCHEMA IF NOT EXISTS " + schemaName;
            stmt.execute(sql);
            logger.debug("Created schema: {}", schemaName);
        }
    }

    /**
     * Copies table structures from the public schema to the tenant schema.
     * Excludes tables configured in multiTenancyProperties.excludedTables.
     */
    private void copyTableStructures(String schemaName) throws SQLException {
        List<String> tables = getTenantTables();
        
        try (Connection connection = dataSource.getConnection()) {
            for (String tableName : tables) {
                copyTableStructure(connection, schemaName, tableName);
            }
        }
    }

    /**
     * Gets the list of tables that should be created in tenant schemas.
     */
    private List<String> getTenantTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getTables(null, "public", "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    // Exclude configured tables
                    if (!multiTenancyProperties.isExcludedTable(tableName)) {
                        tables.add(tableName);
                    }
                }
            }
        }
        
        logger.debug("Tenant tables to create: {}", tables);
        return tables;
    }

    /**
     * Copies a single table structure from public schema to tenant schema.
     * Uses PostgreSQL's LIKE clause to copy the structure including constraints.
     */
    private void copyTableStructure(Connection connection, String schemaName, String tableName) 
            throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Create table with same structure as the original
            // INCLUDING ALL copies: constraints, indexes, defaults, etc.
            String sql = String.format(
                    "CREATE TABLE IF NOT EXISTS %s.%s (LIKE public.%s INCLUDING ALL)",
                    schemaName, tableName, tableName);
            stmt.execute(sql);
            logger.debug("Created table {}.{}", schemaName, tableName);
        }
    }

    /**
     * Copies default data to tables configured in multiTenancyProperties.tablesWithDefaultData.
     */
    private void copyDefaultData(String schemaName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            for (String tableName : multiTenancyProperties.getTablesWithDefaultData()) {
                // Check if this table exists in the tenant schema
                if (tableExists(connection, schemaName, tableName)) {
                    copyTableData(connection, schemaName, tableName);
                }
            }
        }
    }

    /**
     * Checks if a table exists in the specified schema.
     */
    private boolean tableExists(Connection connection, String schemaName, String tableName) 
            throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getTables(null, schemaName, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    /**
     * Copies all data from a public schema table to the tenant schema.
     */
    private void copyTableData(Connection connection, String schemaName, String tableName) 
            throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // First, delete any existing data (in case of re-run)
            String deleteSql = String.format("DELETE FROM %s.%s", schemaName, tableName);
            stmt.execute(deleteSql);
            
            // Copy data from public schema
            String copySql = String.format(
                    "INSERT INTO %s.%s SELECT * FROM public.%s",
                    schemaName, tableName, tableName);
            int rowsCopied = stmt.executeUpdate(copySql);
            logger.debug("Copied {} rows to {}.{}", rowsCopied, schemaName, tableName);
            
            // Reset sequence if table has an id column with sequence
            resetSequenceIfExists(connection, schemaName, tableName);
        }
    }

    /**
     * Resets the sequence for a table's ID column if it exists.
     * This ensures new records get IDs starting after the copied data.
     */
    private void resetSequenceIfExists(Connection connection, String schemaName, String tableName) {
        try (Statement stmt = connection.createStatement()) {
            // Try to reset the sequence - this will fail silently if no sequence exists
            String sequenceName = tableName + "_id_seq";
            String sql = String.format(
                    "SELECT setval('%s.%s', COALESCE((SELECT MAX(id) FROM %s.%s), 0) + 1, false)",
                    schemaName, sequenceName, schemaName, tableName);
            stmt.execute(sql);
            logger.debug("Reset sequence {}.{}", schemaName, sequenceName);
        } catch (SQLException e) {
            // Sequence might not exist for this table, which is fine
            logger.debug("No sequence to reset for table {}.{}", schemaName, tableName);
        }
    }

    /**
     * Updates the tenant creation status for a registration.
     */
    @Transactional
    public void updateTenantCreationStatus(Long registrationId, TenantCreationStatus status) {
        registrationRepository.findById(registrationId).ifPresent(registration -> {
            registration.setTenantCreationStatus(status);
            registrationRepository.save(registration);
            logger.info("Updated tenant creation status for registration {}: {}", 
                    registration.getRegistrationId(), status);
        });
    }

    /**
     * Checks if a tenant schema exists.
     *
     * @param registrationId The registration ID
     * @return true if the schema exists
     */
    public boolean schemaExists(String registrationId) {
        String schemaName = multiTenancyProperties.getSchemaName(registrationId);
        
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getSchemas(null, schemaName)) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.error("Error checking if schema exists: {}", schemaName, e);
            return false;
        }
    }

    /**
     * Drops a tenant schema and all its contents.
     * Use with caution - this is destructive!
     *
     * @param registrationId The registration ID
     */
    public void dropSchema(String registrationId) throws SQLException {
        String schemaName = multiTenancyProperties.getSchemaName(registrationId);
        
        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement()) {
            
            String sql = "DROP SCHEMA IF EXISTS " + schemaName + " CASCADE";
            stmt.execute(sql);
            logger.warn("Dropped schema: {}", schemaName);
        }
    }
}