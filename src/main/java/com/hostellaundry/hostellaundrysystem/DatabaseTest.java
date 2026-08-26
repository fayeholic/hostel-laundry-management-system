package com.hostellaundry.hostellaundrysystem;

import java.sql.Connection;

public class DatabaseTest {

    public static void main(String[] args) {

        System.out.println("=== DATABASE TEST STARTED ===");

        try (Connection connection = DatabaseConnection.getConnection()) {
            System.out.println("SUCCESS: Java is connected to MySQL!");
            System.out.println("Connection closed.");
        } catch (Exception e) {
            System.out.println("FAILED: Could not connect to MySQL.");
            System.out.println(e.getMessage());
        }

        System.out.println("=== DATABASE TEST FINISHED ===");
    }
}
