package com.hostellaundry.hostellaundrysystem;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {

    private static final String URL = databaseUrl();
    private static final String USER = setting("DB_USER", setting("MYSQLUSER", ""));
    private static final String PASSWORD = setting("DB_PASSWORD", setting("MYSQLPASSWORD", ""));

    public static Connection getConnection() throws SQLException {
        if (USER.isBlank() || PASSWORD.isBlank()) {
            throw new SQLException("Database credentials are not configured. Set DB_USER/DB_PASSWORD or Railway MySQL variables.");
        }
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static String setting(String name, String defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(name);
        }
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** Supports both local MySQL settings and Railway's managed MySQL variables. */
    private static String databaseUrl() {
        String supplied = setting("DB_URL", "");
        if (!supplied.isBlank()) {
            return supplied.startsWith("mysql://") ? "jdbc:" + supplied : supplied;
        }
        String host = setting("MYSQLHOST", "");
        if (!host.isBlank()) {
            String port = setting("MYSQLPORT", "3306");
            String database = setting("MYSQLDATABASE", "hostel_laundry_db");
            return "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=true&requireSSL=true";
        }
        return "jdbc:mysql://localhost:3306/hostel_laundry_db";
    }
}
