package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Plain unit tests (no Spring context): worker URL configuration is checked at startup. */
class WorkerUrlValidatorTest {

    static final String PROP = "platform.worker.url";

    @Test
    void acceptsHttpAndHttpsUrls() {
        assertEquals("http://localhost:8001", WorkerUrlValidator.validate(PROP, "http://localhost:8001"));
        assertEquals("https://worker.example:443", WorkerUrlValidator.validate(PROP, " https://worker.example:443 "));
    }

    @Test
    void removesTrailingSlash() {
        assertEquals("http://127.0.0.1:8001", WorkerUrlValidator.validate(PROP, "http://127.0.0.1:8001/"));
    }

    @Test
    void rejectsBlankAndMissingValues() {
        for (String bad : new String[] { null, "", "   " }) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> WorkerUrlValidator.validate(PROP, bad));
            assertTrue(e.getMessage().contains("is blank"), e.getMessage());
            assertTrue(e.getMessage().contains("PLATFORM_WORKER_URL"), e.getMessage());
        }
    }

    @Test
    void rejectsRelativeAndNonHttpUrls() {
        for (String bad : new String[] { "localhost:8001", "/predict", "ftp://host:21", "worker-a" }) {
            assertThrows(IllegalStateException.class, () -> WorkerUrlValidator.validate(PROP, bad), bad);
        }
    }

    @Test
    void rejectsUrlWithoutHost() {
        assertThrows(IllegalStateException.class, () -> WorkerUrlValidator.validate(PROP, "http://"));
        assertThrows(IllegalStateException.class, () -> WorkerUrlValidator.validate(PROP, "http:///predict"));
    }

    @Test
    void rejectsSyntacticallyInvalidUrl() {
        assertThrows(IllegalStateException.class, () -> WorkerUrlValidator.validate(PROP, "http://bad host:8001"));
    }

    @Test
    void workerPoolRefusesToBeCreatedWithBlankUrl() {
        // This is the reported bug: PLATFORM_WORKER_URL= (empty) used to start fine and then return 500s.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new WorkerPool("", "TEXT", 2000, 30000));
        assertTrue(e.getMessage().startsWith("Invalid configuration"), e.getMessage());
    }

    @Test
    void rejectsCredentialsInUrl() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WorkerUrlValidator.validate(PROP, "http://user:secret@localhost:8001"));
        assertTrue(!e.getMessage().contains("secret"), "the password must not be echoed: " + e.getMessage());
    }
}
