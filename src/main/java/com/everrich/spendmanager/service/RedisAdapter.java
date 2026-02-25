package com.everrich.spendmanager.service;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.output.IntegerOutput;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import java.util.OptionalInt;

import com.everrich.spendmanager.multitenancy.TenantContext;

@Service
public class RedisAdapter {

    @Value("${com.everrich.properties.redis.uri}")
    private String REDIS_URI;
    @Value("${com.everrich.properties.redis.transactionindex}")
    private String INDEX_NAME;
    @Value("${com.everrich.properties.gcpprojectid}")
    private String GCP_PROJECT_ID;
    @Value("${com.everrich.properties.gcplocation}")
    private String GCP_LOCATION;
    @Value("${com.everrich.properties.gcpendpoint}")
    private String GCP_ENDPOINT;
    @Value("${com.everrich.properties.embeddingmodel}")
    private String EMBEDDING_MODEL_NAME;
    @Value("${com.everrich.properties.targetvectordimension}")
    private int TARGET_VECTOR_DIMENSION;

    private final RedisClient redisClient;

    private static final Logger log = LoggerFactory.getLogger(RedisAdapter.class);

    // Define the custom commands (copied from RedisTestController)
    public static final ProtocolKeyword FT_SEARCH_COMMAND = new ProtocolKeyword() {
        @Override
        public byte[] getBytes() {
            return "FT.SEARCH".getBytes(StandardCharsets.US_ASCII);
        }
    };

    private static final ProtocolKeyword HSET_COMMAND = new ProtocolKeyword() {
        @Override
        public byte[] getBytes() {
            return "HSET".getBytes(StandardCharsets.US_ASCII);
        }
    };

    public RedisAdapter(RedisClient redisClient) {
        this.redisClient = redisClient;
    }

    private byte[] convertFloatsToRedisByteArray(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * Float.BYTES);
        // RedisSearch expects little-endian byte order for vector data
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    public void createTransactionIndex() {

        StatefulRedisConnection<String, String> connection = null;
        RedisCommands<String, String> syncCommands = null;
        ProtocolKeyword ftCreateCommand = null;
        CommandArgs<String, String> argsBuilder = new CommandArgs<>(StringCodec.UTF8);

        try {
            log.info("Creating index in Redis...Index Name: " + INDEX_NAME);
            connection = redisClient.connect();
            syncCommands = connection.sync();
            ftCreateCommand = new ProtocolKeyword() {
                public byte[] getBytes() {
                    return "FT.CREATE".getBytes(StandardCharsets.UTF_8);
                }
            };

            argsBuilder = new CommandArgs<>(StringCodec.UTF8);
            argsBuilder.add(INDEX_NAME);
            argsBuilder.add("ON").add("HASH");
            argsBuilder.add("PREFIX").add(1).add("doc:");
            argsBuilder.add("SCHEMA");
            argsBuilder.add("content_payload").add("TEXT");
            argsBuilder.add("category").add("TAG");
            argsBuilder.add("operation").add("TAG");
            argsBuilder.add("account").add("TAG");
            argsBuilder.add("tenant").add("TAG");  // Multi-tenancy support: tenant ID for filtering
            argsBuilder.add("vector").add("AS").add("vector");
            argsBuilder.add("VECTOR");
            argsBuilder.add("FLAT");
            argsBuilder.add("6");
            argsBuilder.add("TYPE").add("FLOAT32");
            argsBuilder.add("DIM").add(this.TARGET_VECTOR_DIMENSION);
            argsBuilder.add("DISTANCE_METRIC").add("COSINE");

        } catch (Exception e) {
            log.error("Error connecting to Redis: " + e.getMessage(), e);
            return;
        }

        try {

            CommandOutput<String, String, String> output = new StatusOutput<>(StringCodec.UTF8);
            String result = syncCommands.dispatch(ftCreateCommand, output, argsBuilder);
            log.info("FT.CREATE command executed.");
            log.info("Result: " + result);

        } catch (Exception e) {
            log.info("Index already exists, or creation failed.");
        } finally {
            connection.close();
            log.info("\nConnection closed.");
        }
    }

