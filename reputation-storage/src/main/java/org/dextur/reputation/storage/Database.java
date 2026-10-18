package org.dextur.reputation.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.dextur.reputation.application.Settings;

/** Open and close on database workers. Never logs the JDBC URL or credentials. */
public final class Database implements AutoCloseable {
    private final AtomicReference<HikariDataSource> pool = new AtomicReference<>();
    private java.sql.Connection ownership;
    private String ownershipKey;
    private boolean ownershipValid;

    public void open(Settings.Database settings, Map<String, String> environment) throws Exception {
        String password = environment.get(settings.passwordEnvironment());
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Set database password variable: " + settings.passwordEnvironment());
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("DexturReputation");
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setJdbcUrl("jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/" + settings.schema()
                + "?sslMode=" + settings.sslMode() + "&connectTimeout=" + settings.connectionTimeoutMs()
                + "&socketTimeout=10000&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        config.setUsername(settings.username());
        config.setPassword(password);
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(settings.connectionTimeoutMs());
        config.setValidationTimeout(Math.min(1000, settings.connectionTimeoutMs()));
        config.setMaxLifetime(settings.maxLifetimeMs());
        config.setInitializationFailTimeout(-1);
        HikariDataSource created = new HikariDataSource(config);
        if (!pool.compareAndSet(null, created)) { created.close(); throw new IllegalStateException("Database already open"); }
        try { new SchemaMigrator().migrate(created); }
        catch (Exception e) { close(); throw e; }
    }

    public javax.sql.DataSource dataSource() {
        HikariDataSource value = pool.get();
        if (value == null) throw new IllegalStateException("Database not open");
        return value;
    }
    /** Dedicated ownership connection; does not consume the configured request pool. */
    synchronized void claimExclusive() throws Exception {
        HikariDataSource source=pool.get();
        var connection=java.sql.DriverManager.getConnection(source.getJdbcUrl(),source.getUsername(),source.getPassword());
        try {
            String key="dr-owner-"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(connection.getCatalog().getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,48);
            try(var statement=connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
                statement.setString(1,key);
                try(var result=statement.executeQuery()) { if(!result.next()||result.getInt(1)!=1) throw new java.sql.SQLException("Storage already owned; stop all servers before maintenance"); }
            }
            ownership=connection;ownershipKey=key;ownershipValid=true;
        } catch(Exception e) { connection.close();throw e; }
    }
    synchronized void ensureOwned() throws java.sql.SQLException {
        if(!ownershipValid||ownership==null) throw new java.sql.SQLException("Storage ownership lost; restart explicitly");
        try(var statement=ownership.prepareStatement("SELECT IS_USED_LOCK(?)=CONNECTION_ID()")) {
            statement.setString(1,ownershipKey);
            try(var result=statement.executeQuery()) {
                if(!result.next()||!result.getBoolean(1)||result.wasNull()) throw new java.sql.SQLException("Storage ownership lost");
            }
        } catch(java.sql.SQLException e) { ownershipValid=false;throw e; }
    }
    @Override public synchronized void close() {
        try {
            if(ownership!=null) ownership.close();
        } catch(java.sql.SQLException e) { throw new IllegalStateException("Ownership connection close failed",e); }
        finally {
            ownership=null;ownershipValid=false;
            HikariDataSource value = pool.getAndSet(null);
            if (value != null) value.close();
        }
    }
}
