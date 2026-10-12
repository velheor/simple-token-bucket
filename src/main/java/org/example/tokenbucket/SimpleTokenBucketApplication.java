package org.example.tokenbucket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "org.example.tokenbucket.config")
@EnableConfigurationProperties
public class SimpleTokenBucketApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimpleTokenBucketApplication.class, args);
    }
}
