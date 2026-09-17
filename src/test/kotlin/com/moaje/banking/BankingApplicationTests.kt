package com.moaje.banking

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class BankingApplicationTests(@Autowired private val mockMvc: MockMvc) {
    @Test
    @DisplayName("스프링 애플리케이션 컨텍스트가 정상적으로 로드된다")
    fun contextLoads() {
    }

    @Test
    @DisplayName("Banking OpenAPI 문서와 송금 API가 생성된다")
    fun exposesBankingOpenApi() {
        mockMvc.get("/v3/api-docs")
            .andExpect {
                status { isOk() }
                jsonPath("$.info.title") { value("Moaje Banking API") }
                jsonPath("$.servers[0].url") { value("/") }
                jsonPath("$.paths['/api/v1/banking/transfers']") { exists() }
            }
        mockMvc.get("/swagger-ui/index.html")
            .andExpect { status { isOk() } }
    }
}
