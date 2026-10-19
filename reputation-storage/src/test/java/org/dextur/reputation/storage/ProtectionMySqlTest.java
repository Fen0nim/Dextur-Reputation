package org.dextur.reputation.storage;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
@Testcontainers
class ProtectionMySqlTest extends ProtectionDatabaseContract {
    @org.testcontainers.junit.jupiter.Container static final MySQLContainer<?> DATABASE=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("reputation").withUsername("reputation").withPassword("isolated-test-only");
    @Override JdbcDatabaseContainer<?> container() { return DATABASE; }
}
