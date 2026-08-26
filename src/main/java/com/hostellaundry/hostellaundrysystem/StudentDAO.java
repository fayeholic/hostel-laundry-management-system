package com.hostellaundry.hostellaundrysystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class StudentDAO {

    // Register a new student
    public boolean registerStudent(
            String name,
            String email,
            String password,
            String phone,
            String hostelBlock,
            String roomNumber) {

        String sql = """
                INSERT INTO student
                (name, email, password, phone, hostel_block, room_number)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, name);
            statement.setString(2, email);
            statement.setString(3, password);
            statement.setString(4, phone);
            statement.setString(5, hostelBlock);
            statement.setString(6, roomNumber);

            int rows = statement.executeUpdate();

            return rows > 0;

        } catch (SQLException e) {
            System.out.println("Registration failed!");
            e.printStackTrace();
            return false;
        }
    }

    // Login student
    public boolean loginStudent(String email, String password) {
        return authenticateStudent(email, password) != null;
    }

    public Integer authenticateStudent(String email, String password) {

        String sql = """
                SELECT student_id
                FROM student
                WHERE email = ? AND password = ?
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, email);
            statement.setString(2, password);

            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt("student_id") : null;
            }

        } catch (SQLException e) {
            System.out.println("Login failed!");
            e.printStackTrace();
            return null;
        }
    }
}
