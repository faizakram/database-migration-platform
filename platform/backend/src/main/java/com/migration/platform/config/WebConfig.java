package com.migration.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS for the SPA and the shared {@link RestClient} used to reach Kafka Connect. */
@Configuration
public class WebConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer(PlatformProperties props) {
        String[] origins = props.cors().allowedOrigins().split(",");
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }

    @Bean
    public RestClient connectRestClient(PlatformProperties props) {
        return RestClient.builder()
                .baseUrl(props.connect().baseUrl())
                .build();
    }
}
