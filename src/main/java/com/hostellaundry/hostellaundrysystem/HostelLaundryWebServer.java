package com.hostellaundry.hostellaundrysystem;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Small dependency-free web server for the Hostel Laundry Management System. */
public final class HostelLaundryWebServer {
    private static final String INITIAL_ADMIN_PASSWORD = setting("ADMIN_INITIAL_PASSWORD", "");
    private static final Map<String, UserSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, Long> RESET_REQUEST_LIMIT = new ConcurrentHashMap<>();
    private static final SecureRandom PASSWORD_RANDOM = new SecureRandom();
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int MAX_REQUEST_BODY_BYTES = 65_536;

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(setting("PORT", "8080"));
        try { ensureSupportTables(); } catch (SQLException e) { System.out.println("Database setup check failed: " + e.getMessage()); }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", HostelLaundryWebServer::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.printf("Hostel Laundry System is running at http://localhost:%d%n", port);
    }

    private static void ensureSupportTables() throws SQLException {
        try (Connection c = DatabaseConnection.getConnection()) {
            String[] schema = {
                "CREATE TABLE IF NOT EXISTS student (student_id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, email VARCHAR(100) UNIQUE NOT NULL, password VARCHAR(255) NOT NULL, phone VARCHAR(20), hostel_block VARCHAR(50), room_number VARCHAR(20), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS machine (machine_id INT AUTO_INCREMENT PRIMARY KEY, machine_name VARCHAR(100) NOT NULL, hostel_block VARCHAR(50), status ENUM('AVAILABLE','IN_USE','MAINTENANCE') DEFAULT 'AVAILABLE', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS booking (booking_id INT AUTO_INCREMENT PRIMARY KEY, student_id INT NOT NULL, machine_id INT NOT NULL, booking_date DATE NOT NULL, start_time TIME NOT NULL, end_time TIME NOT NULL, status ENUM('PENDING','ACTIVE','COMPLETED','CANCELLED') DEFAULT 'PENDING', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (student_id) REFERENCES student(student_id), FOREIGN KEY (machine_id) REFERENCES machine(machine_id))",
                "CREATE TABLE IF NOT EXISTS complaint (complaint_id INT AUTO_INCREMENT PRIMARY KEY, student_id INT NOT NULL, machine_id INT NOT NULL, complaint_text VARCHAR(500) NOT NULL, status ENUM('PENDING','IN_PROGRESS','RESOLVED') DEFAULT 'PENDING', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (student_id) REFERENCES student(student_id), FOREIGN KEY (machine_id) REFERENCES machine(machine_id))",
                "CREATE TABLE IF NOT EXISTS admin (admin_id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, email VARCHAR(100) UNIQUE NOT NULL, password VARCHAR(255) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS notification (notification_id INT AUTO_INCREMENT PRIMARY KEY, student_id INT NOT NULL, message VARCHAR(500) NOT NULL, is_read BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (student_id) REFERENCES student(student_id) ON DELETE CASCADE)",
                "CREATE TABLE IF NOT EXISTS password_reset_request (request_id INT AUTO_INCREMENT PRIMARY KEY, student_id INT NOT NULL, status ENUM('PENDING','COMPLETED') NOT NULL DEFAULT 'PENDING', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, completed_at TIMESTAMP NULL, FOREIGN KEY (student_id) REFERENCES student(student_id) ON DELETE CASCADE)"
            };
            for (String statement : schema) try (PreparedStatement table = c.prepareStatement(statement)) { table.execute(); }
            try (PreparedStatement index = c.prepareStatement("CREATE INDEX idx_booking_machine_time ON booking(machine_id,booking_date,start_time,end_time,status)")) { index.execute(); } catch (SQLException ignored) { /* index already exists */ }
            applyRequestedFacilityLayout(c);
            String[][] machines = {{"Washing Machine 1","Block A","AVAILABLE"},{"Washing Machine 2","Block A","IN_USE"},{"Washing Machine 3","Block A","AVAILABLE"},{"Dryer 1","Block A","MAINTENANCE"},{"Dryer 2","Block A","AVAILABLE"},{"Dryer 3","Block A","AVAILABLE"}};
            for (String[] machine : machines) try (PreparedStatement seedMachine = c.prepareStatement("INSERT INTO machine(machine_name,hostel_block,status) SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM machine WHERE machine_name=?)")) { seedMachine.setString(1,machine[0]); seedMachine.setString(2,machine[1]); seedMachine.setString(3,machine[2]); seedMachine.setString(4,machine[0]); seedMachine.executeUpdate(); }
            removeUnusedMachineDuplicates(c, machines);
            if (!blank(INITIAL_ADMIN_PASSWORD)) {
                try (PreparedStatement seed = c.prepareStatement("INSERT IGNORE INTO admin(name,email,password) VALUES('Laundry Administrator','admin@hostellaundry.com',?)")) { seed.setString(1, hash(INITIAL_ADMIN_PASSWORD)); seed.executeUpdate(); }
            }
        }
    }

    /** Applies the approved six-machine laundry-room layout to existing databases once it starts. */
    private static void applyRequestedFacilityLayout(Connection c) throws SQLException {
        String[][] renamed = {
            {"1", "Washing Machine 1"}, {"2", "Dryer 3"}, {"3", "Washing Machine 2"},
            {"4", "Washing Machine 3"}, {"5", "Dryer 1"}, {"6", "Dryer 2"}
        };
        for (String[] change : renamed) {
            try (PreparedStatement rename = c.prepareStatement("UPDATE machine SET machine_name=?, hostel_block='Block A' WHERE machine_id=?")) {
                rename.setString(1, change[1]); rename.setInt(2, Integer.parseInt(change[0])); rename.executeUpdate();
            }
        }
        // A previous layout update may have created a second "Washing Machine 2".
        // Remove only that extra row when it has no booking or complaint history.
        try (PreparedStatement duplicate = c.prepareStatement(
                "DELETE FROM machine WHERE machine_id > 6 AND machine_name='Washing Machine 2' "
                + "AND NOT EXISTS (SELECT 1 FROM booking WHERE booking.machine_id=machine.machine_id) "
                + "AND NOT EXISTS (SELECT 1 FROM complaint WHERE complaint.machine_id=machine.machine_id)")) {
            duplicate.executeUpdate();
        }
    }

    /** Removes only duplicate seed records that have no booking or complaint history. */
    private static void removeUnusedMachineDuplicates(Connection c, String[][] machines) throws SQLException {
        for (String[] machine : machines) {
            String ids = "SELECT machine_id FROM machine WHERE machine_name=? ORDER BY machine_id";
            List<Integer> duplicates = new ArrayList<>();
            try (PreparedStatement find = c.prepareStatement(ids)) {
                find.setString(1, machine[0]);
                try (ResultSet rows = find.executeQuery()) {
                    boolean keepFirst = true;
                    while (rows.next()) {
                        int id = rows.getInt(1);
                        if (keepFirst) { keepFirst = false; continue; }
                        duplicates.add(id);
                    }
                }
            }
            for (int id : duplicates) try (PreparedStatement remove = c.prepareStatement("DELETE FROM machine WHERE machine_id=? AND NOT EXISTS (SELECT 1 FROM booking WHERE machine_id=?) AND NOT EXISTS (SELECT 1 FROM complaint WHERE machine_id=?)")) {
                remove.setInt(1, id); remove.setInt(2, id); remove.setInt(3, id); remove.executeUpdate();
            }
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) { serveFile(exchange, "web/index.html", "text/html; charset=utf-8"); return; }
            if (path.equals("/app.css")) { serveFile(exchange, "web/app.css", "text/css; charset=utf-8"); return; }
            if (path.equals("/responsive.css")) { serveFile(exchange, "web/responsive.css", "text/css; charset=utf-8"); return; }
            if (path.equals("/app.js")) { serveFile(exchange, "web/app.js", "application/javascript; charset=utf-8"); return; }
            if (path.equals("/background.mp4")) { serveBinaryFile(exchange, "web/background.mp4", "video/mp4"); return; }
            if (path.equals("/laundry-room.jpg")) { serveBinaryFile(exchange, "web/laundry-room.jpg", "image/jpeg"); return; }
            if (path.equals("/laundry-room.png")) { serveBinaryFile(exchange, "web/laundry-room.png", "image/png"); return; }
            if (path.equals("/login-poster.png")) { serveBinaryFile(exchange, "web/login-poster.png", "image/png"); return; }
            if (!path.startsWith("/api/")) { send(exchange, 404, "text/plain", "Not found"); return; }
            api(exchange, path);
        } catch (SQLException e) {
            sendJson(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"Server error\"}");
        }
    }

    private static void api(HttpExchange ex, String path) throws IOException, SQLException {
        Map<String, String> form = form(ex);
        if (path.equals("/api/login") && ex.getRequestMethod().equals("POST")) { login(ex, form); return; }
        if (path.equals("/api/register") && ex.getRequestMethod().equals("POST")) { register(ex, form); return; }
        if (path.equals("/api/forgot-password") && ex.getRequestMethod().equals("POST")) { requestPasswordReset(ex, form); return; }
        if (path.equals("/api/logout") && ex.getRequestMethod().equals("POST")) { SESSIONS.remove(cookie(ex, "session")); expireSessionCookie(ex); sendJson(ex, 200, "{\"ok\":true}"); return; }
        UserSession session = requireSession(ex);
        if (session == null) return;
        if (path.startsWith("/api/admin/")) { adminApi(ex, path, form, session); return; }
        if (path.equals("/api/dashboard")) { dashboard(ex, session); return; }
        if (!session.student()) { sendJson(ex, 403, "{\"error\":\"Student access required\"}"); return; }
        if (path.equals("/api/machines")) { machines(ex); return; }
        if (path.equals("/api/bookings") && ex.getRequestMethod().equals("GET")) { bookings(ex, session); return; }
        if (path.equals("/api/bookings") && ex.getRequestMethod().equals("POST")) { createBooking(ex, session, form); return; }
        if (path.equals("/api/cancel") && ex.getRequestMethod().equals("POST")) { cancel(ex, session, form); return; }
        if (path.equals("/api/complaints") && ex.getRequestMethod().equals("GET")) { complaints(ex, session); return; }
        if (path.equals("/api/complaints") && ex.getRequestMethod().equals("POST")) { createComplaint(ex, session, form); return; }
        if (path.equals("/api/queue")) { queue(ex); return; }
        if (path.equals("/api/profile") && ex.getRequestMethod().equals("GET")) { profile(ex, session); return; }
        if (path.equals("/api/profile") && ex.getRequestMethod().equals("POST")) { updateProfile(ex, session, form); return; }
        if (path.equals("/api/notifications")) { notifications(ex, session); return; }
        if (path.equals("/api/slots")) { slots(ex); return; }
        sendJson(ex, 404, "{\"error\":\"Unknown endpoint\"}");
    }

    private static void login(HttpExchange ex, Map<String, String> f) throws IOException, SQLException {
        String email = f.getOrDefault("email", "").trim(); String password = f.getOrDefault("password", "");
        try (Connection c = DatabaseConnection.getConnection()) {
            UserSession user = loginFromTable(c, "admin", "admin_id", email, password, false);
            if (user == null) user = loginFromTable(c, "student", "student_id", email, password, true);
            if (user == null) { sendJson(ex, 401, "{\"error\":\"Invalid email or password\"}"); return; }
            String token = UUID.randomUUID().toString(); SESSIONS.put(token, user);
            ex.getResponseHeaders().add("Set-Cookie", "session=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=7200" + (secureCookie(ex) ? "; Secure" : ""));
            sendJson(ex, 200, "{\"name\":\"" + escape(user.name()) + "\",\"role\":\"" + user.role() + "\"}");
        }
    }

    private static UserSession loginFromTable(Connection c, String table, String idColumn, String email, String password, boolean student) throws SQLException {
        String sql = "SELECT " + idColumn + ", name, password FROM " + table + " WHERE email=?";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, email);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || !passwordMatches(password, r.getString("password"))) return null;
                String stored = r.getString("password");
                if (!stored.startsWith("pbkdf2$")) { try (PreparedStatement u = c.prepareStatement("UPDATE " + table + " SET password=? WHERE " + idColumn + "=?")) { u.setString(1, hash(password)); u.setInt(2, r.getInt(1)); u.executeUpdate(); } }
                return new UserSession(r.getInt(1), r.getString("name"), student ? "STUDENT" : "ADMIN", System.currentTimeMillis() + 7_200_000L);
            }
        }
    }

    private static void register(HttpExchange ex, Map<String, String> f) throws IOException, SQLException {
        for (String key : new String[]{"name", "email", "password", "hostelBlock", "roomNumber"}) if (blank(f.get(key))) { sendJson(ex, 400, "{\"error\":\"Please complete all required fields\"}"); return; }
        if (Boolean.parseBoolean(setting("PRIVATE_BETA", "false")) && !invitedTester(f.get("email"))) { sendJson(ex, 403, "{\"error\":\"This private beta is invitation-only. Ask the project administrator for access.\"}"); return; }
        if (!strongPassword(f.get("password"))) { sendJson(ex, 400, "{\"error\":\"Password must have 12+ characters with uppercase, lowercase, number, and symbol\"}"); return; }
        String sql = "INSERT INTO student (name,email,password,phone,hostel_block,room_number) VALUES (?,?,?,?,?,?)";
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, f.get("name")); s.setString(2, f.get("email")); s.setString(3, hash(f.get("password"))); s.setString(4, f.getOrDefault("phone", "")); s.setString(5, f.get("hostelBlock")); s.setString(6, f.get("roomNumber")); s.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) { sendJson(ex, 409, "{\"error\":\"That email is already registered\"}"); return; } throw e;
        }
        sendJson(ex, 201, "{\"ok\":true}");
    }

    /**
     * Starts an administrator-assisted password reset without revealing whether an
     * email is registered. The administrator can set a temporary password and
     * contact the student through the phone number that the student supplied.
     */
    private static void requestPasswordReset(HttpExchange ex, Map<String, String> f) throws IOException, SQLException {
        String email = f.getOrDefault("email", "").trim().toLowerCase();
        String forwarded = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        String client = !blank(forwarded) ? forwarded.split(",", 2)[0].trim() : (ex.getRemoteAddress().getAddress() == null ? "unknown" : ex.getRemoteAddress().getAddress().getHostAddress());
        long now = System.currentTimeMillis();
        Long previous = RESET_REQUEST_LIMIT.put(client, now);
        if (previous != null && now - previous < 60_000L) {
            sendJson(ex, 429, "{\"error\":\"Please wait one minute before submitting another reset request.\"}");
            return;
        }
        if (blank(email) || !email.contains("@")) {
            sendJson(ex, 400, "{\"error\":\"Enter a valid email address.\"}");
            return;
        }
        String sql = "INSERT INTO password_reset_request(student_id) "
                + "SELECT s.student_id FROM student s WHERE LOWER(s.email)=? "
                + "AND NOT EXISTS (SELECT 1 FROM password_reset_request p WHERE p.student_id=s.student_id AND p.status='PENDING')";
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, email);
            s.executeUpdate();
        }
        // This response is deliberately identical for existing and unknown emails.
        sendJson(ex, 200, "{\"ok\":true}");
    }

    private static void dashboard(HttpExchange ex, UserSession user) throws IOException, SQLException {
        if (!user.student()) { sendJson(ex, 200, "{\"name\":\"" + escape(user.name()) + "\",\"role\":\"ADMIN\"}"); return; }
        String machine = scalar("SELECT COUNT(*) FROM machine");
        String available = scalar("SELECT COUNT(*) FROM machine WHERE status='AVAILABLE'");
        String booking = scalar("SELECT COUNT(*) FROM booking WHERE student_id=? AND status IN ('PENDING','ACTIVE')", user.id());
        String complaint = scalar("SELECT COUNT(*) FROM complaint WHERE student_id=? AND status <> 'RESOLVED'", user.id());
        sendJson(ex, 200, "{\"name\":\"" + escape(user.name()) + "\",\"role\":\"STUDENT\",\"totalMachines\":" + machine + ",\"availableMachines\":" + available + ",\"myBookings\":" + booking + ",\"myComplaints\":" + complaint + "}");
    }

    private static void machines(HttpExchange ex) throws IOException, SQLException {
        String sql = "SELECT machine_id,machine_name,hostel_block,status FROM machine ORDER BY CASE WHEN machine_name LIKE 'Washing Machine%' THEN 0 WHEN machine_name LIKE 'Dryer%' THEN 1 ELSE 2 END, machine_name";
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement(sql); ResultSet r = s.executeQuery()) {
            StringBuilder out = new StringBuilder("["); while (r.next()) append(out, "{\"id\":"+r.getInt(1)+",\"name\":\""+escape(r.getString(2))+"\",\"block\":\""+escape(r.getString(3))+"\",\"status\":\""+r.getString(4)+"\"}"); sendJson(ex, 200, out.append(']').toString());
        }
    }

    private static void bookings(HttpExchange ex, UserSession user) throws IOException, SQLException {
        String sql = "SELECT b.booking_id,m.machine_name,b.booking_date,b.start_time,b.end_time,b.status FROM booking b JOIN machine m ON m.machine_id=b.machine_id WHERE b.student_id=? ORDER BY b.booking_date DESC,b.start_time DESC";
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement(sql)) { s.setInt(1,user.id()); try(ResultSet r=s.executeQuery()) { StringBuilder out=new StringBuilder("["); while(r.next()) append(out,"{\"id\":"+r.getInt(1)+",\"machine\":\""+escape(r.getString(2))+"\",\"date\":\""+r.getDate(3)+"\",\"start\":\""+r.getTime(4).toLocalTime()+"\",\"end\":\""+r.getTime(5).toLocalTime()+"\",\"status\":\""+r.getString(6)+"\"}"); sendJson(ex,200,out.append(']').toString()); } }
    }

    private static void createBooking(HttpExchange ex, UserSession user, Map<String,String> f) throws IOException, SQLException {
        int machineId; LocalDate date; LocalTime start,end;
        try { machineId=Integer.parseInt(f.get("machineId")); date=LocalDate.parse(f.get("date")); start=LocalTime.parse(f.get("start")); end=LocalTime.parse(f.get("end")); } catch(Exception e) { sendJson(ex,400,"{\"error\":\"Invalid booking details\"}"); return; }
        if (!end.equals(start.plusHours(1)) || date.isBefore(LocalDate.now())) { sendJson(ex,400,"{\"error\":\"Bookings must be exactly one hour long and cannot be in the past\"}"); return; }
        String lockName = "laundry-booking:" + machineId + ":" + date + ":" + start;
        try(Connection c=DatabaseConnection.getConnection()) {
            if (!acquireBookingLock(c, lockName)) { sendJson(ex, 429, "{\"error\":\"Booking is busy. Please try again in a moment.\"}"); return; }
            try {
                c.setAutoCommit(false);
                String available="SELECT 1 FROM machine WHERE machine_id=? AND status='AVAILABLE' FOR UPDATE";
                try(PreparedStatement s=c.prepareStatement(available)){s.setInt(1,machineId);try(ResultSet r=s.executeQuery()){if(!r.next()){c.rollback();sendJson(ex,409,"{\"error\":\"This machine is not available\"}");return;}}}
                String overlap="SELECT 1 FROM booking WHERE machine_id=? AND booking_date=? AND status IN ('PENDING','ACTIVE') AND start_time < ? AND end_time > ? FOR UPDATE";
                try(PreparedStatement s=c.prepareStatement(overlap)){s.setInt(1,machineId);s.setDate(2,java.sql.Date.valueOf(date));s.setTime(3,java.sql.Time.valueOf(end));s.setTime(4,java.sql.Time.valueOf(start));try(ResultSet r=s.executeQuery()){if(r.next()){c.rollback();sendJson(ex,409,"{\"error\":\"That time slot has already been booked\"}");return;}}}
                try(PreparedStatement s=c.prepareStatement("INSERT INTO booking(student_id,machine_id,booking_date,start_time,end_time,status) VALUES(?,?,?,?,?,'PENDING')")){s.setInt(1,user.id());s.setInt(2,machineId);s.setDate(3,java.sql.Date.valueOf(date));s.setTime(4,java.sql.Time.valueOf(start));s.setTime(5,java.sql.Time.valueOf(end));s.executeUpdate();}
                c.commit();
            } catch (SQLException failure) { c.rollback(); throw failure; }
            finally { releaseBookingLock(c, lockName); }
        }
        sendJson(ex,201,"{\"ok\":true}");
    }

    private static void cancel(HttpExchange ex, UserSession user, Map<String,String> f) throws IOException, SQLException { try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement("UPDATE booking SET status='CANCELLED' WHERE booking_id=? AND student_id=? AND status IN ('PENDING','ACTIVE')")){s.setInt(1,Integer.parseInt(f.get("id")));s.setInt(2,user.id());sendJson(ex,s.executeUpdate()>0?200:404,"{\"ok\":true}");} }
    private static void complaints(HttpExchange ex, UserSession u) throws IOException,SQLException {String q="SELECT c.complaint_id,m.machine_name,c.complaint_text,c.status,c.created_at FROM complaint c JOIN machine m ON m.machine_id=c.machine_id WHERE c.student_id=? ORDER BY c.created_at DESC";try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(q)){s.setInt(1,u.id());try(ResultSet r=s.executeQuery()){StringBuilder o=new StringBuilder("[");while(r.next())append(o,"{\"id\":"+r.getInt(1)+",\"machine\":\""+escape(r.getString(2))+"\",\"text\":\""+escape(r.getString(3))+"\",\"status\":\""+r.getString(4)+"\",\"created\":\""+r.getTimestamp(5)+"\"}");sendJson(ex,200,o.append(']').toString());}}}
    private static void createComplaint(HttpExchange ex,UserSession u,Map<String,String> f)throws IOException,SQLException{if(blank(f.get("text"))){sendJson(ex,400,"{\"error\":\"Please describe the issue\"}");return;}try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement("INSERT INTO complaint(student_id,machine_id,complaint_text,status) VALUES(?,?,?,'PENDING')")){s.setInt(1,u.id());s.setInt(2,Integer.parseInt(f.get("machineId")));s.setString(3,f.get("text"));s.executeUpdate();}sendJson(ex,201,"{\"ok\":true}");}
    private static void queue(HttpExchange ex)throws IOException,SQLException{String q="SELECT b.booking_date,b.start_time,b.end_time,m.machine_name FROM booking b JOIN machine m ON m.machine_id=b.machine_id WHERE b.status IN ('PENDING','ACTIVE') AND b.booking_date>=CURDATE() ORDER BY b.booking_date,b.start_time LIMIT 8";try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(q);ResultSet r=s.executeQuery()){StringBuilder o=new StringBuilder("[");while(r.next())append(o,"{\"date\":\""+r.getDate(1)+"\",\"start\":\""+r.getTime(2).toLocalTime()+"\",\"end\":\""+r.getTime(3).toLocalTime()+"\",\"machine\":\""+escape(r.getString(4))+"\"}");sendJson(ex,200,o.append(']').toString());}}
    private static void profile(HttpExchange ex, UserSession u) throws IOException, SQLException { try (Connection c=DatabaseConnection.getConnection(); PreparedStatement s=c.prepareStatement("SELECT name,email,phone,hostel_block,room_number FROM student WHERE student_id=?")) { s.setInt(1,u.id()); try(ResultSet r=s.executeQuery()){ if(!r.next()){sendJson(ex,404,"{\"error\":\"Profile not found\"}");return;} sendJson(ex,200,"{\"name\":\""+escape(r.getString(1))+"\",\"email\":\""+escape(r.getString(2))+"\",\"phone\":\""+escape(r.getString(3))+"\",\"hostelBlock\":\""+escape(r.getString(4))+"\",\"roomNumber\":\""+escape(r.getString(5))+"\"}"); } } }
    private static void updateProfile(HttpExchange ex, UserSession u, Map<String,String> f) throws IOException, SQLException { if(blank(f.get("name"))||blank(f.get("hostelBlock"))||blank(f.get("roomNumber"))){sendJson(ex,400,"{\"error\":\"Name, hostel block and room number are required\"}");return;} if(!blank(f.get("newPassword"))&&!strongPassword(f.get("newPassword"))){sendJson(ex,400,"{\"error\":\"New password must have 12+ characters with uppercase, lowercase, number, and symbol\"}");return;} String q=blank(f.get("newPassword"))?"UPDATE student SET name=?,phone=?,hostel_block=?,room_number=? WHERE student_id=?":"UPDATE student SET name=?,phone=?,hostel_block=?,room_number=?,password=? WHERE student_id=?"; try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(q)){s.setString(1,f.get("name"));s.setString(2,f.getOrDefault("phone", ""));s.setString(3,f.get("hostelBlock"));s.setString(4,f.get("roomNumber"));if(blank(f.get("newPassword"))){s.setInt(5,u.id());}else{s.setString(5,hash(f.get("newPassword")));s.setInt(6,u.id());}s.executeUpdate();} SESSIONS.replaceAll((k,v)->v.id()==u.id()&&v.student()?new UserSession(v.id(),f.get("name"),v.role(),v.expiresAt()):v); sendJson(ex,200,"{\"ok\":true}"); }
    private static void notifications(HttpExchange ex, UserSession u) throws IOException, SQLException { String q="SELECT notification_id,message,is_read,created_at FROM notification WHERE student_id=? ORDER BY created_at DESC LIMIT 20";try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(q)){s.setInt(1,u.id());try(ResultSet r=s.executeQuery()){StringBuilder o=new StringBuilder("[");while(r.next())append(o,"{\"id\":"+r.getInt(1)+",\"message\":\""+escape(r.getString(2))+"\",\"read\":"+r.getBoolean(3)+",\"created\":\""+r.getTimestamp(4)+"\"}");sendJson(ex,200,o.append(']').toString());}} }
    private static void slots(HttpExchange ex) throws IOException, SQLException { Map<String,String> q=query(ex); if(blank(q.get("machineId"))||blank(q.get("date"))){sendJson(ex,400,"{\"error\":\"Machine and date are required\"}");return;} String sql="SELECT start_time FROM booking WHERE machine_id=? AND booking_date=? AND status IN ('PENDING','ACTIVE')";try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setInt(1,Integer.parseInt(q.get("machineId")));s.setDate(2,java.sql.Date.valueOf(q.get("date")));try(ResultSet r=s.executeQuery()){StringBuilder o=new StringBuilder("[");while(r.next())append(o,"\""+r.getTime(1).toLocalTime()+"\"");sendJson(ex,200,o.append(']').toString());}} }
    private static void adminApi(HttpExchange ex, String path, Map<String,String> f, UserSession u) throws IOException, SQLException {
        if (u.student()) { sendJson(ex,403,"{\"error\":\"Administrator access required\"}"); return; }
        if (path.equals("/api/admin/dashboard")) { adminDashboard(ex); return; }
        if (path.equals("/api/admin/profile") && ex.getRequestMethod().equals("GET")) { adminProfile(ex,u); return; }
        if (path.equals("/api/admin/profile") && ex.getRequestMethod().equals("POST")) { changeAdminPassword(ex,u,f); return; }
        if (path.equals("/api/admin/machines") && ex.getRequestMethod().equals("GET")) { machines(ex); return; }
        if (path.equals("/api/admin/machines") && ex.getRequestMethod().equals("POST")) { saveMachine(ex,f); return; }
        if (path.equals("/api/admin/machine-delete")) { deleteMachine(ex,f); return; }
        if (path.equals("/api/admin/bookings")) { adminBookings(ex); return; }
        if (path.equals("/api/admin/booking-status")) { updateBookingStatus(ex,f); return; }
        if (path.equals("/api/admin/complaints")) { adminComplaints(ex); return; }
        if (path.equals("/api/admin/complaint-status")) { updateComplaintStatus(ex,f); return; }
        if (path.equals("/api/admin/password-reset-requests") && ex.getRequestMethod().equals("GET")) { resetRequests(ex); return; }
        if (path.equals("/api/admin/reset-student-password") && ex.getRequestMethod().equals("POST")) { resetStudentPassword(ex,f); return; }
        if (path.equals("/api/admin/export") && ex.getRequestMethod().equals("GET")) { exportCsv(ex); return; }
        sendJson(ex,404,"{\"error\":\"Unknown administrator endpoint\"}");
    }
    private static void adminDashboard(HttpExchange ex)throws IOException,SQLException{sendJson(ex,200,"{\"students\":"+scalar("SELECT COUNT(*) FROM student")+",\"machines\":"+scalar("SELECT COUNT(*) FROM machine")+",\"bookings\":"+scalar("SELECT COUNT(*) FROM booking WHERE status IN ('PENDING','ACTIVE')")+",\"complaints\":"+scalar("SELECT COUNT(*) FROM complaint WHERE status <> 'RESOLVED'")+",\"passwordResetRequests\":"+scalar("SELECT COUNT(*) FROM password_reset_request WHERE status='PENDING'")+"}");}
    private static void adminProfile(HttpExchange ex, UserSession u)throws IOException,SQLException{try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement("SELECT name,email FROM admin WHERE admin_id=?")){s.setInt(1,u.id());try(ResultSet r=s.executeQuery()){if(!r.next()){sendJson(ex,404,"{\"error\":\"Administrator account not found\"}");return;}sendJson(ex,200,"{\"name\":\""+escape(r.getString(1))+"\",\"email\":\""+escape(r.getString(2))+"\"}");}}}
    private static void changeAdminPassword(HttpExchange ex,UserSession u,Map<String,String> f)throws IOException,SQLException{String current=f.getOrDefault("currentPassword","");String next=f.getOrDefault("newPassword","");if(blank(current)||blank(next)){sendJson(ex,400,"{\"error\":\"Enter your current password and a new password\"}");return;}if(!strongPassword(next)){sendJson(ex,400,"{\"error\":\"New password must have 12+ characters with uppercase, lowercase, number, and symbol\"}");return;}try(Connection c=DatabaseConnection.getConnection();PreparedStatement find=c.prepareStatement("SELECT password FROM admin WHERE admin_id=?")){find.setInt(1,u.id());try(ResultSet r=find.executeQuery()){if(!r.next()||!passwordMatches(current,r.getString(1))){sendJson(ex,401,"{\"error\":\"Your current password is incorrect\"}");return;}}try(PreparedStatement save=c.prepareStatement("UPDATE admin SET password=? WHERE admin_id=?")){save.setString(1,hash(next));save.setInt(2,u.id());save.executeUpdate();}}sendJson(ex,200,"{\"ok\":true}");}
    private static void resetRequests(HttpExchange ex) throws IOException, SQLException {
        String q = "SELECT p.request_id,s.name,s.email,s.phone,p.created_at FROM password_reset_request p JOIN student s ON s.student_id=p.student_id WHERE p.status='PENDING' ORDER BY p.created_at";
        try (Connection c=DatabaseConnection.getConnection(); PreparedStatement s=c.prepareStatement(q); ResultSet r=s.executeQuery()) {
            StringBuilder out=new StringBuilder("[");
            while(r.next()) append(out,"{\"id\":"+r.getInt(1)+",\"student\":\""+escape(r.getString(2))+"\",\"email\":\""+escape(r.getString(3))+"\",\"phone\":\""+escape(r.getString(4))+"\",\"created\":\""+r.getTimestamp(5)+"\"}");
            sendJson(ex,200,out.append(']').toString());
        }
    }
    private static void resetStudentPassword(HttpExchange ex, Map<String,String> f) throws IOException, SQLException {
        int requestId;
        try { requestId=Integer.parseInt(f.get("id")); } catch (Exception e) { sendJson(ex,400,"{\"error\":\"Invalid password reset request\"}"); return; }
        String newPassword=f.getOrDefault("newPassword","");
        if (!strongPassword(newPassword)) { sendJson(ex,400,"{\"error\":\"Temporary password must have 12+ characters with uppercase, lowercase, number, and symbol\"}"); return; }
        String name="", phone="";
        try (Connection c=DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement find=c.prepareStatement("SELECT s.student_id,s.name,s.phone FROM password_reset_request p JOIN student s ON s.student_id=p.student_id WHERE p.request_id=? AND p.status='PENDING' FOR UPDATE")) {
                find.setInt(1,requestId);
                try(ResultSet r=find.executeQuery()) {
                    if(!r.next()){c.rollback();sendJson(ex,404,"{\"error\":\"Password reset request was not found or has already been completed\"}");return;}
                    int studentId=r.getInt(1); name=r.getString(2); phone=r.getString(3);
                    try(PreparedStatement update=c.prepareStatement("UPDATE student SET password=? WHERE student_id=?")){update.setString(1,hash(newPassword));update.setInt(2,studentId);update.executeUpdate();}
                    try(PreparedStatement done=c.prepareStatement("UPDATE password_reset_request SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP WHERE request_id=?")){done.setInt(1,requestId);done.executeUpdate();}
                    try(PreparedStatement notification=c.prepareStatement("INSERT INTO notification(student_id,message) VALUES(?,?)")){notification.setInt(1,studentId);notification.setString(2,"Your password reset was completed by the laundry administrator. Please change the temporary password in My Profile after logging in.");notification.executeUpdate();}
                }
            }
            c.commit();
        }
        String normalized=normalizeWhatsApp(phone);
        String url="";
        if(!blank(normalized)) {
            String message="Hello "+name+", your Hostel Laundry account password reset is ready. Your temporary password is: "+newPassword+". Please log in and change it immediately in My Profile.";
            url="https://wa.me/"+normalized+"?text="+URLEncoder.encode(message,StandardCharsets.UTF_8);
        }
        sendJson(ex,200,"{\"ok\":true,\"whatsAppUrl\":\""+escape(url)+"\"}");
    }
    private static void exportCsv(HttpExchange ex) throws IOException, SQLException {
        String type=query(ex).getOrDefault("type","");
        String sql, file;
        if("students".equals(type)){sql="SELECT student_id,name,email,phone,hostel_block,room_number,created_at FROM student ORDER BY student_id";file="students.csv";}
        else if("bookings".equals(type)){sql="SELECT b.booking_id,s.name,m.machine_name,b.booking_date,b.start_time,b.end_time,b.status,b.created_at FROM booking b JOIN student s ON s.student_id=b.student_id JOIN machine m ON m.machine_id=b.machine_id ORDER BY b.booking_date DESC,b.start_time DESC";file="bookings.csv";}
        else if("complaints".equals(type)){sql="SELECT c.complaint_id,s.name,m.machine_name,c.complaint_text,c.status,c.created_at FROM complaint c JOIN student s ON s.student_id=c.student_id JOIN machine m ON m.machine_id=c.machine_id ORDER BY c.created_at DESC";file="complaints.csv";}
        else {sendJson(ex,400,"{\"error\":\"Choose students, bookings, or complaints to export\"}");return;}
        try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(sql);ResultSet r=s.executeQuery()){
            StringBuilder csv=new StringBuilder();int count=r.getMetaData().getColumnCount();
            for(int i=1;i<=count;i++){if(i>1)csv.append(',');csv.append(csv(r.getMetaData().getColumnLabel(i)));}csv.append('\n');
            while(r.next()){for(int i=1;i<=count;i++){if(i>1)csv.append(',');csv.append(csv(r.getString(i)));}csv.append('\n');}
            byte[] bytes=csv.toString().getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","text/csv; charset=utf-8");ex.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+file+"\"");ex.getResponseHeaders().set("Cache-Control","no-store");ex.sendResponseHeaders(200,bytes.length);ex.getResponseBody().write(bytes);ex.close();
        }
    }
    private static void saveMachine(HttpExchange ex,Map<String,String> f)throws IOException,SQLException{if(blank(f.get("block"))||!valid(f.get("status"),"AVAILABLE","IN_USE","MAINTENANCE")){sendJson(ex,400,"{\"error\":\"Choose a valid block and machine status\"}");return;}try(Connection c=DatabaseConnection.getConnection()){if(blank(f.get("id"))){String name=f.get("name");if(blank(name)){try(PreparedStatement n=c.prepareStatement("SELECT COALESCE(MAX(machine_id),0)+1 FROM machine");ResultSet r=n.executeQuery()){r.next();name="Washing Machine "+r.getInt(1);}}try(PreparedStatement s=c.prepareStatement("INSERT INTO machine(machine_name,hostel_block,status) VALUES(?,?,?)")){s.setString(1,name);s.setString(2,f.get("block"));s.setString(3,f.get("status"));s.executeUpdate();}}else{try(PreparedStatement s=c.prepareStatement("UPDATE machine SET hostel_block=?,status=? WHERE machine_id=?")){s.setString(1,f.get("block"));s.setString(2,f.get("status"));s.setInt(3,Integer.parseInt(f.get("id")));if(s.executeUpdate()==0){sendJson(ex,404,"{\"error\":\"Machine not found\"}");return;}}}}sendJson(ex,200,"{\"ok\":true}");}
    private static void deleteMachine(HttpExchange ex,Map<String,String> f)throws IOException,SQLException{try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement("DELETE FROM machine WHERE machine_id=? AND NOT EXISTS (SELECT 1 FROM booking WHERE machine_id=? ) AND NOT EXISTS (SELECT 1 FROM complaint WHERE machine_id=?)")){int id=Integer.parseInt(f.get("id"));s.setInt(1,id);s.setInt(2,id);s.setInt(3,id);if(s.executeUpdate()==0){sendJson(ex,409,"{\"error\":\"Machine cannot be removed because it has booking or complaint history\"}");return;}}sendJson(ex,200,"{\"ok\":true}");}
    private static void adminBookings(HttpExchange ex)throws IOException,SQLException{String q="SELECT b.booking_id,s.student_id,s.name,m.machine_name,b.booking_date,b.start_time,b.end_time,b.status FROM booking b JOIN student s ON s.student_id=b.student_id JOIN machine m ON m.machine_id=b.machine_id ORDER BY b.booking_date DESC,b.start_time DESC";try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(q);ResultSet r=s.executeQuery()){StringBuilder o=new StringBuilder("[");while(r.next())append(o,"{\"id\":"+r.getInt(1)+",\"studentId\":"+r.getInt(2)+",\"student\":\""+escape(r.getString(3))+"\",\"machine\":\""+escape(r.getString(4))+"\",\"date\":\""+r.getDate(5)+"\",\"start\":\""+r.getTime(6).toLocalTime()+"\",\"end\":\""+r.getTime(7).toLocalTime()+"\",\"status\":\""+r.getString(8)+"\"}");sendJson(ex,200,o.append(']').toString());}}
    private static void updateBookingStatus(HttpExchange ex,Map<String,String> f)throws IOException,SQLException{String status=f.get("status");if(!valid(status,"PENDING","ACTIVE","COMPLETED","CANCELLED")){sendJson(ex,400,"{\"error\":\"Invalid booking status\"}");return;}try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement("UPDATE booking SET status=? WHERE booking_id=?")){s.setString(1,status);s.setInt(2,Integer.parseInt(f.get("id")));if(s.executeUpdate()==0){sendJson(ex,404,"{\"error\":\"Booking not found\"}");return;}}notifyStudentForBooking(Integer.parseInt(f.get("id")),"Your booking status has been updated to "+status.replace('_',' ')+".");sendJson(ex,200,"{\"ok\":true}");}
    private static void adminComplaints(HttpExchange ex)throws IOException,SQLException{String q="SELECT c.complaint_id,s.student_id,s.name,m.machine_name,c.complaint_text,c.status,c.created_at FROM complaint c JOIN student s ON s.student_id=c.student_id JOIN machine m ON m.machine_id=c.machine_id ORDER BY c.created_at DESC";try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(q);ResultSet r=s.executeQuery()){StringBuilder o=new StringBuilder("[");while(r.next())append(o,"{\"id\":"+r.getInt(1)+",\"studentId\":"+r.getInt(2)+",\"student\":\""+escape(r.getString(3))+"\",\"machine\":\""+escape(r.getString(4))+"\",\"text\":\""+escape(r.getString(5))+"\",\"status\":\""+r.getString(6)+"\",\"created\":\""+r.getTimestamp(7)+"\"}");sendJson(ex,200,o.append(']').toString());}}
    private static void updateComplaintStatus(HttpExchange ex,Map<String,String> f)throws IOException,SQLException{String status=f.get("status");if(!valid(status,"PENDING","IN_PROGRESS","RESOLVED")){sendJson(ex,400,"{\"error\":\"Invalid complaint status\"}");return;}int id=Integer.parseInt(f.get("id"));try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement("UPDATE complaint SET status=? WHERE complaint_id=?")){s.setString(1,status);s.setInt(2,id);if(s.executeUpdate()==0){sendJson(ex,404,"{\"error\":\"Complaint not found\"}");return;}}notifyStudentForComplaint(id,"Your maintenance report status has been updated to "+status.replace('_',' ')+".");sendJson(ex,200,"{\"ok\":true}");}
    private static void notifyStudentForBooking(int id,String message)throws SQLException{try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement("INSERT INTO notification(student_id,message) SELECT student_id,? FROM booking WHERE booking_id=?")){s.setString(1,message);s.setInt(2,id);s.executeUpdate();}}
    private static void notifyStudentForComplaint(int id,String message)throws SQLException{try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement("INSERT INTO notification(student_id,message) SELECT student_id,? FROM complaint WHERE complaint_id=?")){s.setString(1,message);s.setInt(2,id);s.executeUpdate();}}
    private static boolean valid(String value,String...allowed){for(String item:allowed)if(item.equals(value))return true;return false;}
    private static String scalar(String sql,int...values)throws SQLException{try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)s.setInt(i+1,values[i]);try(ResultSet r=s.executeQuery()){r.next();return r.getString(1);}}}
    private static UserSession requireSession(HttpExchange ex)throws IOException{String token=cookie(ex,"session");UserSession s=SESSIONS.get(token);if(s==null||s.expiresAt()<System.currentTimeMillis()){SESSIONS.remove(token);sendJson(ex,401,"{\"error\":\"Your session has expired. Please log in again.\"}");return null;}return s;}
    private static Map<String,String> form(HttpExchange e)throws IOException{Map<String,String> m=new HashMap<>();if(!e.getRequestMethod().equals("POST"))return m;byte[] bodyBytes=e.getRequestBody().readNBytes(MAX_REQUEST_BODY_BYTES + 1);if(bodyBytes.length>MAX_REQUEST_BODY_BYTES)throw new IOException("Request is too large");String body=new String(bodyBytes,StandardCharsets.UTF_8);for(String p:body.split("&")){String[] a=p.split("=",2);if(a.length==2)m.put(URLDecoder.decode(a[0],StandardCharsets.UTF_8),URLDecoder.decode(a[1],StandardCharsets.UTF_8));}return m;}
    private static Map<String,String> query(HttpExchange e){Map<String,String> m=new HashMap<>();String raw=e.getRequestURI().getRawQuery();if(raw==null)return m;for(String p:raw.split("&")){String[]a=p.split("=",2);if(a.length==2)m.put(URLDecoder.decode(a[0],StandardCharsets.UTF_8),URLDecoder.decode(a[1],StandardCharsets.UTF_8));}return m;}
    private static String cookie(HttpExchange e,String key){String raw=e.getRequestHeaders().getFirst("Cookie");if(raw!=null)for(String p:raw.split(";")){String[]a=p.trim().split("=",2);if(a.length==2&&a[0].equals(key))return a[1];}return "";}
    private static void serveFile(HttpExchange e,String name,String type)throws IOException{try(InputStream in=HostelLaundryWebServer.class.getClassLoader().getResourceAsStream(name)){if(in==null){send(e,404,"text/plain","File not found");return;}send(e,200,type,new String(in.readAllBytes(),StandardCharsets.UTF_8));}}
    private static void serveBinaryFile(HttpExchange e, String name, String type) throws IOException {
        try (InputStream in = HostelLaundryWebServer.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) { send(e, 404, "text/plain", "File not found"); return; }
            byte[] bytes = in.readAllBytes();
            int start = 0, end = bytes.length - 1;
            String range = e.getRequestHeaders().getFirst("Range");
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-", 2);
                try {
                    start = Integer.parseInt(parts[0]);
                    if (parts.length == 2 && !parts[1].isBlank()) end = Math.min(Integer.parseInt(parts[1]), end);
                } catch (NumberFormatException ignored) { start = 0; }
            }
            if (start < 0 || start >= bytes.length || end < start) { e.getResponseHeaders().set("Content-Range", "bytes */" + bytes.length); e.sendResponseHeaders(416, -1); e.close(); return; }
            int length = end - start + 1;
            e.getResponseHeaders().set("Content-Type", type);
            e.getResponseHeaders().set("Accept-Ranges", "bytes");
            e.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            if (range != null) e.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length);
            e.sendResponseHeaders(range == null ? 200 : 206, length);
            e.getResponseBody().write(bytes, start, length);
            e.close();
        }
    }
    private static void sendJson(HttpExchange e,int code,String body)throws IOException{send(e,code,"application/json; charset=utf-8",body);}
    private static void send(HttpExchange e,int code,String type,String body)throws IOException{byte[]b=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type",type);e.sendResponseHeaders(code,b.length);e.getResponseBody().write(b);e.close();}
    private static void append(StringBuilder b,String value){if(b.length()>1)b.append(',');b.append(value);}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static String setting(String name,String defaultValue){String value=System.getProperty(name);if(blank(value))value=System.getenv(name);return blank(value)?defaultValue:value;}
    private static boolean invitedTester(String email){for(String invited:setting("TESTER_EMAILS","").split(",")){if(invited.trim().equalsIgnoreCase(email==null?"":email.trim()))return true;}return false;}
    private static String escape(String value){return value==null?"":value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","");}
    private static String csv(String value){return "\""+(value==null?"":value.replace("\"","\"\""))+"\"";}
    private static String normalizeWhatsApp(String phone){String digits=phone==null?"":phone.replaceAll("[^0-9]","");if(digits.startsWith("0"))digits="6"+digits;return digits.length()>=10&&digits.length()<=15?digits:"";}
    private static boolean acquireBookingLock(Connection c, String name) throws SQLException { try (PreparedStatement s=c.prepareStatement("SELECT GET_LOCK(?, 5)")) { s.setString(1,name); try(ResultSet r=s.executeQuery()){return r.next()&&r.getInt(1)==1;} } }
    private static void releaseBookingLock(Connection c, String name) { try (PreparedStatement s=c.prepareStatement("SELECT RELEASE_LOCK(?)")) { s.setString(1,name); s.execute(); } catch (SQLException ignored) { } }
    private static boolean secureCookie(HttpExchange e) { String forwarded=e.getRequestHeaders().getFirst("X-Forwarded-Proto"); return Boolean.parseBoolean(setting("COOKIE_SECURE", "false")) || (forwarded != null && forwarded.equalsIgnoreCase("https")); }
    private static void expireSessionCookie(HttpExchange e) { e.getResponseHeaders().add("Set-Cookie", "session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0" + (secureCookie(e) ? "; Secure" : "")); }
    private static boolean passwordMatches(String password, String stored) { if (stored == null) return false; if (stored.startsWith("pbkdf2$")) { String[] parts=stored.split("\\$",4); if(parts.length!=4)return false; try { int iterations=Integer.parseInt(parts[1]); byte[] expected=Base64.getDecoder().decode(parts[3]); byte[] actual=pbkdf2(password,Base64.getDecoder().decode(parts[2]),iterations,expected.length*8); return MessageDigest.isEqual(expected,actual); } catch (IllegalArgumentException e) { return false; } } return stored.startsWith("sha256:") ? MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),legacyHash(password).getBytes(StandardCharsets.UTF_8)) : MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),password.getBytes(StandardCharsets.UTF_8)); }
    private static boolean strongPassword(String password) { return password != null && password.length() >= 12 && password.matches(".*[A-Z].*") && password.matches(".*[a-z].*") && password.matches(".*\\d.*") && password.matches(".*[^A-Za-z0-9].*"); }
    private static String hash(String value) { byte[] salt=new byte[16]; PASSWORD_RANDOM.nextBytes(salt); return "pbkdf2$"+PBKDF2_ITERATIONS+"$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(pbkdf2(value,salt,PBKDF2_ITERATIONS,PBKDF2_KEY_BITS)); }
    private static byte[] pbkdf2(String value, byte[] salt, int iterations, int bits) { try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(value.toCharArray(),salt,iterations,bits)).getEncoded(); } catch (InvalidKeySpecException | java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("Password hashing is unavailable",e); } }
    private static String legacyHash(String value) { try { byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder("sha256:");for(byte b:digest)out.append(String.format("%02x",b));return out.toString();} catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
    private record UserSession(int id,String name,String role,long expiresAt) { boolean student() { return "STUDENT".equals(role); } }
}
