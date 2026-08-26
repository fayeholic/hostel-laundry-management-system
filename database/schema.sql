-- Hostel Laundry Management System: clean public-beta database schema.
CREATE DATABASE IF NOT EXISTS hostel_laundry_db;
USE hostel_laundry_db;

CREATE TABLE IF NOT EXISTS student (
    student_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    phone VARCHAR(20),
    hostel_block VARCHAR(50),
    room_number VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS machine (
    machine_id INT AUTO_INCREMENT PRIMARY KEY,
    machine_name VARCHAR(100) NOT NULL,
    hostel_block VARCHAR(50),
    status ENUM('AVAILABLE', 'IN_USE', 'MAINTENANCE') DEFAULT 'AVAILABLE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS booking (
    booking_id INT AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL,
    machine_id INT NOT NULL,
    booking_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    status ENUM('PENDING', 'ACTIVE', 'COMPLETED', 'CANCELLED') DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (student_id) REFERENCES student(student_id),
    FOREIGN KEY (machine_id) REFERENCES machine(machine_id)
);

CREATE TABLE IF NOT EXISTS complaint (
    complaint_id INT AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL,
    machine_id INT NOT NULL,
    complaint_text VARCHAR(500) NOT NULL,
    status ENUM('PENDING', 'IN_PROGRESS', 'RESOLVED') DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (student_id) REFERENCES student(student_id),
    FOREIGN KEY (machine_id) REFERENCES machine(machine_id)
);

-- Initial machines for public testing.
INSERT INTO machine (machine_name, hostel_block, status)
SELECT 'Washing Machine 1', 'Block A', 'AVAILABLE' WHERE NOT EXISTS (SELECT 1 FROM machine WHERE machine_name='Washing Machine 1');
INSERT INTO machine (machine_name, hostel_block, status)
SELECT 'Washing Machine 2', 'Block A', 'AVAILABLE' WHERE NOT EXISTS (SELECT 1 FROM machine WHERE machine_name='Washing Machine 2');
INSERT INTO machine (machine_name, hostel_block, status)
SELECT 'Washing Machine 3', 'Block A', 'IN_USE' WHERE NOT EXISTS (SELECT 1 FROM machine WHERE machine_name='Washing Machine 3');
INSERT INTO machine (machine_name, hostel_block, status)
SELECT 'Washing Machine 4', 'Block B', 'AVAILABLE' WHERE NOT EXISTS (SELECT 1 FROM machine WHERE machine_name='Washing Machine 4');
INSERT INTO machine (machine_name, hostel_block, status)
SELECT 'Washing Machine 5', 'Block B', 'MAINTENANCE' WHERE NOT EXISTS (SELECT 1 FROM machine WHERE machine_name='Washing Machine 5');
INSERT INTO machine (machine_name, hostel_block, status)
SELECT 'Washing Machine 6', 'Block B', 'AVAILABLE' WHERE NOT EXISTS (SELECT 1 FROM machine WHERE machine_name='Washing Machine 6');
