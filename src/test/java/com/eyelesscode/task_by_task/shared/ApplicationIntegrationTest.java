package com.eyelesscode.task_by_task.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyelesscode.task_by_task.auth.RecoveryCode;
import com.eyelesscode.task_by_task.auth.RecoveryCodeRepository;
import com.eyelesscode.task_by_task.group.Group;
import com.eyelesscode.task_by_task.group.GroupMembership;
import com.eyelesscode.task_by_task.group.GroupMembershipRepository;
import com.eyelesscode.task_by_task.group.GroupRepository;
import com.eyelesscode.task_by_task.notification.PushSubscription;
import com.eyelesscode.task_by_task.notification.PushSubscriptionRepository;
import com.eyelesscode.task_by_task.user.User;
import com.eyelesscode.task_by_task.user.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

class ApplicationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private RecoveryCodeRepository recoveryCodeRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMembershipRepository groupMembershipRepository;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;

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

    @Test
    @Transactional
    @DisplayName(
            "Debe persistir y consultar entidades JPA base y sus repositorios con"
                    + " ddl-auto=validate")
    void shouldPersistAndQueryBaseEntitiesThroughRepositories() {
        Instant now = Instant.now();

        User user =
                new User(
                        "test@example.com",
                        "TestUser123",
                        "Test User",
                        "{bcrypt}$2a$10$dummyhash",
                        "UTC",
                        "24H",
                        now,
                        now);
        user = userRepository.saveAndFlush(user);
        assertThat(user.getId()).isNotNull();

        assertThat(userRepository.existsByEmailIgnoreCase("TEST@EXAMPLE.COM")).isTrue();
        assertThat(userRepository.existsByUsernameIgnoreCase("testuser123")).isTrue();
        assertThat(userRepository.findByEmailIgnoreCase("TEST@example.com")).isPresent();
        assertThat(userRepository.findByUsernameIgnoreCase("testuser123")).isPresent();
        assertThat(userRepository.existsByEmailIgnoreCaseAndIdNot("TEST@EXAMPLE.COM", user.getId()))
                .isFalse();
        assertThat(userRepository.existsByUsernameIgnoreCaseAndIdNot("testuser123", user.getId()))
                .isFalse();

        Group group = new Group(user, "Personal", true, now, now);
        group = groupRepository.saveAndFlush(group);
        assertThat(group.getId()).isNotNull();
        assertThat(groupRepository.findByCreatorIdAndIsPersonalTrue(user.getId())).isPresent();

        GroupMembership membership = new GroupMembership(group, user, "CREATOR", now);
        membership = groupMembershipRepository.saveAndFlush(membership);
        assertThat(membership.getId()).isNotNull();

        RecoveryCode code = new RecoveryCode(user, "{bcrypt}$2a$10$codehash", false, null, now);
        code = recoveryCodeRepository.saveAndFlush(code);
        assertThat(code.getId()).isNotNull();
        assertThat(recoveryCodeRepository.countByUserIdAndUsedFalse(user.getId())).isEqualTo(1L);
        assertThat(recoveryCodeRepository.findByUserIdAndUsedFalse(user.getId())).hasSize(1);

        recoveryCodeRepository.deleteByUserId(user.getId());
        assertThat(recoveryCodeRepository.countByUserIdAndUsedFalse(user.getId())).isZero();

        PushSubscription sub =
                new PushSubscription(
                        user,
                        "https://fcm.googleapis.com/fcm/send/123",
                        "p256dh-key",
                        "auth-key",
                        now);
        sub = pushSubscriptionRepository.saveAndFlush(sub);
        assertThat(sub.getId()).isNotNull();

        pushSubscriptionRepository.deleteByUserIdAndEndpoint(
                user.getId(), "https://fcm.googleapis.com/fcm/send/123");
        assertThat(pushSubscriptionRepository.findById(sub.getId())).isEmpty();
    }

    @Test
    @DisplayName(
            "Debe exponer el contrato OpenAPI /v3/api-docs y /swagger-ui/index.html con HTTP 200")
    void shouldExposeOpenApiDocsAndSwaggerUi() throws Exception {
        MockMvc mockMvc =
                MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").isNotEmpty())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "/api/v1/auth/login")));

        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
