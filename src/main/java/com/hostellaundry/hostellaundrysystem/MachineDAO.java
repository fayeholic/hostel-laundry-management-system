package com.hostellaundry.hostellaundrysystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class MachineDAO {

    public void displayMachines() {

        String sql = """
                SELECT machine_id, machine_name, hostel_block, status
                FROM machine
                ORDER BY machine_id
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            System.out.println("=== LAUNDRY MACHINES ===");

            while (result.next()) {

                int id = result.getInt("machine_id");
                String name = result.getString("machine_name");
                String block = result.getString("hostel_block");
                String status = result.getString("status");

                System.out.println(
                        id + " | "
                        + name + " | "
                        + block + " | "
                        + status
                );
            }

        } catch (SQLException e) {

            System.out.println("Failed to retrieve machines.");
            e.printStackTrace();
        }
    }
}