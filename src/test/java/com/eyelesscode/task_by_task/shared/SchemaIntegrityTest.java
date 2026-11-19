package com.eyelesscode.task_by_task.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaIntegrityTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM users");
    }

    private Long createTestUser(String username, String email) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, username, display_name, password_hash) VALUES (?, ?, ?,"
                        + " ?) RETURNING id",
                Long.class,
                email,
                username,
                "Test User",
                "{bcrypt}$2a$10$abcdefghijklmnopqrstuvwxyz123456");
    }

    private Long createTestGroup(Long creatorId, String name, boolean isPersonal) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO groups (creator_id, name, is_personal) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                creatorId,
                name,
                isPersonal);
    }

    @Test
    @DisplayName(
            "Integridad temporal: Rechazar tareas con due_time sin due_date"
                    + " (chk_tasks_due_time_requires_date)")
    void shouldEnforceTemporalIntegrityOnTasks() {
        Long userId = createTestUser("taskuser", "taskuser@example.com");
        Long groupId = createTestGroup(userId, "Personal", true);

        // 1. due_time sin due_date debe lanzar DataIntegrityViolationException
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO tasks (group_id, title, due_date, due_time)"
                                                + " VALUES (?, ?, ?, ?)",
                                        groupId,
                                        "Invalid Task",
                                        null,
                                        LocalTime.of(14, 30)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 2. due_date con due_time debe funcionar correctamente
        int insertedWithBoth =
                jdbcTemplate.update(
                        "INSERT INTO tasks (group_id, title, due_date, due_time) VALUES (?, ?, ?,"
                                + " ?)",
                        groupId,
                        "Valid Task With Time",
                        LocalDate.of(2026, 12, 1),
                        LocalTime.of(10, 0));
        assertThat(insertedWithBoth).isEqualTo(1);

        // 3. due_date sin due_time (date-only) debe funcionar correctamente
        int insertedDateOnly =
                jdbcTemplate.update(
                        "INSERT INTO tasks (group_id, title, due_date, due_time) VALUES (?, ?, ?,"
                                + " ?)",
                        groupId,
                        "Valid Date-Only Task",
                        LocalDate.of(2026, 12, 2),
                        null);
        assertThat(insertedDateOnly).isEqualTo(1);

        // 4. Ninguno definido debe funcionar correctamente
        int insertedNoDates =
                jdbcTemplate.update(
                        "INSERT INTO tasks (group_id, title, due_date, due_time) VALUES (?, ?, ?,"
                                + " ?)",
                        groupId,
                        "Task Without Dates",
                        null,
                        null);
        assertThat(insertedNoDates).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Unicidad parcial del grupo Personal: Solo se permite un grupo is_personal=true por"
                    + " usuario (uq_groups_creator_personal)")
    void shouldEnforcePartialUniqueIndexForPersonalGroup() {
        Long user1Id = createTestUser("userone", "userone@example.com");
        Long user2Id = createTestUser("usertwo", "usertwo@example.com");

        // Crear grupo personal inicial para user1
        createTestGroup(user1Id, "Personal", true);

        // user1 puede crear múltiples grupos compartidos (is_personal = false)
        createTestGroup(user1Id, "Trabajo", false);
        createTestGroup(user1Id, "Familia", false);

        // Intentar crear un segundo grupo personal para user1 debe fallar
        assertThatThrownBy(() -> createTestGroup(user1Id, "Otro Personal", true))
                .isInstanceOf(DataIntegrityViolationException.class);

        // user2 sí puede crear su propio grupo personal
        Long user2PersonalGroup = createTestGroup(user2Id, "Personal", true);
        assertThat(user2PersonalGroup).isNotNull();
    }

    @Test
    @DisplayName(
            "Unicidad normalizada de etiquetas y prohibición de delimitadores"
                    + " (uq_tags_group_normalized_name, chk_tags_no_delimiters)")
    void shouldEnforceNormalizedUniqueTagNameAndProhibitDelimiters() {
        Long userId = createTestUser("taguser", "taguser@example.com");
        Long group1Id = createTestGroup(userId, "Grupo 1", true);
        Long group2Id = createTestGroup(userId, "Grupo 2", false);

        // Insertar etiqueta inicial con espacios
        jdbcTemplate.update(
                "INSERT INTO tags (group_id, name) VALUES (?, ?)", group1Id, " Urgente ");

        // Reintentar con diferente capitalización o espacios debe violar
        // uq_tags_group_normalized_name
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO tags (group_id, name) VALUES (?, ?)",
                                        group1Id,
                                        "urgente"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO tags (group_id, name) VALUES (?, ?)",
                                        group1Id,
                                        "URGENTE  "))
                .isInstanceOf(DataIntegrityViolationException.class);

        // En otro grupo sí se puede usar el mismo nombre
        int insertedInGroup2 =
                jdbcTemplate.update(
                        "INSERT INTO tags (group_id, name) VALUES (?, ?)", group2Id, "urgente");
        assertThat(insertedInGroup2).isEqualTo(1);

        // Prohibición de delimitadores ',' y ';'
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO tags (group_id, name) VALUES (?, ?)",
                                        group1Id,
                                        "tag,name"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO tags (group_id, name) VALUES (?, ?)",
                                        group1Id,
                                        "tag;name"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName(
            "Unicidad de membresías y roles permitidos (uq_group_memberships_group_user,"
                    + " chk_group_memberships_role)")
    void shouldEnforceGroupMembershipsUniquenessAndAllowedRoles() {
        Long creatorId = createTestUser("creator", "creator@example.com");
        Long member1Id = createTestUser("member1", "member1@example.com");
        Long member2Id = createTestUser("member2", "member2@example.com");
        Long groupId = createTestGroup(creatorId, "Proyecto", false);

        // Membresía inicial válida
        jdbcTemplate.update(
                "INSERT INTO group_memberships (group_id, user_id, role) VALUES (?, ?, ?)",
                groupId,
                member1Id,
                "MANAGER");

        // Duplicar membresía para el mismo usuario y grupo debe fallar
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO group_memberships (group_id, user_id, role)"
                                                + " VALUES (?, ?, ?)",
                                        groupId,
                                        member1Id,
                                        "VISITOR"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Rol no permitido debe fallar
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO group_memberships (group_id, user_id, role)"
                                                + " VALUES (?, ?, ?)",
                                        groupId,
                                        member2Id,
                                        "SUPERADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Roles permitidos (CREATOR, MANAGER, VISITOR) deben funcionar
        int insertedVisitor =
                jdbcTemplate.update(
                        "INSERT INTO group_memberships (group_id, user_id, role) VALUES (?, ?, ?)",
                        groupId,
                        member2Id,
                        "VISITOR");
        assertThat(insertedVisitor).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Unicidad case-insensitive de usuario y restricciones de formato (uq_users_email_lower,"
                    + " uq_users_username_lower, chk_users_time_format)")
    void shouldEnforceCaseInsensitiveUserUniquenessAndTimeFormat() {
        createTestUser("AlphaUser", "Alpha@Example.com");

        // Email duplicado con diferente casing
        assertThatThrownBy(() -> createTestUser("BetaUser", "alpha@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Username duplicado con diferente casing
        assertThatThrownBy(() -> createTestUser("alphauser", "beta@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // time_format inválido (solo '24H' o '12H')
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO users (email, username, display_name,"
                                            + " password_hash, time_format) VALUES (?, ?, ?, ?, ?)",
                                        "gamma@example.com",
                                        "GammaUser",
                                        "Gamma",
                                        "hash",
                                        "18H"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // username con caracteres no alfanuméricos o longitud inválida
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO users (email, username, display_name,"
                                                + " password_hash) VALUES (?, ?, ?, ?)",
                                        "delta@example.com",
                                        "ab", // Menor a 3 caracteres
                                        "Delta",
                                        "hash"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName(
            "Borrado en cascada: Eliminar usuario debe eliminar sus grupos, tareas, etiquetas,"
                    + " membresías, códigos, suscripciones y notificaciones (FND-REQ-05)")
    void shouldCascadeDeleteAllUserDataWhenUserIsDeleted() {
        Long userId = createTestUser("cascadeuser", "cascade@example.com");
        Long groupId = createTestGroup(userId, "Grupo Cascada", true);

        Long tagId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO tags (group_id, name) VALUES (?, ?) RETURNING id",
                        Long.class,
                        groupId,
                        "tag-cascada");

        Long taskId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO tasks (group_id, title) VALUES (?, ?) RETURNING id",
                        Long.class,
                        groupId,
                        "tarea-cascada");

        jdbcTemplate.update("INSERT INTO task_tags (task_id, tag_id) VALUES (?, ?)", taskId, tagId);

        jdbcTemplate.update(
                "INSERT INTO group_memberships (group_id, user_id, role) VALUES (?, ?, ?)",
                groupId,
                userId,
                "CREATOR");

        jdbcTemplate.update(
                "INSERT INTO recovery_codes (user_id, code_hash) VALUES (?, ?)",
                userId,
                "recovery_hash_123");

        jdbcTemplate.update(
                "INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth) VALUES (?, ?,"
                        + " ?, ?)",
                userId,
                "https://push.example.com/sub/123",
                "p256dh_key",
                "auth_key");

        jdbcTemplate.update(
                "INSERT INTO notifications (user_id, event_type, content) VALUES (?, ?, ?)",
                userId,
                "DUE_DATE_ALERT",
                "Tu tarea vence pronto");

        // Verificar que todos los registros existen
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM groups WHERE creator_id = ?",
                                Integer.class,
                                userId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tasks WHERE id = ?", Integer.class, taskId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tags WHERE id = ?", Integer.class, tagId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM task_tags WHERE task_id = ?",
                                Integer.class,
                                taskId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM recovery_codes WHERE user_id = ?",
                                Integer.class,
                                userId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?",
                                Integer.class,
                                userId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM notifications WHERE user_id = ?",
                                Integer.class,
                                userId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM group_memberships WHERE user_id = ?",
                                Integer.class,
                                userId))
                .isEqualTo(1);

        // Eliminar el usuario
        int deleted = jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        assertThat(deleted).isEqualTo(1);

        // Verificar que todas las entidades dependientes fueron eliminadas en cascada
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM groups WHERE creator_id = ?",
                                Integer.class,
                                userId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tasks WHERE id = ?", Integer.class, taskId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tags WHERE id = ?", Integer.class, tagId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM task_tags WHERE task_id = ?",
                                Integer.class,
                                taskId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM recovery_codes WHERE user_id = ?",
                                Integer.class,
                                userId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?",
                                Integer.class,
                                userId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM notifications WHERE user_id = ?",
                                Integer.class,
                                userId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM group_memberships WHERE user_id = ?",
                                Integer.class,
                                userId))
                .isZero();
    }

    @Test
    @DisplayName(
            "Borrado en cascada: Eliminar grupo debe eliminar tareas, etiquetas, asociaciones y"
                    + " membresías, preservando usuarios (FND-REQ-06)")
    void shouldCascadeDeleteGroupContentsWhenGroupIsDeleted() {
        Long ownerId = createTestUser("groupowner", "owner@example.com");
        Long memberId = createTestUser("groupmember", "member@example.com");
        Long groupId = createTestGroup(ownerId, "Grupo Compartido", false);

        Long tagId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO tags (group_id, name) VALUES (?, ?) RETURNING id",
                        Long.class,
                        groupId,
                        "etiqueta-grupo");

        Long taskId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO tasks (group_id, title) VALUES (?, ?) RETURNING id",
                        Long.class,
                        groupId,
                        "tarea-grupo");

        jdbcTemplate.update("INSERT INTO task_tags (task_id, tag_id) VALUES (?, ?)", taskId, tagId);

        jdbcTemplate.update(
                "INSERT INTO group_memberships (group_id, user_id, role) VALUES (?, ?, ?)",
                groupId,
                memberId,
                "VISITOR");

        // Eliminar el grupo
        int deleted = jdbcTemplate.update("DELETE FROM groups WHERE id = ?", groupId);
        assertThat(deleted).isEqualTo(1);

        // Tareas, etiquetas, task_tags y membresías del grupo deben eliminarse
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tasks WHERE group_id = ?",
                                Integer.class,
                                groupId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tags WHERE group_id = ?",
                                Integer.class,
                                groupId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM task_tags WHERE task_id = ?",
                                Integer.class,
                                taskId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM group_memberships WHERE group_id = ?",
                                Integer.class,
                                groupId))
                .isZero();

        // Los usuarios deben preservarse intactos
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM users WHERE id IN (?, ?)",
                                Integer.class,
                                ownerId,
                                memberId))
                .isEqualTo(2);
    }
}
