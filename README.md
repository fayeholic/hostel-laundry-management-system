# Hostel Laundry Management System

Java 21 and MySQL web application for student bookings, complaints, and administrator facility management.

## Local demo in NetBeans

1. Start MySQL and execute `database/schema.sql`.
2. Open this Maven project in Apache NetBeans.
3. Right-click the project, choose **Clean and Build**, then **Run Project**.
4. Open `http://localhost:8080`.

Set your local database credentials with Java properties or environment variables: `DB_URL`, `DB_USER`, and `DB_PASSWORD`. They are intentionally not stored in this public repository.

## Public beta deployment

Use a Java hosting service and a managed MySQL database. Add these environment variables in the hosting dashboard; do not put passwords in source code:

```text
PORT=<provided by host>
DB_URL=jdbc:mysql://<database-host>:3306/<database-name>?useSSL=true&requireSSL=true
DB_USER=<database-user>
DB_PASSWORD=<database-password>
COOKIE_SECURE=true
PRIVATE_BETA=false
ADMIN_INITIAL_PASSWORD=<a-strong-new-admin-password>
```

With `PRIVATE_BETA=false`, anyone with the website link can create a student account and test the system. The deployment must use HTTPS so the secure session cookie can be sent.

Before deploying, import `database/schema.sql` into the managed MySQL database, then set the environment variables above. The application creates its `admin` and `notification` support tables when it starts.

Set `ADMIN_INITIAL_PASSWORD` to a strong secret before the first start. The application creates the administrator account as `admin@hostellaundry.com` without ever storing that password in GitHub.
