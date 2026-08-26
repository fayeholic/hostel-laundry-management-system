package com.hostellaundry.hostellaundrysystem;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {

    private static final String URL = setting("DB_URL", "jdbc:mysql://localhost:3306/hostel_laundry_db");
    private static final String USER = setting("DB_USER", "");
    private static final String PASSWORD = setting("DB_PASSWORD", "");

    public static Connection getConnection() throws SQLException {
        if (USER.isBlank() || PASSWORD.isBlank()) {
            throw new SQLException("Database credentials are not configured. Set DB_USER and DB_PASSWORD.");
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
}
