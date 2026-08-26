package com.hostellaundry.hostellaundrysystem;

public class StudentLoginTest {

    public static void main(String[] args) {

        System.out.println("=== LOGIN TEST ===");

        StudentDAO studentDAO = new StudentDAO();

        boolean loginSuccess = studentDAO.loginStudent(
                "testuser@test.com",
                "123456"
        );

        if (loginSuccess) {
            System.out.println("SUCCESS: Login successful!");
        } else {
            System.out.println("FAILED: Invalid email or password.");
        }
    }
}