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
        String hint = " Set " + property + " (environment variable PLATFORM_WORKER_URL for one worker, or "
                + "PLATFORM_WORKER_URLS as a comma-separated list) to absolute http(s) URLs, "
                + "for example http://localhost:8001.";
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
        if (uri.getRawUserInfo() != null) {
            // Credentials in the URL would end up in logs and error messages.
            throw new IllegalStateException(
                    "Invalid configuration: " + property + " must not contain a user name or password." + hint);
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** "host:port" of a validated URL, with the scheme's default port filled in. Safe to log. */
    public static String endpoint(String validatedUrl) {
        URI uri = URI.create(validatedUrl);
        int port = uri.getPort() != -1 ? uri.getPort() : (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80);
        return uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }

    /**
     * Identity used to detect the same worker configured twice: scheme + host + port + path,
     * case-insensitive, default port filled in. (Two different host names that resolve to the
     * same machine, e.g. localhost and 127.0.0.1, cannot be detected this way.)
     */
    public static String identity(String validatedUrl) {
        URI uri = URI.create(validatedUrl);
        String path = uri.getPath() == null ? "" : uri.getPath();
        return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + endpoint(validatedUrl) + path;
    }
}
