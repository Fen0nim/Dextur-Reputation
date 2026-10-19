package org.dextur.reputation.storage;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
@Testcontainers
class ProtectionMariaDbTest extends ProtectionDatabaseContract {
    @org.testcontainers.junit.jupiter.Container static final MariaDBContainer<?> DATABASE=new MariaDBContainer<>("mariadb:11.4.8").withDatabaseName("reputation").withUsername("reputation").withPassword("isolated-test-only");
    @Override JdbcDatabaseContainer<?> container() { return DATABASE; }
}
