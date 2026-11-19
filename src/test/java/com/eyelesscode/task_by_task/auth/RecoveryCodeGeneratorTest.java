package com.eyelesscode.task_by_task.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eyelesscode.task_by_task.shared.security.SecurityUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class RecoveryCodeGeneratorTest {

    private static final Pattern RECOVERY_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{10}$");

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName(
            "Debe generar exactamente 8 códigos únicos de 10 caracteres alfanuméricos en"
                    + " mayúsculas")
    void shouldGenerateEightUniqueTenCharacterUppercaseAlphanumericCodes() {
        List<String> codes = RecoveryCodeGenerator.generateCodes();

        assertThat(codes).hasSize(8);
        assertThat(new HashSet<>(codes)).hasSize(8);
        for (String code : codes) {
            assertThat(code).matches(RECOVERY_CODE_PATTERN);
        }
    }

    @Test
    @DisplayName(
            "Debe generar conjuntos con alta entropía sin repetición entre invocaciones sucesivas")
    void shouldGenerateDistinctSetsAcrossMultipleInvocations() {
        Set<String> allGenerated = new HashSet<>();
        int batches = 20;

        for (int i = 0; i < batches; i++) {
            List<String> codes = RecoveryCodeGenerator.generateCodes();
            assertThat(codes).hasSize(8);
            allGenerated.addAll(codes);
        }

        assertThat(allGenerated).hasSize(batches * 8);
    }

    @Test
    @DisplayName("Debe retornar una lista inmutable al generar códigos")
    void shouldReturnUnmodifiableList() {
        List<String> codes = RecoveryCodeGenerator.generateCodes();

        assertThatThrownBy(() -> codes.add("ABCDEFGHIJ"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Debe normalizar códigos recortando espacios y convirtiendo a mayúsculas (EC-11)")
    void shouldNormalizeCodeWithTrimAndUppercase() {
        assertThat(RecoveryCodeGenerator.normalizeCode("  ab12cd34ef  ")).isEqualTo("AB12CD34EF");
        assertThat(RecoveryCodeGenerator.normalizeCode("9z8y7x6w5v")).isEqualTo("9Z8Y7X6W5V");
        assertThat(RecoveryCodeGenerator.normalizeCode("   ")).isEmpty();
        assertThat(RecoveryCodeGenerator.normalizeCode(null)).isEmpty();
    }

    @Test
    @DisplayName("SecurityUtils debe extraer el userId numérico cuando el usuario está autenticado")
    void shouldExtractCurrentUserIdWhenAuthenticated() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                        "42", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(SecurityUtils.getCurrentUserId()).isEqualTo(42L);
    }

    @Test
    @DisplayName(
            "SecurityUtils debe lanzar AccessDeniedException si no hay autenticación o es anónima")
    void shouldThrowAccessDeniedExceptionWhenUnauthenticatedOrAnonymous() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(SecurityUtils::getCurrentUserId)
                .isInstanceOf(AccessDeniedException.class);

        UsernamePasswordAuthenticationToken anon =
                new UsernamePasswordAuthenticationToken("anonymousUser", null);
        SecurityContextHolder.getContext().setAuthentication(anon);
        assertThatThrownBy(SecurityUtils::getCurrentUserId)
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName(
            "SecurityUtils debe lanzar AccessDeniedException si el nombre del principal no es"
                    + " numérico")
    void shouldThrowAccessDeniedExceptionWhenPrincipalNameIsNotNumeric() {
        UsernamePasswordAuthenticationToken invalidAuth =
                new UsernamePasswordAuthenticationToken(
                        "not-a-number", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(invalidAuth);

        assertThatThrownBy(SecurityUtils::getCurrentUserId)
                .isInstanceOf(AccessDeniedException.class);
    }
}
