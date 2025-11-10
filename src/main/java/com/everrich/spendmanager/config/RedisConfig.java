package com.everrich.spendmanager.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class RedisConfig {

    @Value("${com.everrich.properties.redis.uri}")
    private String redisUri;

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Bean
    public RedisClient redisClient(ClientResources clientResources) {

        RedisURI uri = RedisURI.create(redisUri);
        log.info("Wiring and creating redisClient with URI: " + redisUri);
        return RedisClient.create(clientResources, uri);
    }

    @Bean(destroyMethod = "shutdown")
    public ClientResources clientResources() {
        return DefaultClientResources.create();
    }
}