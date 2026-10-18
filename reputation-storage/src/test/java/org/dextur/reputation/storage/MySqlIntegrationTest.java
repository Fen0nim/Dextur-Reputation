package org.dextur.reputation.storage;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MySqlIntegrationTest extends DatabaseContract {
    @Container static final MySQLContainer<?> DATABASE = new MySQLContainer<>("mysql:8.4.7")
            .withDatabaseName("reputation").withUsername("reputation").withPassword("isolated-test-only");
    @Override JdbcDatabaseContainer<?> container() { return DATABASE; }
}
