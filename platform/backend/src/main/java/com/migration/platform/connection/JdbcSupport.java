package com.migration.platform.connection;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Builds raw JDBC connections to source/target databases (shared by test + discovery). */
@Component
public class JdbcSupport {

    public Connection open(DbConnection c, String plaintextPassword) throws SQLException {
        return open(c.getDbType(), c.getHost(), c.getPort(), c.getDatabaseName(),
                c.getUsername(), plaintextPassword);
    }

    public Connection open(DbType type, String host, int port, String database,
                           String username, String password) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        if (type == DbType.SQLSERVER) {
            // TODO(#44): production must use encrypt=true + trusted certs.
            props.setProperty("encrypt", "false");
            props.setProperty("loginTimeout", "8");
        } else {
            props.setProperty("connectTimeout", "8");
        }
        return DriverManager.getConnection(buildUrl(type, host, port, database), props);
    }

    public String buildUrl(DbType type, String host, int port, String database) {
        return switch (type) {
            case SQLSERVER -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + database;
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
        };
    }
}
