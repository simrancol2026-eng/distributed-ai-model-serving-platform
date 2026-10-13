package com.dhir.spring_boot.registry;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Arrays;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logs, once at startup, which database engine the registry is using, so test evidence can show
 * "PostgreSQL 16.x" rather than the in-memory H2 used by unit tests.
 * Logs only the product name/version and active profiles: never the JDBC URL, user or password.
 */
@Component
public class DatasourceInfoLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasourceInfoLogger.class);

    private final DataSource dataSource;
    private final Environment environment;

    public DatasourceInfoLogger(DataSource dataSource, Environment environment) {
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            String[] profiles = environment.getActiveProfiles();
            log.info("registry datasource: product={} version={} activeProfiles={}", md.getDatabaseProductName(),
                    md.getDatabaseProductVersion(), profiles.length == 0 ? "[default]" : Arrays.toString(profiles));
        }
    }
}
