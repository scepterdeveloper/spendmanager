package com.everrich.spendmanager.service;

import static java.util.stream.Collectors.toList;

import com.google.cloud.aiplatform.v1.EndpointName;
import com.google.cloud.aiplatform.v1.PredictRequest;
import com.google.cloud.aiplatform.v1.PredictResponse;
import com.google.cloud.aiplatform.v1.PredictionServiceClient;
import com.google.cloud.aiplatform.v1.PredictionServiceSettings;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EmbeddingsCreator {

    // Gets text embeddings from a pretrained, foundational model.
    public static List<List<Float>> predictTextEmbeddings(
            String endpoint,
            String project,
            String model,
            List<String> texts,
            String task,
            OptionalInt outputDimensionality)
            throws IOException {
        PredictionServiceSettings settings = PredictionServiceSettings.newBuilder().setEndpoint(endpoint).build();
        Matcher matcher = Pattern.compile("^(?<Location>\\w+-\\w+)").matcher(endpoint);
        String location = matcher.matches() ? matcher.group("Location") : "us-central1";
        EndpointName endpointName = EndpointName.ofProjectLocationPublisherModelName(project, location, "google",
                model);

        List<List<Float>> floats = new ArrayList<>();
        // You can use this prediction service client for multiple requests.
        try (PredictionServiceClient client = PredictionServiceClient.create(settings)) {
            // gemini-embedding-001 takes one input at a time.
            for (int i = 0; i < texts.size(); i++) {
                PredictRequest.Builder request = PredictRequest.newBuilder().setEndpoint(endpointName.toString());
                if (outputDimensionality.isPresent()) {
                    request.setParameters(
                            Value.newBuilder()
                                    .setStructValue(
                                            Struct.newBuilder()
                                                    .putFields(
                                                            "outputDimensionality",
                                                            valueOf(outputDimensionality.getAsInt()))
                                                    .build()));
                }
                request.addInstances(
                        Value.newBuilder()
                                .setStructValue(
                                        Struct.newBuilder()
                                                .putFields("content", valueOf(texts.get(i)))
                                                .putFields("task_type", valueOf(task))
                                                .build()));
                PredictResponse response = client.predict(request.build());

                for (Value prediction : response.getPredictionsList()) {
                    Value embeddings = prediction.getStructValue().getFieldsOrThrow("embeddings");
                    Value values = embeddings.getStructValue().getFieldsOrThrow("values");
                    floats.add(
                            values.getListValue().getValuesList().stream()
                                    .map(Value::getNumberValue)
                                    .map(Double::floatValue)
                                    .collect(toList()));
                }
            }
            return floats;
        }
    }

    private static Value valueOf(String s) {
        return Value.newBuilder().setStringValue(s).build();
    }

    private static Value valueOf(int n) {
        return Value.newBuilder().setNumberValue(n).build();
    }
}
