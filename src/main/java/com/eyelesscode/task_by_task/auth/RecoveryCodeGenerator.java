package com.eyelesscode.task_by_task.auth;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RecoveryCodeGenerator {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 10;
    private static final int CODE_COUNT = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodeGenerator() {}

    public static List<String> generateCodes() {
        Set<String> codes = new LinkedHashSet<>(CODE_COUNT);
        while (codes.size() < CODE_COUNT) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            codes.add(sb.toString());
        }
        return List.copyOf(codes);
    }

    public static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
