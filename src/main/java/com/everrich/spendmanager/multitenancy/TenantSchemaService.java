package com.everrich.spendmanager.multitenancy;

import com.everrich.spendmanager.entities.Registration;
import com.everrich.spendmanager.entities.TenantCreationStatus;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.repository.RegistrationRepository;
import com.everrich.spendmanager.repository.TransactionRepository;
import com.everrich.spendmanager.service.RedisAdapter;
import com.everrich.spendmanager.service.RedisAdapter.DocumentBatchItem;
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
 * Handles the asynchronous creation of schema and copying of default data.
 */
@Service
public class TenantSchemaService {

    private static final Logger logger = LoggerFactory.getLogger(TenantSchemaService.class);

    private final DataSource dataSource;
    private final MultiTenancyProperties multiTenancyProperties;
    private final RegistrationRepository registrationRepository;
    private final RedisAdapter redisAdapter;
    private final TransactionRepository transactionRepository;

    public TenantSchemaService(DataSource dataSource,
                               MultiTenancyProperties multiTenancyProperties,
                               RegistrationRepository registrationRepository,
                               RedisAdapter redisAdapter,
                               TransactionRepository transactionRepository) {
        this.dataSource = dataSource;
        this.multiTenancyProperties = multiTenancyProperties;
        this.registrationRepository = registrationRepository;
        this.redisAdapter = redisAdapter;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Synchronously creates a tenant schema for the given registration.
     * This method:
     * 1. Creates a new schema with the tenant prefix + registration ID
     * 2. Copies table structures (excluding configured tables)
     * 3. Copies default data for configured tables (e.g., categories)
     * 4. Initializes RAG configuration including vector store documents
     * 5. Updates the registration's tenant creation status
     * 
     * This synchronous version is used to avoid GCP Request Based billing issues
     * where async processing takes extremely long time.
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
            
            // Step 4: Initialize RAG configuration (ensure index exists)
            logger.info("Step 4/5: Initializing RAG configuration for tenant {}", registrationId);
            initializeRagConfiguration(registrationId);
            logger.info("Step 4/5: RAG configuration initialized for tenant {}", registrationId);
            
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
     * Initializes the RAG configuration for a new tenant.
     * This ensures the Redis vector index exists and creates RAG training documents
     * for any transactions that were copied from the default schema.
     * 
     * The RAG system uses a shared index with tenant-specific document prefixes.
     * This method:
     * 1. Ensures the shared transaction index exists in Redis
     * 2. Sets the TenantContext to the new tenant
     * 3. Queries the copied transactions from the tenant's schema
     * 4. Creates RAG documents in batch for improved performance
     *
     * @param tenantId The tenant/registration ID
     */
    private void initializeRagConfiguration(String tenantId) {
        String previousTenantId = null;
        try {
            // Ensure the shared Redis index exists
            // This is idempotent - if the index already exists, it will be skipped
            redisAdapter.createTransactionIndex();
            
            logger.info("RAG index ensured for tenant '{}'. Index: '{}', Document prefix: 'doc:{}:'",
                    tenantId, redisAdapter.getIndexName(), tenantId);
            
            // Set tenant context to the new tenant to query their transactions
            previousTenantId = TenantContext.getTenantId();
            TenantContext.setTenantId(tenantId);
            
            // Query all transactions that were copied to the new tenant's schema
            java.util.List<Transaction> transactions = transactionRepository.findAll();
            logger.info("Found {} transactions to process for RAG training in tenant '{}'", 
                    transactions.size(), tenantId);
            
            // Build batch items for all valid transactions
            List<DocumentBatchItem> batchItems = new ArrayList<>();
            int skippedCount = 0;
            
            for (Transaction transaction : transactions) {
                // Only process transactions that have a category and account assigned
                if (transaction.getCategoryEntity() != null && transaction.getAccount() != null) {
                    String categoryName = transaction.getCategoryEntity().getName();
                    String description = transaction.getDescription();
                    String operationName = transaction.getOperation().name(); // "PLUS" or "MINUS"
                    String accountName = transaction.getAccount().getName();
                    
                    batchItems.add(new DocumentBatchItem(categoryName, description, operationName, accountName));
                } else {
                    skippedCount++;
                }
            }
            
            // Create all documents in batch for significantly improved performance
            if (!batchItems.isEmpty()) {
                logger.info("Creating {} RAG documents in batch for tenant '{}'...", batchItems.size(), tenantId);
                int processedCount = redisAdapter.createDocumentsBatch(batchItems, tenantId);
                logger.info("RAG training data created for tenant '{}'. Processed: {}, Skipped: {}", 
                        tenantId, processedCount, skippedCount);
            } else {
                logger.info("No valid transactions found for RAG training in tenant '{}'. Skipped: {}", 
                        tenantId, skippedCount);
            }
            
        } catch (Exception e) {
            // Log warning but don't fail tenant creation - RAG can be initialized later
            logger.warn("Warning: Could not fully initialize RAG configuration for tenant '{}': {}. " +
                    "The tenant can still function, and RAG can be recreated via the RAG Config page.",
                    tenantId, e.getMessage());
        } finally {
            // Restore the previous tenant context
            if (previousTenantId != null) {
                TenantContext.setTenantId(previousTenantId);
            } else {
                TenantContext.clear();
            }
        }
    }
}