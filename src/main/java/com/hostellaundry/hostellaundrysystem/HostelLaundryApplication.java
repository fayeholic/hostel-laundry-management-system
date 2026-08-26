package com.hostellaundry.hostellaundrysystem;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Scanner;

/** Command-line entry point for students to manage laundry reservations. */
public class HostelLaundryApplication {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private final Scanner input = new Scanner(System.in);
    private final StudentDAO students = new StudentDAO();
    private final MachineDAO machines = new MachineDAO();
    private final BookingDAO bookings = new BookingDAO();
    private final ComplaintDAO complaints = new ComplaintDAO();

    public static void main(String[] args) {
        new HostelLaundryApplication().run();
    }

    private void run() {
        if (!databaseIsReady()) return;
        System.out.println("\n=== HOSTEL LAUNDRY SYSTEM ===");
        while (true) {
            System.out.println("\n1. Register\n2. Log in\n0. Exit");
            switch (read("Choose an option: ")) {
                case "1" -> register();
                case "2" -> login();
                case "0" -> { System.out.println("Goodbye."); return; }
                default -> System.out.println("Please enter 0, 1, or 2.");
            }
        }
    }

    private boolean databaseIsReady() {
        try (Connection ignored = DatabaseConnection.getConnection()) { return true; }
        catch (SQLException e) {
            System.out.println("Cannot connect to the database: " + e.getMessage());
            System.out.println("Import HOSTELLAUNDRY_MYSQL.sql, start MySQL, then set DB_URL, DB_USER, and DB_PASSWORD if needed.");
            return false;
        }
    }

    private void register() {
        boolean created = students.registerStudent(read("Name: "), read("Email: "), read("Password: "),
                read("Phone: "), read("Hostel block: "), read("Room number: "));
        System.out.println(created ? "Registration successful. You can now log in." : "Registration failed (the email may already exist).");
    }

    private void login() {
        Integer studentId = students.authenticateStudent(read("Email: "), read("Password: "));
        if (studentId == null) { System.out.println("Invalid email or password."); return; }
        System.out.println("Login successful.");
        studentMenu(studentId);
    }

    private void studentMenu(int studentId) {
        while (true) {
            System.out.println("\n1. View machines\n2. Create booking\n3. View my bookings\n4. Cancel booking\n5. Submit complaint\n6. View my complaints\n0. Log out");
            switch (read("Choose an option: ")) {
                case "1" -> machines.displayMachines();
                case "2" -> createBooking(studentId);
                case "3" -> bookings.viewBookings(studentId);
                case "4" -> cancelBooking(studentId);
                case "5" -> submitComplaint(studentId);
                case "6" -> complaints.viewComplaints(studentId);
                case "0" -> { return; }
                default -> System.out.println("Invalid option.");
            }
        }
    }

    private void createBooking(int studentId) {
        Integer machineId = readPositiveInt("Machine ID: ");
        LocalDate date = readDate();
        LocalTime start = readTime("Start time (HH:mm): ");
        LocalTime end = readTime("End time (HH:mm): ");
        if (machineId == null || date == null || start == null || end == null) return;
        if (!end.isAfter(start)) { System.out.println("End time must be after start time."); return; }
        boolean created = bookings.createBooking(studentId, machineId, date.toString(), start.format(TIME_FORMAT), end.format(TIME_FORMAT));
        System.out.println(created ? "Booking created." : "Booking failed: machine is unavailable or the time overlaps an existing booking.");
    }

    private void cancelBooking(int studentId) {
        Integer id = readPositiveInt("Booking ID: ");
        if (id != null) System.out.println(bookings.cancelBooking(id, studentId) ? "Booking cancelled." : "No cancellable booking found.");
    }

    private void submitComplaint(int studentId) {
        Integer machineId = readPositiveInt("Machine ID: ");
        if (machineId == null) return;
        boolean created = complaints.createComplaint(studentId, machineId, read("Describe the problem: "));
        System.out.println(created ? "Complaint submitted." : "Complaint could not be submitted.");
    }

    private String read(String prompt) { System.out.print(prompt); return input.nextLine().trim(); }
    private Integer readPositiveInt(String prompt) {
        try { int value = Integer.parseInt(read(prompt)); if (value > 0) return value; }
        catch (NumberFormatException ignored) { }
        System.out.println("Enter a positive whole number."); return null;
    }
    private LocalDate readDate() {
        try { return LocalDate.parse(read("Date (YYYY-MM-DD): ")); }
        catch (DateTimeParseException e) { System.out.println("Use YYYY-MM-DD."); return null; }
    }
    private LocalTime readTime(String prompt) {
        try { return LocalTime.parse(read(prompt), TIME_FORMAT); }
        catch (DateTimeParseException e) { System.out.println("Use 24-hour HH:mm time."); return null; }
    }
}
