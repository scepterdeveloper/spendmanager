package com.everrich.spendmanager.multitenancy;

import com.everrich.spendmanager.entities.Registration;
import com.everrich.spendmanager.entities.TenantCreationStatus;
import com.everrich.spendmanager.entities.VectorStoreTask;
import com.everrich.spendmanager.repository.RegistrationRepository;
import com.everrich.spendmanager.repository.VectorStoreTaskRepository;
import com.everrich.spendmanager.service.RedisAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Service responsible for creating and managing tenant schemas.
 * Handles the creation of schema and copying of default data.
 * 
 * Vector store initialization is now deferred to a background task processor
 * (VectorStoreTaskProcessor) to avoid blocking the registration process.
 */
@Service
public class TenantSchemaService {

    private static final Logger logger = LoggerFactory.getLogger(TenantSchemaService.class);

    private final DataSource dataSource;
    private final MultiTenancyProperties multiTenancyProperties;
    private final RegistrationRepository registrationRepository;
    private final RedisAdapter redisAdapter;
    private final VectorStoreTaskRepository vectorStoreTaskRepository;

    public TenantSchemaService(DataSource dataSource,
                               MultiTenancyProperties multiTenancyProperties,
                               RegistrationRepository registrationRepository,
                               RedisAdapter redisAdapter,
                               VectorStoreTaskRepository vectorStoreTaskRepository) {
        this.dataSource = dataSource;
        this.multiTenancyProperties = multiTenancyProperties;
        this.registrationRepository = registrationRepository;
        this.redisAdapter = redisAdapter;
        this.vectorStoreTaskRepository = vectorStoreTaskRepository;
    }

    /**
     * Creates a tenant schema for the given registration.
     * This method:
     * 1. Creates a new schema with the tenant prefix + registration ID
     * 2. Copies table structures (excluding configured tables)
     * 3. Copies default data for configured tables (e.g., categories)
     * 4. Parks a vector store initialization task for background processing
     * 5. Updates the registration's tenant creation status to COMPLETED
     * 
     * The vector store initialization is deferred to a background scheduler
     * (VectorStoreTaskProcessor) to avoid blocking the registration process.
     *
     * @param registration The registration for which to create the tenant schema
     */
    @Transactional
    public void createTenantSchema(Registration registration) {
        String registrationId = registration.getRegistrationId();
        String schemaName = multiTenancyProperties.getSchemaName(registrationId);
        
        logger.info("=== TENANT SCHEMA CREATION START === Registration: {}, Schema: {}", 
                registrationId, schemaName);

        try {
            logger.info("Step 1/5: Creating schema {}", schemaName);
            createSchema(schemaName);
            logger.info("Step 1/5: Schema {} created successfully", schemaName);
            
            logger.info("Step 2/5: Copying table structures to {}", schemaName);
            copyTableStructures(schemaName);
            logger.info("Step 2/5: Table structures copied successfully to {}", schemaName);
            
            logger.info("Step 3/5: Copying default data to {}", schemaName);
            copyDefaultData(schemaName);
            logger.info("Step 3/5: Default data copied successfully to {}", schemaName);
            
            // Step 4: Park vector store initialization task for background processing
            logger.info("Step 4/5: Parking vector store initialization task for tenant {}", registrationId);
            parkVectorStoreTask(registrationId);
            logger.info("Step 4/5: Vector store task parked for tenant {} (will be processed by scheduler)", registrationId);
            
            // Update status to COMPLETED - fetch fresh entity within transaction
            logger.info("Step 5/5: Updating tenant creation status to COMPLETED for registration ID: {}", registration.getId());
            Registration freshRegistration = registrationRepository.findById(registration.getId())
                    .orElseThrow(() -> new RuntimeException("Registration not found: " + registration.getId()));
            freshRegistration.setTenantCreationStatus(TenantCreationStatus.COMPLETED);
            registrationRepository.save(freshRegistration);
            registrationRepository.flush(); // Ensure immediate write to database
            logger.info("Step 5/5: Tenant creation status updated to COMPLETED for registration: {}", registrationId);
            
            logger.info("=== TENANT SCHEMA CREATION COMPLETE === Registration: {}, Schema: {}", 
                    registrationId, schemaName);
            
        } catch (Exception e) {
            logger.error("=== TENANT SCHEMA CREATION FAILED === Registration: {}, Schema: {}, Error: {}", 
                    registrationId, schemaName, e.getMessage(), e);
            try {
                Registration freshRegistration = registrationRepository.findById(registration.getId())
                        .orElse(null);
                if (freshRegistration != null) {
                    freshRegistration.setTenantCreationStatus(TenantCreationStatus.FAILED);
                    registrationRepository.save(freshRegistration);
                    registrationRepository.flush();
                    logger.info("Updated tenant creation status to FAILED for registration: {}", registrationId);
                }
            } catch (Exception updateEx) {
                logger.error("Failed to update tenant creation status to FAILED: {}", updateEx.getMessage(), updateEx);
            }
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

    /**
     * Parks a vector store initialization task for background processing.
     * This creates a task entry in the VECTOR_STORE_TASK table which will be
     * picked up by the VectorStoreTaskProcessor scheduler.
     * 
     * This approach decouples the expensive embedding generation from the
     * registration flow, allowing the user to complete registration immediately
     * while the RAG training data is created in the background.
     *
     * @param tenantId The tenant/registration ID
     */
    private void parkVectorStoreTask(String tenantId) {
        try {
            // Ensure the shared Redis index exists (this is fast and idempotent)
            redisAdapter.createTransactionIndex();
            
            logger.info("RAG index ensured for tenant '{}'. Index: '{}', Document prefix: 'doc:{}:'",
                    tenantId, redisAdapter.getIndexName(), tenantId);
            
            // Create a task entry for background processing
            VectorStoreTask task = VectorStoreTask.createForTenant(tenantId);
            vectorStoreTaskRepository.save(task);
            
            logger.info("Created vector store initialization task (ID: {}) for tenant '{}'. " +
                    "Task will be processed by VectorStoreTaskProcessor scheduler.",
                    task.getId(), tenantId);
            
        } catch (Exception e) {
            // Log warning but don't fail tenant creation - RAG can be initialized later
            logger.warn("Warning: Could not park vector store task for tenant '{}': {}. " +
                    "The tenant can still function, and RAG can be recreated via the RAG Config page.",
                    tenantId, e.getMessage());
        }
    }
}