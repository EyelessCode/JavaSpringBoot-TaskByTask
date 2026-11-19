package com.eyelesscode.task_by_task.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("prod")
@TestPropertySource(properties = {"db-scheduler.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProdProfileIntegrationTest {

    @ServiceConnection
    private static final PostgreSQLContainer<?> PROD_POSTGRES =
            new PostgreSQLContainer<>("postgres:alpine");

    static {
        PROD_POSTGRES.start();
    }

    @Autowired private WebApplicationContext context;
    @Autowired private DataSource dataSource;
    @Autowired private SecurityProperties securityProperties;

    @Test
    @DisplayName(
            "Perfil prod arranca con HikariCP acotado, cookies seguras, Swagger deshabilitado"
                    + " (404 RFC 7807) y Actuator readiness UP con VAPID off"
                    + " (HRD-REQ-12..16, HRD-REQ-20, EC-06)")
    void shouldStartProdProfileWithHardenedDefaults() throws Exception {
        // 1. Pool HikariCP acotado
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
        assertThat(hikari.getMinimumIdle()).isEqualTo(2);
        assertThat(hikari.getConnectionTimeout()).isEqualTo(5000L);
        assertThat(hikari.getIdleTimeout()).isEqualTo(300_000L);
        assertThat(hikari.getMaxLifetime()).isEqualTo(1_500_000L);

        // 2. Política de cookies segura en prod
        assertThat(securityProperties.cookie().secure()).isTrue();
        assertThat(securityProperties.cookie().sameSite()).isEqualTo("Lax");

        MockMvc mockMvc =
                MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        // 3. OpenAPI y Swagger UI deshabilitados en prod -> 404 RFC 7807 en español (EC-06)
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isNotFound())
                .andExpect(
                        header().string(
                                        HttpHeaders.CONTENT_TYPE,
                                        containsString("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"))
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isNotFound())
                .andExpect(
                        header().string(
                                        HttpHeaders.CONTENT_TYPE,
                                        containsString("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"))
                .andExpect(jsonPath("$.status").value(404));

        // 4. Actuator liveness/readiness UP sin detalles internos y con VAPID deshabilitado
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/env").with(user("1").roles("USER")))
                .andExpect(status().isForbidden());
    }
}
