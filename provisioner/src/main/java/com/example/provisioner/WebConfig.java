package com.example.provisioner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.origins:}")
    private String corsOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] allowed = (corsOrigins == null || corsOrigins.isBlank())
                ? new String[0]
                : corsOrigins.split(",");
        registry.addMapping("/api/**")
                .allowedOrigins(allowed)
                .allowedMethods("*")
                .allowedHeaders("*");
    }
}
