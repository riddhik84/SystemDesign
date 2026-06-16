package com.systemdesign.bitly.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * General application configuration: OpenAPI metadata and any shared beans that do not belong
 * to a more specific configuration class.
 */
@Configuration
public class AppConfig {

    @Bean
    public OpenAPI openAPI(@Value("${app.base-url}") String baseUrl) {
        return new OpenAPI()
            .info(new Info()
                .title("Bitly URL Shortener API")
                .description("""
                    Production-quality URL shortener implementing system design best practices:
                    counter-based short code generation with Redis batching, Redis LRU cache on
                    the read path, and PostgreSQL persistence.

                    Design targets: 1B URLs, 100M DAU, 1000:1 read/write ratio, <100ms redirect latency.
                    """)
                .version("1.0.0")
                .contact(new Contact()
                    .name("System Design Examples")
                    .url("https://github.com/systemdesign/bitly"))
                .license(new License().name("MIT")))
            .servers(List.of(
                new Server().url(baseUrl).description("Current environment")
            ));
    }
}
