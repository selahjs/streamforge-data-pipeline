# ==============================================================================
# STAGE 1: BUILDER STAGE
# Used to compile the code and produce the executable JAR file.
# We use a JDK (Java Development Kit) here.
# ==============================================================================
FROM eclipse-temurin:17-jdk-jammy AS builder
# Use /app as the working directory inside the container
WORKDIR /app

# Copy the build definition files first (pom.xml, settings.xml, or build.gradle)
# This allows Docker to use caching when only source code changes.
COPY pom.xml .
# 2. Add the Maven Wrapper and related files (.mvn directory)
# This is the FIX for 'mvn: not found'
COPY mvnw .
COPY .mvn .mvn
# Copy source code and build files
COPY src src

# The '-DskipTests' flag speeds up the deployment build process.
# 3. Execute the build command using the wrapper script
RUN ./mvnw clean package -DskipTests

# ==============================================================================
# STAGE 2: RUNTIME STAGE
# Used to run the application. We use a JRE (Java Runtime Environment) here
# which is much smaller than the JDK.
# ==============================================================================
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy the resulting executable JAR file from the 'builder' stage
# The file name is typically derived from your pom.xml (e.g., target/my-app.jar)
# Replace 'target/csv-upload-service-0.0.1-SNAPSHOT.jar' with your actual JAR name.
# COPY the correctly named JAR file: rest-service-0.0.1-SNAPSHOT.jar
COPY --from=builder /app/target/rest-service-0.0.1-SNAPSHOT.jar app.jar

# Expose the port your Spring Boot app runs on (default is 8080)
EXPOSE 8080

# The command to execute the application when the container starts
# The '-jar' flag points to the copied JAR file.
ENTRYPOINT ["java", "-jar", "app.jar"]