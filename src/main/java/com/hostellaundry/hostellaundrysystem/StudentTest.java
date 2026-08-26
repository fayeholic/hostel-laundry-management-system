package com.hostellaundry.hostellaundrysystem;

public class StudentTest {

    public static void main(String[] args) {

        System.out.println("=== STUDENT TEST STARTED ===");

        StudentDAO studentDAO = new StudentDAO();

        boolean registered = studentDAO.registerStudent(
                "Test User",
                "testuser@test.com",
                "123456",
                "0123456789",
                "Block A",
                "A102"
        );

        if (registered) {
            System.out.println("SUCCESS: Student registered!");
        } else {
            System.out.println("FAILED: Student registration.");
        }

        System.out.println("=== STUDENT TEST FINISHED ===");
    }
}