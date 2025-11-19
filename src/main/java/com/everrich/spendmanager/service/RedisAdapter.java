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
            argsBuilder.add("description_op").add("TEXT");
            argsBuilder.add("content_payload").add("TEXT");
            argsBuilder.add("category").add("TAG");
            argsBuilder.add("operation").add("TAG");
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

    public String createDocument(String categoryName, String description, String operation) {

        StatefulRedisConnection<String, String> connection = null;
        //log.info("Starting creation...with description " + description);
        //log.info("Connection pramas: " + REDIS_URI + " " + INDEX_NAME);

        try {
            String searchKey = "doc:" + UUID.randomUUID().toString();
            String contentPayload = description + " " + operation + " " + categoryName;

            connection = redisClient.connect();
            RedisCommands<String, String> syncCommands = connection.sync();

            float[] vectorFloats = generateEmbeddingVector(contentPayload, "create");
            byte[] vectorByteArray = floatArrayToByteArray(vectorFloats);

            // 1. Build Command Arguments
            CommandArgs<String, String> hsetArgs = new CommandArgs<>(StringCodec.UTF8)
                    .add(searchKey)
                    // Add all string fields normally
                    .add("description_op").add(description + " " + operation)
                    .add("category").add(categoryName)
                    .add("operation").add(operation)
                    .add("content_payload").add(contentPayload);

            hsetArgs.add("vector").add(vectorByteArray);
            CommandOutput<String, String, Long> output = new IntegerOutput<>(StringCodec.UTF8);
            syncCommands.dispatch(HSET_COMMAND, output, hsetArgs);

            log.info("✅ Document created with key: " + searchKey
                    + " (Vector Size: " + vectorByteArray.length + " bytes)");
            return searchKey;

        } catch (Exception e) {
            System.err.println("Error creating document:");
            e.printStackTrace();
            return null;
        } finally {
            if (connection != null)
                connection.close();
        }
    }

    public List<RedisDocument> searchDocuments(String description, String operation) {

        //log.info("Starting search...with description " + description);
        //log.info("Connection pramas: " + REDIS_URI + " " + INDEX_NAME);
        StatefulRedisConnection<String, String> connection = null;
        RedisCommands<String, String> syncCommands = null;

        try {
            float[] queryVectorFloats = generateEmbeddingVector(description + " " + operation, "query");
            byte[] queryVectorByteArray = convertFloatsToRedisByteArray(queryVectorFloats);

            connection = redisClient.connect();
            syncCommands = connection.sync();

            //log.info("Connection established for search.");
            //log.info("Query vector size being sent: " + queryVectorByteArray.length + " bytes");

            CommandArgs<String, String> argsBuilder = new CommandArgs<>(StringCodec.UTF8);
            argsBuilder.add(this.INDEX_NAME);

            String queryFilter = "*=>[KNN 1 @vector $query_vec]";
            argsBuilder.add(queryFilter);
            argsBuilder.add("DIALECT").add(2);

            // C. PARAMS (Vector Data)
            argsBuilder.add("PARAMS").add(2);
            argsBuilder.add("query_vec");
            argsBuilder.add(queryVectorByteArray); // CRITICAL: Inject the raw byte array

            // D. RETURN (Fields to retrieve)
            argsBuilder.add("RETURN").add(5);
            argsBuilder.add("category");
            argsBuilder.add("operation");
            argsBuilder.add("description_op");
            argsBuilder.add("content_payload");
            argsBuilder.add("__vector_score"); // Use the implicit score field

            // --- 4. Command Execution ---
            // Use the defined FT_SEARCH_COMMAND and the low-level dispatch
            CommandOutput<String, String, List<Object>> output = new ArrayOutput<>(StringCodec.UTF8);
            List<Object> results = syncCommands.dispatch(FT_SEARCH_COMMAND, output, argsBuilder);

            // --- 5. Result Processing (Format results for user viewing) ---
            return formatSearchResults(results);

        } catch (Exception e) {
            log.error("Error executing FT.SEARCH command:" + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            // --- 6. Cleanup ---
            if (connection != null)
                connection.close();
            //log.info("Connection closed after search.");
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
}
