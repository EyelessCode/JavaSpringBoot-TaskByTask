package com.eyelesscode.task_by_task.shared.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlLikePatternTest {

    @Test
    @DisplayName("ESCAPE_CHAR debe ser '!' y escape() debe neutralizar !, %, _ preservando \\")
    void shouldEscapeWildcardsAndExclamationMarkUsingExclamationEscapeChar() {
        assertThat(SqlLikePattern.ESCAPE_CHAR).isEqualTo('!');
        assertThat(SqlLikePattern.escape("100%_done!\\test")).isEqualTo("100!%!_done!!\\test");
        assertThat(SqlLikePattern.escape("normal")).isEqualTo("normal");
    }

    @Test
    @DisplayName("containsIgnoreCase y startsWithIgnoreCase deben convertir a minúsculas y escapar")
    void shouldBuildContainsAndStartsWithPatternsCaseInsensitively() {
        assertThat(SqlLikePattern.containsIgnoreCase("50%_OFF!\\")).isEqualTo("%50!%!_off!!\\%");
        assertThat(SqlLikePattern.containsIgnoreCase("50%_OFF!")).isEqualTo("%50!%!_off!!%");
        assertThat(SqlLikePattern.startsWithIgnoreCase("Urg_100%!")).isEqualTo("urg!_100!%!!%");
    }

    @Test
    @DisplayName("Debe rechazar entradas nulas con NullPointerException")
    void shouldRejectNullInputs() {
        assertThatThrownBy(() -> SqlLikePattern.escape(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SqlLikePattern.containsIgnoreCase(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SqlLikePattern.startsWithIgnoreCase(null))
                .isInstanceOf(NullPointerException.class);
    }
}
