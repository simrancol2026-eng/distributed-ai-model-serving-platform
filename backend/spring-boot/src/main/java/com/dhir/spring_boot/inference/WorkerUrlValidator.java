package com.dhir.spring_boot.inference;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Checks the configured worker URL when the application starts.
 *
 * A blank or malformed URL is a configuration mistake. Failing at startup with a
 * clear message is better than starting normally and then answering every
 * inference request with a confusing HTTP 500.
 */
public final class WorkerUrlValidator {

    private WorkerUrlValidator() {
    }

    /**
     * @return the URL without surrounding whitespace or a trailing slash
     * @throws IllegalStateException if the URL is not an absolute http(s) URL with a host
     */
    public static String validate(String property, String value) {
        String hint = " Set " + property + " (or the PLATFORM_WORKER_URL environment variable) "
                + "to an absolute http(s) URL, for example http://localhost:8001.";
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Invalid configuration: " + property + " is blank." + hint);
        }
        String trimmed = value.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(
                    "Invalid configuration: " + property + "='" + trimmed + "' is not a valid URL." + hint, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalStateException(
                    "Invalid configuration: " + property + "='" + trimmed + "' must start with http:// or https://."
                            + hint);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException(
                    "Invalid configuration: " + property + "='" + trimmed + "' has no host." + hint);
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
