# Build the Java application and include the MySQL JDBC dependency.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime

# Small runtime image for a private hosting platform.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/HostelLaundrySystem-1.0-SNAPSHOT.jar app.jar
COPY --from=build /app/target/dependency ./lib
EXPOSE 8080
CMD ["sh", "-c", "java -cp 'app.jar:lib/*' com.hostellaundry.hostellaundrysystem.HostelLaundryWebServer"]
