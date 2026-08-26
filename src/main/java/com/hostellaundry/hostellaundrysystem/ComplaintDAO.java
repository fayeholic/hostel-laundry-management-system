package com.hostellaundry.hostellaundrysystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class ComplaintDAO {

    // =========================
    // CREATE COMPLAINT
    // =========================
    public boolean createComplaint(
            int studentId,
            int machineId,
            String complaintText) {

        String sql = """
                INSERT INTO complaint
                (student_id, machine_id, complaint_text, status)
                VALUES (?, ?, ?, 'PENDING')
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, studentId);
            statement.setInt(2, machineId);
            statement.setString(3, complaintText);

            int rows = statement.executeUpdate();

            return rows > 0;

        } catch (SQLException e) {

            System.out.println("Failed to submit complaint!");
            e.printStackTrace();

            return false;
        }
    }


    // =========================
    // VIEW STUDENT COMPLAINTS
    // =========================
    public void viewComplaints(int studentId) {

        String sql = """
                SELECT
                    c.complaint_id,
                    m.machine_name,
                    c.complaint_text,
                    c.status,
                    c.created_at
                FROM complaint c
                JOIN machine m
                    ON c.machine_id = m.machine_id
                WHERE c.student_id = ?
                ORDER BY c.created_at DESC
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, studentId);

            ResultSet result = statement.executeQuery();

            System.out.println("=== MY COMPLAINTS ===");

            boolean found = false;

            while (result.next()) {

                found = true;

                System.out.println(
                        "Complaint ID: "
                        + result.getInt("complaint_id")

                        + " | Machine: "
                        + result.getString("machine_name")

                        + " | Problem: "
                        + result.getString("complaint_text")

                        + " | Status: "
                        + result.getString("status")

                        + " | Date: "
                        + result.getTimestamp("created_at")
                );
            }

            if (!found) {
                System.out.println("No complaints found.");
            }

        } catch (SQLException e) {

            System.out.println("Failed to retrieve complaints!");
            e.printStackTrace();
        }
    }


    // =========================
    // UPDATE COMPLAINT STATUS
    // =========================
    public boolean updateComplaintStatus(
            int complaintId,
            String newStatus) {

        String sql = """
                UPDATE complaint
                SET status = ?
                WHERE complaint_id = ?
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, newStatus);
            statement.setInt(2, complaintId);

            int rows = statement.executeUpdate();

            return rows > 0;

        } catch (SQLException e) {

            System.out.println("Failed to update complaint status!");
            e.printStackTrace();

            return false;
        }
    }
}