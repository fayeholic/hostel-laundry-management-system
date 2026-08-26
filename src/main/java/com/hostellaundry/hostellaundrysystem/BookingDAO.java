package com.hostellaundry.hostellaundrysystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class BookingDAO {

    // =========================
    // CREATE BOOKING
    // =========================
    public boolean createBooking(
            int studentId,
            int machineId,
            String bookingDate,
            String startTime,
            String endTime) {

        if (!isMachineAvailable(machineId) || hasOverlappingBooking(machineId, bookingDate, startTime, endTime)) {
            return false;
        }

        String sql = """
                INSERT INTO booking
                (student_id, machine_id, booking_date, start_time, end_time, status)
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, studentId);
            statement.setInt(2, machineId);
            statement.setString(3, bookingDate);
            statement.setString(4, startTime);
            statement.setString(5, endTime);

            int rows = statement.executeUpdate();

            return rows > 0;

        } catch (SQLException e) {

            System.out.println("Booking failed!");
            e.printStackTrace();

            return false;
        }
    }

    private boolean isMachineAvailable(int machineId) {
        String sql = "SELECT 1 FROM machine WHERE machine_id = ? AND status = 'AVAILABLE'";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, machineId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            System.out.println("Unable to check machine availability: " + e.getMessage());
            return false;
        }
    }

    private boolean hasOverlappingBooking(int machineId, String bookingDate, String startTime, String endTime) {
        String sql = """
                SELECT 1 FROM booking
                WHERE machine_id = ? AND booking_date = ?
                  AND status IN ('PENDING', 'ACTIVE')
                  AND start_time < ? AND end_time > ?
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, machineId);
            statement.setString(2, bookingDate);
            statement.setString(3, endTime);
            statement.setString(4, startTime);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            System.out.println("Unable to check booking availability: " + e.getMessage());
            return true;
        }
    }


    // =========================
    // VIEW BOOKING HISTORY
    // =========================
    public void viewBookings(int studentId) {

        String sql = """
                SELECT
                    b.booking_id,
                    m.machine_name,
                    b.booking_date,
                    b.start_time,
                    b.end_time,
                    b.status
                FROM booking b
                JOIN machine m
                    ON b.machine_id = m.machine_id
                WHERE b.student_id = ?
                ORDER BY b.booking_date, b.start_time
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, studentId);

            ResultSet result = statement.executeQuery();

            System.out.println("=== MY BOOKINGS ===");

            boolean found = false;

            while (result.next()) {

                found = true;

                System.out.println(
                        "Booking ID: " + result.getInt("booking_id")
                        + " | Machine: " + result.getString("machine_name")
                        + " | Date: " + result.getDate("booking_date")
                        + " | Start: " + result.getTime("start_time")
                        + " | End: " + result.getTime("end_time")
                        + " | Status: " + result.getString("status")
                );
            }

            if (!found) {
                System.out.println("No bookings found.");
            }

        } catch (SQLException e) {

            System.out.println("Failed to retrieve bookings.");
            e.printStackTrace();
        }
    }


    // =========================
    // CANCEL BOOKING
    // =========================
    public boolean cancelBooking(int bookingId, int studentId) {

        String sql = """
                UPDATE booking
                SET status = 'CANCELLED'
                WHERE booking_id = ?
                AND student_id = ?
                AND status IN ('PENDING', 'ACTIVE')
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, bookingId);
            statement.setInt(2, studentId);

            int rows = statement.executeUpdate();

            return rows > 0;

        } catch (SQLException e) {

            System.out.println("Failed to cancel booking!");
            e.printStackTrace();

            return false;
        }
    }
}
