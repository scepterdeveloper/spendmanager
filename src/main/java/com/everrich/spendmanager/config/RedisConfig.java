package com.everrich.spendmanager.config;

// Example Redis Configuration (In a class annotated with @Configuration)

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.resource.ClientResources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class RedisConfig {

    @Value("${com.everrich.properties.redis.uri}")
    private String redisUri;

    @Bean
    public RedisClient redisClient(ClientResources clientResources) {
        // Parse the URI string you are injecting (e.g., rediss://:pass@host:port/0)
        RedisURI uri = RedisURI.create(redisUri);
        
        // This is the CRITICAL line: force SSL to be true on the URI object
        // The URI parsing might not automatically set SSL to true when using rediss://
        uri.setSsl(true); 

        // Optional: Trust all certificates (often needed in Cloud Run environments)
        // uri.setVerifyPeer(false); 

        return RedisClient.create(clientResources, uri);
    }
    
    // You may also need to define the ClientResources bean if you haven't already:
    @Bean(destroyMethod = "shutdown")
    public ClientResources clientResources() {
        return ClientResources.create();
    }
}