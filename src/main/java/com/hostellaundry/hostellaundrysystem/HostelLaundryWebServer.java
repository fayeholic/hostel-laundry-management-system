package com.hostellaundry.hostellaundrysystem;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** Small dependency-free web server for the Hostel Laundry Management System. */
public final class HostelLaundryWebServer {
    private static final String INITIAL_ADMIN_PASSWORD = setting("ADMIN_INITIAL_PASSWORD", "");
    private static final Map<String, UserSession> SESSIONS = new ConcurrentHashMap<>();

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
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement admin = c.prepareStatement("CREATE TABLE IF NOT EXISTS admin (admin_id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, email VARCHAR(100) UNIQUE NOT NULL, password VARCHAR(255) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"); PreparedStatement notification = c.prepareStatement("CREATE TABLE IF NOT EXISTS notification (notification_id INT AUTO_INCREMENT PRIMARY KEY, student_id INT NOT NULL, message VARCHAR(500) NOT NULL, is_read BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (student_id) REFERENCES student(student_id) ON DELETE CASCADE)")) {
            admin.execute(); notification.execute();
            if (!blank(INITIAL_ADMIN_PASSWORD)) {
                try (PreparedStatement seed = c.prepareStatement("INSERT IGNORE INTO admin(name,email,password) VALUES('Laundry Administrator','admin@hostellaundry.com',?)")) { seed.setString(1, hash(INITIAL_ADMIN_PASSWORD)); seed.executeUpdate(); }
            }
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) { serveFile(exchange, "web/index.html", "text/html; charset=utf-8"); return; }
            if (path.equals("/app.css")) { serveFile(exchange, "web/app.css", "text/css; charset=utf-8"); return; }
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
        if (path.equals("/api/logout")) { SESSIONS.remove(cookie(ex, "session")); sendJson(ex, 200, "{\"ok\":true}"); return; }
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
            ex.getResponseHeaders().add("Set-Cookie", "session=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=7200" + (Boolean.parseBoolean(setting("COOKIE_SECURE", "false")) ? "; Secure" : ""));
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
                if (!stored.startsWith("sha256:")) { try (PreparedStatement u = c.prepareStatement("UPDATE " + table + " SET password=? WHERE " + idColumn + "=?")) { u.setString(1, hash(password)); u.setInt(2, r.getInt(1)); u.executeUpdate(); } }
                return new UserSession(r.getInt(1), r.getString("name"), student ? "STUDENT" : "ADMIN", System.currentTimeMillis() + 7_200_000L);
            }
        }
    }

    private static void register(HttpExchange ex, Map<String, String> f) throws IOException, SQLException {
        for (String key : new String[]{"name", "email", "password", "hostelBlock", "roomNumber"}) if (blank(f.get(key))) { sendJson(ex, 400, "{\"error\":\"Please complete all required fields\"}"); return; }
        if (Boolean.parseBoolean(setting("PRIVATE_BETA", "false")) && !invitedTester(f.get("email"))) { sendJson(ex, 403, "{\"error\":\"This private beta is invitation-only. Ask the project administrator for access.\"}"); return; }
        if (!strongPassword(f.get("password"))) { sendJson(ex, 400, "{\"error\":\"Password must have 8+ characters with uppercase, lowercase, number, and symbol\"}"); return; }
        String sql = "INSERT INTO student (name,email,password,phone,hostel_block,room_number) VALUES (?,?,?,?,?,?)";
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, f.get("name")); s.setString(2, f.get("email")); s.setString(3, hash(f.get("password"))); s.setString(4, f.getOrDefault("phone", "")); s.setString(5, f.get("hostelBlock")); s.setString(6, f.get("roomNumber")); s.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) { sendJson(ex, 409, "{\"error\":\"That email is already registered\"}"); return; } throw e;
        }
        sendJson(ex, 201, "{\"ok\":true}");
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
        String sql = "SELECT machine_id,machine_name,hostel_block,status FROM machine ORDER BY machine_id";
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
        try(Connection c=DatabaseConnection.getConnection()) {
            String available="SELECT 1 FROM machine WHERE machine_id=? AND status='AVAILABLE'";
            try(PreparedStatement s=c.prepareStatement(available)){s.setInt(1,machineId);try(ResultSet r=s.executeQuery()){if(!r.next()){sendJson(ex,409,"{\"error\":\"This machine is not available\"}");return;}}}
            String overlap="SELECT 1 FROM booking WHERE machine_id=? AND booking_date=? AND status IN ('PENDING','ACTIVE') AND start_time < ? AND end_time > ?";
            try(PreparedStatement s=c.prepareStatement(overlap)){s.setInt(1,machineId);s.setDate(2,java.sql.Date.valueOf(date));s.setTime(3,java.sql.Time.valueOf(end));s.setTime(4,java.sql.Time.valueOf(start));try(ResultSet r=s.executeQuery()){if(r.next()){sendJson(ex,409,"{\"error\":\"That time slot has already been booked\"}");return;}}}
            try(PreparedStatement s=c.prepareStatement("INSERT INTO booking(student_id,machine_id,booking_date,start_time,end_time,status) VALUES(?,?,?,?,?,'PENDING')")){s.setInt(1,user.id());s.setInt(2,machineId);s.setDate(3,java.sql.Date.valueOf(date));s.setTime(4,java.sql.Time.valueOf(start));s.setTime(5,java.sql.Time.valueOf(end));s.executeUpdate();}
        }
        sendJson(ex,201,"{\"ok\":true}");
    }

    private static void cancel(HttpExchange ex, UserSession user, Map<String,String> f) throws IOException, SQLException { try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement("UPDATE booking SET status='CANCELLED' WHERE booking_id=? AND student_id=? AND status IN ('PENDING','ACTIVE')")){s.setInt(1,Integer.parseInt(f.get("id")));s.setInt(2,user.id());sendJson(ex,s.executeUpdate()>0?200:404,"{\"ok\":true}");} }
    private static void complaints(HttpExchange ex, UserSession u) throws IOException,SQLException {String q="SELECT c.complaint_id,m.machine_name,c.complaint_text,c.status,c.created_at FROM complaint c JOIN machine m ON m.machine_id=c.machine_id WHERE c.student_id=? ORDER BY c.created_at DESC";try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(q)){s.setInt(1,u.id());try(ResultSet r=s.executeQuery()){StringBuilder o=new StringBuilder("[");while(r.next())append(o,"{\"id\":"+r.getInt(1)+",\"machine\":\""+escape(r.getString(2))+"\",\"text\":\""+escape(r.getString(3))+"\",\"status\":\""+r.getString(4)+"\",\"created\":\""+r.getTimestamp(5)+"\"}");sendJson(ex,200,o.append(']').toString());}}}
    private static void createComplaint(HttpExchange ex,UserSession u,Map<String,String> f)throws IOException,SQLException{if(blank(f.get("text"))){sendJson(ex,400,"{\"error\":\"Please describe the issue\"}");return;}try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement("INSERT INTO complaint(student_id,machine_id,complaint_text,status) VALUES(?,?,?,'PENDING')")){s.setInt(1,u.id());s.setInt(2,Integer.parseInt(f.get("machineId")));s.setString(3,f.get("text"));s.executeUpdate();}sendJson(ex,201,"{\"ok\":true}");}
    private static void queue(HttpExchange ex)throws IOException,SQLException{String q="SELECT b.booking_date,b.start_time,b.end_time,m.machine_name FROM booking b JOIN machine m ON m.machine_id=b.machine_id WHERE b.status IN ('PENDING','ACTIVE') AND b.booking_date>=CURDATE() ORDER BY b.booking_date,b.start_time LIMIT 8";try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(q);ResultSet r=s.executeQuery()){StringBuilder o=new StringBuilder("[");while(r.next())append(o,"{\"date\":\""+r.getDate(1)+"\",\"start\":\""+r.getTime(2).toLocalTime()+"\",\"end\":\""+r.getTime(3).toLocalTime()+"\",\"machine\":\""+escape(r.getString(4))+"\"}");sendJson(ex,200,o.append(']').toString());}}
    private static void profile(HttpExchange ex, UserSession u) throws IOException, SQLException { try (Connection c=DatabaseConnection.getConnection(); PreparedStatement s=c.prepareStatement("SELECT name,email,phone,hostel_block,room_number FROM student WHERE student_id=?")) { s.setInt(1,u.id()); try(ResultSet r=s.executeQuery()){ if(!r.next()){sendJson(ex,404,"{\"error\":\"Profile not found\"}");return;} sendJson(ex,200,"{\"name\":\""+escape(r.getString(1))+"\",\"email\":\""+escape(r.getString(2))+"\",\"phone\":\""+escape(r.getString(3))+"\",\"hostelBlock\":\""+escape(r.getString(4))+"\",\"roomNumber\":\""+escape(r.getString(5))+"\"}"); } } }
    private static void updateProfile(HttpExchange ex, UserSession u, Map<String,String> f) throws IOException, SQLException { if(blank(f.get("name"))||blank(f.get("hostelBlock"))||blank(f.get("roomNumber"))){sendJson(ex,400,"{\"error\":\"Name, hostel block and room number are required\"}");return;} if(!blank(f.get("newPassword"))&&!strongPassword(f.get("newPassword"))){sendJson(ex,400,"{\"error\":\"New password must have 8+ characters with uppercase, lowercase, number, and symbol\"}");return;} String q=blank(f.get("newPassword"))?"UPDATE student SET name=?,phone=?,hostel_block=?,room_number=? WHERE student_id=?":"UPDATE student SET name=?,phone=?,hostel_block=?,room_number=?,password=? WHERE student_id=?"; try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(q)){s.setString(1,f.get("name"));s.setString(2,f.getOrDefault("phone", ""));s.setString(3,f.get("hostelBlock"));s.setString(4,f.get("roomNumber"));if(blank(f.get("newPassword"))){s.setInt(5,u.id());}else{s.setString(5,hash(f.get("newPassword")));s.setInt(6,u.id());}s.executeUpdate();} SESSIONS.replaceAll((k,v)->v.id()==u.id()&&v.student()?new UserSession(v.id(),f.get("name"),v.role(),v.expiresAt()):v); sendJson(ex,200,"{\"ok\":true}"); }
    private static void notifications(HttpExchange ex, UserSession u) throws IOException, SQLException { String q="SELECT notification_id,message,is_read,created_at FROM notification WHERE student_id=? ORDER BY created_at DESC LIMIT 20";try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(q)){s.setInt(1,u.id());try(ResultSet r=s.executeQuery()){StringBuilder o=new StringBuilder("[");while(r.next())append(o,"{\"id\":"+r.getInt(1)+",\"message\":\""+escape(r.getString(2))+"\",\"read\":"+r.getBoolean(3)+",\"created\":\""+r.getTimestamp(4)+"\"}");sendJson(ex,200,o.append(']').toString());}} }
    private static void slots(HttpExchange ex) throws IOException, SQLException { Map<String,String> q=query(ex); if(blank(q.get("machineId"))||blank(q.get("date"))){sendJson(ex,400,"{\"error\":\"Machine and date are required\"}");return;} String sql="SELECT start_time FROM booking WHERE machine_id=? AND booking_date=? AND status IN ('PENDING','ACTIVE')";try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setInt(1,Integer.parseInt(q.get("machineId")));s.setDate(2,java.sql.Date.valueOf(q.get("date")));try(ResultSet r=s.executeQuery()){StringBuilder o=new StringBuilder("[");while(r.next())append(o,"\""+r.getTime(1).toLocalTime()+"\"");sendJson(ex,200,o.append(']').toString());}} }
    private static void adminApi(HttpExchange ex, String path, Map<String,String> f, UserSession u) throws IOException, SQLException { if(u.student()){sendJson(ex,403,"{\"error\":\"Administrator access required\"}");return;} if(path.equals("/api/admin/dashboard")){adminDashboard(ex);return;} if(path.equals("/api/admin/machines")&&ex.getRequestMethod().equals("GET")){machines(ex);return;} if(path.equals("/api/admin/machines")&&ex.getRequestMethod().equals("POST")){saveMachine(ex,f);return;} if(path.equals("/api/admin/machine-delete")){deleteMachine(ex,f);return;} if(path.equals("/api/admin/bookings")){adminBookings(ex);return;} if(path.equals("/api/admin/booking-status")){updateBookingStatus(ex,f);return;} if(path.equals("/api/admin/complaints")){adminComplaints(ex);return;} if(path.equals("/api/admin/complaint-status")){updateComplaintStatus(ex,f);return;} sendJson(ex,404,"{\"error\":\"Unknown administrator endpoint\"}"); }
    private static void adminDashboard(HttpExchange ex)throws IOException,SQLException{sendJson(ex,200,"{\"students\":"+scalar("SELECT COUNT(*) FROM student")+",\"machines\":"+scalar("SELECT COUNT(*) FROM machine")+",\"bookings\":"+scalar("SELECT COUNT(*) FROM booking WHERE status IN ('PENDING','ACTIVE')")+",\"complaints\":"+scalar("SELECT COUNT(*) FROM complaint WHERE status <> 'RESOLVED'")+"}");}
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
    private static Map<String,String> form(HttpExchange e)throws IOException{Map<String,String> m=new HashMap<>();if(!e.getRequestMethod().equals("POST"))return m;String body=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);for(String p:body.split("&")){String[] a=p.split("=",2);if(a.length==2)m.put(URLDecoder.decode(a[0],StandardCharsets.UTF_8),URLDecoder.decode(a[1],StandardCharsets.UTF_8));}return m;}
    private static Map<String,String> query(HttpExchange e){Map<String,String> m=new HashMap<>();String raw=e.getRequestURI().getRawQuery();if(raw==null)return m;for(String p:raw.split("&")){String[]a=p.split("=",2);if(a.length==2)m.put(URLDecoder.decode(a[0],StandardCharsets.UTF_8),URLDecoder.decode(a[1],StandardCharsets.UTF_8));}return m;}
    private static String cookie(HttpExchange e,String key){String raw=e.getRequestHeaders().getFirst("Cookie");if(raw!=null)for(String p:raw.split(";")){String[]a=p.trim().split("=",2);if(a.length==2&&a[0].equals(key))return a[1];}return "";}
    private static void serveFile(HttpExchange e,String name,String type)throws IOException{try(InputStream in=HostelLaundryWebServer.class.getClassLoader().getResourceAsStream(name)){if(in==null){send(e,404,"text/plain","File not found");return;}send(e,200,type,new String(in.readAllBytes(),StandardCharsets.UTF_8));}}
    private static void serveBinaryFile(HttpExchange e, String name, String type) throws IOException { try (InputStream in = HostelLaundryWebServer.class.getClassLoader().getResourceAsStream(name)) { if (in == null) { send(e, 404, "text/plain", "File not found"); return; } byte[] bytes = in.readAllBytes(); e.getResponseHeaders().set("Content-Type", type); e.getResponseHeaders().set("Cache-Control", "no-cache"); e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close(); } }
    private static void sendJson(HttpExchange e,int code,String body)throws IOException{send(e,code,"application/json; charset=utf-8",body);}
    private static void send(HttpExchange e,int code,String type,String body)throws IOException{byte[]b=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type",type);e.sendResponseHeaders(code,b.length);e.getResponseBody().write(b);e.close();}
    private static void append(StringBuilder b,String value){if(b.length()>1)b.append(',');b.append(value);}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static String setting(String name,String defaultValue){String value=System.getProperty(name);if(blank(value))value=System.getenv(name);return blank(value)?defaultValue:value;}
    private static boolean invitedTester(String email){for(String invited:setting("TESTER_EMAILS","").split(",")){if(invited.trim().equalsIgnoreCase(email==null?"":email.trim()))return true;}return false;}
    private static String escape(String value){return value==null?"":value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","");}
    private static boolean passwordMatches(String password, String stored) { return stored.startsWith("sha256:") ? stored.equals(hash(password)) : stored.equals(password); }
    private static boolean strongPassword(String password) { return password != null && password.length() >= 8 && password.matches(".*[A-Z].*") && password.matches(".*[a-z].*") && password.matches(".*\\d.*") && password.matches(".*[^A-Za-z0-9].*"); }
    private static String hash(String value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder("sha256:"); for (byte b : digest) out.append(String.format("%02x", b)); return out.toString(); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private record UserSession(int id,String name,String role,long expiresAt) { boolean student() { return "STUDENT".equals(role); } }
}
