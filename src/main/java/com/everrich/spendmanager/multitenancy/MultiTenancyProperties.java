package com.everrich.spendmanager.multitenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for multi-tenancy settings.
 * These can be customized in application.properties or application.yml.
 */
@Component
@ConfigurationProperties(prefix = "app.multitenancy")
public class MultiTenancyProperties {

    /**
     * Whether multi-tenancy is enabled.
     * Default: true
     */
    private boolean enabled = true;

    /**
     * List of table names that should NOT be included in tenant-specific schemas.
     * These tables remain in the default (public) schema and are shared across tenants.
     * Default: app_user, registration
     */
    private List<String> excludedTables = new ArrayList<>(List.of(
            "app_user",
            "registration",
            "registration_id_seq"
    ));

    /**
     * List of table names that should be copied WITH their data to new tenant schemas.
     * For example, default categories, accounts, etc. that every tenant should start with.
     * Default: account, category, saved_insight, statement, transaction
     */
    private List<String> tablesWithDefaultData = new ArrayList<>(List.of(
            "account",
            "category",
            "saved_insight",
            "statement",
            "transaction"
    ));

    /**
     * The default schema name.
     * Default: public
     */
    private String defaultSchema = "public";

    /**
     * Prefix for tenant schema names.
     * Schema names will be: {prefix}{registrationId}
     * Default: tenant_
     */
    private String schemaPrefix = "tenant_";

    // Getters and Setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getExcludedTables() {
        return excludedTables;
    }

    public void setExcludedTables(List<String> excludedTables) {
        this.excludedTables = excludedTables;
    }

    public List<String> getTablesWithDefaultData() {
        return tablesWithDefaultData;
    }

    public void setTablesWithDefaultData(List<String> tablesWithDefaultData) {
        this.tablesWithDefaultData = tablesWithDefaultData;
    }

    public String getDefaultSchema() {
        return defaultSchema;
    }

    public void setDefaultSchema(String defaultSchema) {
        this.defaultSchema = defaultSchema;
    }

    public String getSchemaPrefix() {
        return schemaPrefix;
    }

    public void setSchemaPrefix(String schemaPrefix) {
        this.schemaPrefix = schemaPrefix;
    }

    /**
     * Checks if a table is excluded from tenant schemas.
     * 
     * @param tableName The table name to check
     * @return true if the table should not be in tenant schemas
     */
    public boolean isExcludedTable(String tableName) {
        return excludedTables.stream()
                .anyMatch(excluded -> excluded.equalsIgnoreCase(tableName));
    }

    /**
     * Checks if a table should have its data copied to new tenant schemas.
     * 
     * @param tableName The table name to check
     * @return true if the table's data should be copied
     */
    public boolean shouldCopyData(String tableName) {
        return tablesWithDefaultData.stream()
                .anyMatch(table -> table.equalsIgnoreCase(tableName));
    }

    /**
     * Generates the full schema name for a tenant.
     * 
     * @param registrationId The registration ID
     * @return The schema name (e.g., "tenant_100001")
     */
    public String getSchemaName(String registrationId) {
        return schemaPrefix + registrationId;
    }
}