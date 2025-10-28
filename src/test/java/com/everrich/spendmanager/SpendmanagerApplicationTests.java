package com.everrich.spendmanager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.everrich.spendmanager.config.TestAiConfig;

@SpringBootTest(properties = {
    // 1. Exclude the Google AI client (blocks the real EmbeddingModel failure)
    "spring.autoconfigure.exclude[0]=org.springframework.ai.google.genai.autoconfigure.GoogleGenAiAutoConfiguration",
    // 2. Exclude the Redis VectorStore (blocks the real VectorStore failure)
    "spring.autoconfigure.exclude[1]=org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreAutoConfiguration"
})
@Import(TestAiConfig.class)
@ActiveProfiles("test")
class SpendmanagerApplicationTests {

	@Test
	void contextLoads() {
	}

}
