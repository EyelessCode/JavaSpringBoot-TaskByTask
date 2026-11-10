package com.eyelesscode.task_by_task.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ApplicationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName(
            "Debe levantar el contexto de Spring Boot e inicializar el esquema completo vía Flyway")
    void shouldLoadContextAndInitializeSchemaWithFlyway() {
        // 1. Verificar que Flyway ejecutó la migración V1 exitosamente
        Integer flywayCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success"
                                + " = TRUE",
                        Integer.class);
        assertThat(flywayCount).isEqualTo(1);

        // 2. Verificar que las 12 tablas de dominio e infraestructura existen en public schema
        List<String> tables =
                jdbcTemplate.queryForList(
                        "SELECT LOWER(table_name) FROM information_schema.tables WHERE table_schema"
                                + " = 'public'",
                        String.class);

        assertThat(tables)
                .contains(
                        "users",
                        "recovery_codes",
                        "groups",
                        "group_memberships",
                        "tags",
                        "tasks",
                        "task_tags",
                        "push_subscriptions",
                        "notifications",
                        "spring_session",
                        "spring_session_attributes",
                        "scheduled_tasks");
    }
}
