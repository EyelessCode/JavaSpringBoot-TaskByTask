package com.eyelesscode.task_by_task.shared.ratelimit;

public class RateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitException(long retryAfterSeconds) {
        super(formatMessage(retryAfterSeconds));
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public RateLimitException(long retryAfterSeconds, String customMessage) {
        super(customMessage);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String formatMessage(long seconds) {
        return "Demasiadas solicitudes. Ha superado el límite de intentos permitidos. Intente"
                + " nuevamente en "
                + Math.max(1, seconds)
                + " segundos.";
    }
}
