package org.dextur.reputation.storage;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MariaDbIntegrationTest extends DatabaseContract {
    @Container static final MariaDBContainer<?> DATABASE = new MariaDBContainer<>("mariadb:11.4.8")
            .withDatabaseName("reputation").withUsername("reputation").withPassword("isolated-test-only");
    @Override JdbcDatabaseContainer<?> container() { return DATABASE; }
}
