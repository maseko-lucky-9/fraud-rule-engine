package com.capitec.fraud.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI fraudEngineOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fraud Rule Engine API")
                        .version("v1")
                        .description("Fraud rule engine: deterministic evaluation with optional AI advisory. "
                                + "Submit transaction events, retrieve flagged decisions with full rule trace."));
    }
}
