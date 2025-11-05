package com.everrich.spendmanager.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

public class VectorUtils {

    /**
     * Converts a List<Double> (from Spring AI) into a primitive float array.
     * This is necessary because RediSearch/Jedis often expects float[], not List<Double> or double[].
     */
    public static float[] doubleListToFloatArray(List<Double> doubles) {
        if (doubles == null || doubles.isEmpty()) {
            return new float[0];
        }
        float[] floats = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) {
            // Explicitly cast each Double wrapper to a primitive float
            floats[i] = doubles.get(i).floatValue();
        }
        return floats;
    }

    /**
     * Converts a float array (vector) into a byte array for Redis.
     * CRITICAL: Must be Little Endian (FLOAT32).
     */
    public static byte[] floatArrayToByteArray(float[] floats) {
        // Allocate a ByteBuffer large enough for all floats (4 bytes per float)
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4);

        // Crucial: Set the byte order to Little Endian
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Put the float array into the buffer
        for (float f : floats) {
            buffer.putFloat(f);
        }

        return buffer.array();
    }
}