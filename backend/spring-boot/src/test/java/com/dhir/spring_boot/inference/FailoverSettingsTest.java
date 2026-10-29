package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;

class FailoverSettingsTest {
    private AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(FailoverSettings.class);
        return context;
    }

    @Test void defaultsAreOptInWithTenSecondBudget() {
        try (var context = context(Map.of())) {
            context.refresh();
            assertEquals(new FailoverSettings(false, 10000), context.getBean(FailoverSettings.class));
        }
    }

    @Test void enabledOverrideIsReadAtStartup() {
        try (var context = context(Map.of("worker.failover.enabled", "true", "worker.failover.budget-ms", "1200"))) {
            context.refresh();
            assertEquals(new FailoverSettings(true, 1200), context.getBean(FailoverSettings.class));
        }
    }

    @Test void invalidBudgetsFailEvenWhenDisabled() {
        for (String value : new String[] {"0", "-1", "no", Long.toString(Long.MAX_VALUE)}) {
            try (var context = context(Map.of("worker.failover.budget-ms", value))) {
                assertThrows(RuntimeException.class, context::refresh);
            }
        }
    }
}
