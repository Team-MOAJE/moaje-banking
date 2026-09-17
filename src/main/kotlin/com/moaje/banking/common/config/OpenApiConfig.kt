package com.moaje.banking.common.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun bankingOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Moaje Banking API")
                .version("v1")
                .description("계좌 연결과 송금 Transaction Journal API"),
        )
        .components(
            Components().addSecuritySchemes(
                BEARER_AUTH,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT"),
            ),
        )
        .servers(listOf(Server().url("/").description("현재 Swagger가 열린 호스트")))
        .addSecurityItem(SecurityRequirement().addList(BEARER_AUTH))

    companion object { private const val BEARER_AUTH = "bearerAuth" }
}
