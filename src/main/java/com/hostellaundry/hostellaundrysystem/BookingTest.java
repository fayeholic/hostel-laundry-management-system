package com.hostellaundry.hostellaundrysystem;

public class BookingTest {

    public static void main(String[] args) {

        System.out.println("=== CANCEL BOOKING TEST ===");

        BookingDAO bookingDAO = new BookingDAO();

        // Change these numbers if your IDs are different
        int bookingId = 1;
        int studentId = 1;

        boolean cancelled =
                bookingDAO.cancelBooking(bookingId, studentId);

        if (cancelled) {
            System.out.println("SUCCESS: Booking cancelled!");
        } else {
            System.out.println("FAILED: Booking could not be cancelled.");
        }

        System.out.println("=== TEST FINISHED ===");
    }
}