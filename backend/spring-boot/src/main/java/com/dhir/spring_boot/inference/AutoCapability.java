package com.dhir.spring_boot.inference;

import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative input dispatch, not a medical image detector or programming-language model. */
final class AutoCapability {
    private AutoCapability() {}

    private static final Pattern CODE = Pattern.compile(
            "(?m)^\\s*(?:def\\s+\\w+\\s*\\(|(?:async\\s+)?function\\s+\\w+\\s*\\(|"
            + "(?:public\\s+)?(?:class|interface)\\s+\\w+|#include\\s*[<\\\"]|"
            + "(?:const|let|var)\\s+\\w+\\s*=|(?:fn|func)\\s+\\w+\\s*\\(|"
            + "(?:SELECT|select)\\s+.+\\s+(?:FROM|from)\\s+|#!\\s*/(?:usr/)?bin/)");

    static String taskCapability(String task) {
        if (task == null || task.isBlank()) return null;
        return switch (task.trim().toLowerCase(Locale.ROOT)) {
            case "sentiment-analysis" -> "TEXT";
            case "code-language" -> "CODE";
            case "image-classification" -> "IMAGE";
            case "chest-xray" -> "HEALTHCARE";
            default -> throw new IllegalArgumentException("Field 'task' must be sentiment-analysis, code-language, image-classification or chest-xray.");
        };
    }

    static String detect(String input) {
        String value = input.stripLeading();
        // Full image decoding, format and pixel limits are enforced by the image worker.
        if (value.startsWith("data:image/png;base64,") || value.startsWith("data:image/jpeg;base64,")
                || value.startsWith("iVBORw0KGgo") || value.startsWith("/9j/")) return "IMAGE";
        if (value.startsWith("data:")) return null;
        String sample = value.substring(0, Math.min(value.length(), InferenceController.MAX_INPUT_CHARS));
        if (CODE.matcher(sample).find()) return "CODE";
        // Snippets without enough syntax evidence require a user-selected task instead of guessing.
        if (sample.contains("```") || sample.matches("(?s).*[{};=<>].*")
                || sample.matches("(?s).*\\b\\w+\\s*\\(.*")) return null;
        return "TEXT";
    }

    static boolean imageCapability(String capability) {
        return "IMAGE".equals(capability) || "HEALTHCARE".equals(capability);
    }
}