    private float[] generateEmbeddingVector(String content, String mode) throws Exception {

        String taskType;
        if ("create".equalsIgnoreCase(mode)) {
            taskType = "RETRIEVAL_DOCUMENT";
        } else if ("query".equalsIgnoreCase(mode)) {
            taskType = "RETRIEVAL_QUERY";
        } else {
            System.err.println("Warning: Unknown embedding mode provided. Using RETRIEVAL_QUERY.");
            taskType = "RETRIEVAL_QUERY";
        }

        try {
            List<List<Float>> embeddingList = EmbeddingsCreator.predictTextEmbeddings(
                    this.GCP_ENDPOINT,
                    this.GCP_PROJECT_ID,
                    this.EMBEDDING_MODEL_NAME,
                    List.of(content),
                    taskType,
                    OptionalInt.of(TARGET_VECTOR_DIMENSION));

            List<Float> floatList = embeddingList.get(0);

            if (floatList.size() != TARGET_VECTOR_DIMENSION) {
                throw new Exception("Model returned dimension (" + floatList.size()
                        + ") which does not match expected dimension (" + TARGET_VECTOR_DIMENSION + ").");
            }

            //log.info("Real Embedding generated (Task: " + taskType + ", Size: " + floatList.size() + ").");

            float[] vectorFloats = new float[TARGET_VECTOR_DIMENSION];
            for (int i = 0; i < TARGET_VECTOR_DIMENSION; i++) {
                vectorFloats[i] = floatList.get(i);
            }

            return vectorFloats;

        } catch (Exception e) {
            System.err.println("Error calling Gemini/VertexAI SDK for embedding. Error: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Creates a document in Redis for RAG-based category determination.
     * Uses TenantContext to get the current tenant ID for multi-tenancy support.
     * 
     * @param categoryName The category name for this transaction pattern
     * @param description The transaction description
     * @param operation The operation type (e.g., PLUS, MINUS)
     * @param accountName The account name
     * @return The Redis key of the created document, or null on error
     */
    public String createDocument(String categoryName, String description, String operation, String accountName) {
        // Get tenant ID from context (uses ThreadLocal)
        String tenantId = TenantContext.getTenantId();
        return createDocument(categoryName, description, operation, accountName, tenantId);
    }

    /**
     * Creates a document in Redis for RAG-based category determination with explicit tenant ID.
     * Use this overload for async operations where TenantContext may not be available.
     * 
     * @param categoryName The category name for this transaction pattern
     * @param description The transaction description
     * @param operation The operation type (e.g., PLUS, MINUS)
     * @param accountName The account name
     * @param tenantId The tenant ID for multi-tenancy filtering (pass null for default)
     * @return The Redis key of the created document, or null on error
     */
    public String createDocument(String categoryName, String description, String operation, String accountName, String tenantId) {

        StatefulRedisConnection<String, String> connection = null;

        // Normalize tenant ID - use "default" if null or empty
        String effectiveTenantId = (tenantId != null && !tenantId.isEmpty()) ? tenantId : "default";

        try {
            // Include tenant ID in document key for easier bulk operations per tenant
            String searchKey = "doc:" + effectiveTenantId + ":" + UUID.randomUUID().toString();
            String contentPayload = accountName + " " + operation + " " + description;

            connection = redisClient.connect();
            RedisCommands<String, String> syncCommands = connection.sync();

            float[] vectorFloats = generateEmbeddingVector(contentPayload, "create");
            byte[] vectorByteArray = floatArrayToByteArray(vectorFloats);

            // 1. Build Command Arguments
            CommandArgs<String, String> hsetArgs = new CommandArgs<>(StringCodec.UTF8)
                    .add(searchKey)
                    // Add all string fields normally
                    .add("category").add(categoryName)
                    .add("operation").add(operation)
                    .add("account").add(accountName)
                    .add("tenant").add(effectiveTenantId)  // Multi-tenancy: store tenant ID
                    .add("content_payload").add(contentPayload);

            hsetArgs.add("vector").add(vectorByteArray);
            CommandOutput<String, String, Long> output = new IntegerOutput<>(StringCodec.UTF8);
            syncCommands.dispatch(HSET_COMMAND, output, hsetArgs);

            log.info("✅ Document created with key: {} (Tenant: {}, Vector Size: {} bytes)", 
                    searchKey, effectiveTenantId, vectorByteArray.length);
            return searchKey;

        } catch (Exception e) {
            log.error("Error creating document for tenant {}: {}", effectiveTenantId, e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            if (connection != null)
                connection.close();
        }
    }

    /**
     * Searches for similar documents in Redis using vector similarity.
     * Uses TenantContext to get the current tenant ID for multi-tenancy filtering.
     * 
     * @param description The transaction description to search for
     * @param operation The operation type
     * @param accountName The account name
     * @return List of matching RedisDocuments, or null on error
     */
    public List<RedisDocument> searchDocuments(String description, String operation, String accountName) {
        // Get tenant ID from context (uses ThreadLocal)
        String tenantId = TenantContext.getTenantId();
        return searchDocuments(description, operation, accountName, tenantId);
    }

    /**
     * Searches for similar documents in Redis with explicit tenant ID filtering.
     * Use this overload for async operations where TenantContext may not be available.
     * 
     * @param description The transaction description to search for
     * @param operation The operation type
     * @param accountName The account name
     * @param tenantId The tenant ID for filtering (pass null for default)
     * @return List of matching RedisDocuments filtered by tenant, or null on error
     */
    public List<RedisDocument> searchDocuments(String description, String operation, String accountName, String tenantId) {

        StatefulRedisConnection<String, String> connection = null;
        RedisCommands<String, String> syncCommands = null;

        // Normalize tenant ID - use "default" if null or empty
        String effectiveTenantId = (tenantId != null && !tenantId.isEmpty()) ? tenantId : "default";

        try {
            float[] queryVectorFloats = generateEmbeddingVector(accountName + " " + operation + " " + description, "query");
            byte[] queryVectorByteArray = convertFloatsToRedisByteArray(queryVectorFloats);

            connection = redisClient.connect();
            syncCommands = connection.sync();

            CommandArgs<String, String> argsBuilder = new CommandArgs<>(StringCodec.UTF8);
            argsBuilder.add(this.INDEX_NAME);

            // Multi-tenancy: Add tenant filter to the KNN query
            // Format: (@tenant:{tenantId})=>[KNN 1 @vector $query_vec]
            String queryFilter = "(@tenant:{" + effectiveTenantId + "})=>[KNN 1 @vector $query_vec]";
            argsBuilder.add(queryFilter);
            argsBuilder.add("DIALECT").add(2);

            // C. PARAMS (Vector Data)
            argsBuilder.add("PARAMS").add(2);
            argsBuilder.add("query_vec");
            argsBuilder.add(queryVectorByteArray); // CRITICAL: Inject the raw byte array

            // D. RETURN (Fields to retrieve)
            argsBuilder.add("RETURN").add(6);
            argsBuilder.add("category");
            argsBuilder.add("operation");
            argsBuilder.add("account");
            argsBuilder.add("tenant");  // Include tenant in results for verification
            argsBuilder.add("content_payload");
            argsBuilder.add("__vector_score"); // Use the implicit score field

            // --- 4. Command Execution ---
            // Use the defined FT_SEARCH_COMMAND and the low-level dispatch
            CommandOutput<String, String, List<Object>> output = new ArrayOutput<>(StringCodec.UTF8);
            List<Object> results = syncCommands.dispatch(FT_SEARCH_COMMAND, output, argsBuilder);

            // --- 5. Result Processing (Format results for user viewing) ---
            return formatSearchResults(results);

        } catch (Exception e) {
            log.error("Error executing FT.SEARCH command for tenant {}: {}", effectiveTenantId, e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            // --- 6. Cleanup ---
            if (connection != null)
                connection.close();
        }
    }

    private List<RedisDocument> formatSearchResults(List<Object> rawResults) {

        List<Object> docs = (List<Object>) rawResults.get(5);
        int docsCount = Integer.parseInt(((Long) rawResults.get(7)).toString());
        List<RedisDocument> output = new ArrayList<>();

        for (int docIndex = 0; docIndex < docsCount; docIndex++) {
            List<Object> doc = (List<Object>) docs.get(docIndex);
            //log.info("Doc Id: " + doc.get(1));
            RedisDocument redisDocument = new RedisDocument();
            redisDocument.setDocumentId(doc.get(1).toString());

            //log.info("-----------------Fields-----------------");
            List<Object> fields = (List<Object>) doc.get(3);
            for (int i = 0; i < fields.size(); i += 2) {
                String fieldName = fields.get(i).toString();
                String fieldValue = fields.get(i + 1).toString();
                //log.info(fieldName + ": " + fieldValue);
                redisDocument.addField(fieldName, fieldValue);
            }
            output.add(redisDocument);
        }

        return output;
    }

    private byte[] floatArrayToByteArray(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * Float.BYTES);
        // RedisSearch expects little-endian byte order for vector data
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    public String getIndexName(){
        return this.INDEX_NAME;
    }

    public String getRedisURI(){
        return this.REDIS_URI;
    }    

    public int getVectorDimension() {
        return this.TARGET_VECTOR_DIMENSION;
    }

    /**
     * Retrieves all documents for a specific tenant.
     * Used for viewing the RAG configuration documents.
     * 
     * @param tenantId The tenant ID whose documents should be retrieved
     * @return List of RedisDocuments for the tenant
     */
    public List<RedisDocument> getDocumentsByTenant(String tenantId) {
        StatefulRedisConnection<String, String> connection = null;
        
        // Normalize tenant ID
        String effectiveTenantId = (tenantId != null && !tenantId.isEmpty()) ? tenantId : "default";
        List<RedisDocument> documents = new ArrayList<>();
        
        try {
            log.info("Retrieving all documents for tenant '{}'", effectiveTenantId);
            connection = redisClient.connect();
            RedisCommands<String, String> syncCommands = connection.sync();
            
            // Pattern to match tenant documents: doc:<tenantId>:*
            String pattern = "doc:" + effectiveTenantId + ":*";
            
            // Use SCAN to find all matching keys
            io.lettuce.core.ScanArgs scanArgs = io.lettuce.core.ScanArgs.Builder.matches(pattern).limit(1000);
            io.lettuce.core.KeyScanCursor<String> cursor = syncCommands.scan(scanArgs);
            
            while (true) {
                List<String> keys = cursor.getKeys();
                for (String key : keys) {
                    // Fetch hash fields for each key
                    java.util.Map<String, String> hashFields = syncCommands.hgetall(key);
                    if (!hashFields.isEmpty()) {
                        RedisDocument doc = new RedisDocument();
                        doc.setDocumentId(key);
                        hashFields.forEach((fieldName, fieldValue) -> {
                            // Skip the vector field as it's binary data
                            if (!"vector".equals(fieldName)) {
                                doc.addField(fieldName, fieldValue);
                            }
                        });
                        documents.add(doc);
                    }
                }
                
                if (cursor.isFinished()) {
                    break;
                }
                
                cursor = syncCommands.scan(cursor, scanArgs);
            }
            
            log.info("✅ Retrieved {} documents for tenant '{}'", documents.size(), effectiveTenantId);
            return documents;
            
        } catch (Exception e) {
            log.error("Error retrieving documents for tenant '{}': {}", effectiveTenantId, e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve tenant documents: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    /**
     * Deletes all documents for a specific tenant.
     * Useful for tenant cleanup or data migration.
     * 
     * @param tenantId The tenant ID whose documents should be deleted
     * @return The number of documents deleted
     */
    public int deleteDocumentsByTenant(String tenantId) {
        StatefulRedisConnection<String, String> connection = null;
        
        // Normalize tenant ID
        String effectiveTenantId = (tenantId != null && !tenantId.isEmpty()) ? tenantId : "default";
        
        try {
            log.info("Deleting all documents for tenant '{}' from Redis...", effectiveTenantId);
            connection = redisClient.connect();
            RedisCommands<String, String> syncCommands = connection.sync();
            
            // Pattern to match tenant documents: doc:<tenantId>:*
            String pattern = "doc:" + effectiveTenantId + ":*";
            
            // Use SCAN to find all matching keys
            io.lettuce.core.ScanArgs scanArgs = io.lettuce.core.ScanArgs.Builder.matches(pattern).limit(1000);
            io.lettuce.core.KeyScanCursor<String> cursor = syncCommands.scan(scanArgs);
            
            int totalDeleted = 0;
            
            while (true) {
                List<String> keys = cursor.getKeys();
                if (!keys.isEmpty()) {
                    Long deleted = syncCommands.del(keys.toArray(new String[0]));
                    totalDeleted += deleted != null ? deleted.intValue() : 0;
                }
                
                if (cursor.isFinished()) {
                    break;
                }
                
                cursor = syncCommands.scan(cursor, scanArgs);
            }
            
            log.info("✅ Deleted {} documents for tenant '{}'", totalDeleted, effectiveTenantId);
            return totalDeleted;
            
        } catch (Exception e) {
            log.error("Error deleting documents for tenant '{}': {}", effectiveTenantId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete tenant documents: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    /**
     * Deletes specific documents by their Redis keys.
     * Used for selective document deletion from the RAG configuration.
     * 
     * @param documentKeys List of document keys to delete
     * @return The number of documents successfully deleted
     */
    public int deleteDocumentsByKeys(List<String> documentKeys) {
        if (documentKeys == null || documentKeys.isEmpty()) {
            log.info("No document keys provided for deletion");
            return 0;
        }
        
        StatefulRedisConnection<String, String> connection = null;
        
        try {
            log.info("Deleting {} specific documents from Redis...", documentKeys.size());
            connection = redisClient.connect();
            RedisCommands<String, String> syncCommands = connection.sync();
            
            Long deleted = syncCommands.del(documentKeys.toArray(new String[0]));
            int deletedCount = deleted != null ? deleted.intValue() : 0;
            
            log.info("✅ Deleted {} documents", deletedCount);
            return deletedCount;
            
        } catch (Exception e) {
            log.error("Error deleting documents: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete documents: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    /**
     * Delete the entire index AND all associated documents.
     * Uses the DD (Delete Documents) flag to remove both the index structure 
     * and all hash keys associated with this index (keys with prefix 'doc:').
     */
    public void deleteIndex() {
        StatefulRedisConnection<String, String> connection = null;
        
        try {
            log.info("Deleting index '{}' from Redis...", INDEX_NAME);
            connection = redisClient.connect();
            RedisCommands<String, String> syncCommands = connection.sync();
            
            ProtocolKeyword ftDropIndexCommand = new ProtocolKeyword() {
                @Override
                public byte[] getBytes() {
                    return "FT.DROPINDEX".getBytes(StandardCharsets.UTF_8);
                }
            };
            
            CommandArgs<String, String> argsBuilder = new CommandArgs<>(StringCodec.UTF8);
            argsBuilder.add(INDEX_NAME);
            argsBuilder.add("DD");  // DD flag = Delete Documents (deletes all hash keys with this index)
            
            CommandOutput<String, String, String> output = new StatusOutput<>(StringCodec.UTF8);
            String result = syncCommands.dispatch(ftDropIndexCommand, output, argsBuilder);
            
            log.info("FT.DROPINDEX command with DD flag executed successfully - index and all documents deleted");
            log.info("Result: {}", result);
            
        } catch (Exception e) {
            log.error("Error deleting index '{}': {}", INDEX_NAME, e.getMessage(), e);
            throw new RuntimeException("Failed to delete index: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.close();
                log.info("Connection closed after deleting index");
            }
        }
    }
}
