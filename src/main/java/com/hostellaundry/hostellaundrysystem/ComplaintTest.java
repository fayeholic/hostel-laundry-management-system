package com.hostellaundry.hostellaundrysystem;

public class ComplaintTest {

    public static void main(String[] args) {

        System.out.println("=== COMPLAINT STATUS TEST ===");

        ComplaintDAO complaintDAO = new ComplaintDAO();

        boolean updated = complaintDAO.updateComplaintStatus(
                1,
                "IN_PROGRESS"
        );

        if (updated) {
            System.out.println("SUCCESS: Complaint status updated!");
        } else {
            System.out.println("FAILED: Complaint status was not updated.");
        }

        System.out.println();
        System.out.println("=== COMPLAINT HISTORY ===");

        complaintDAO.viewComplaints(1);

        System.out.println("=== TEST FINISHED ===");
    }
}