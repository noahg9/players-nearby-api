# ---- Build stage ----
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app

# Copy Gradle wrapper files first — Docker layer cache: rebuild only when build files change
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/

# Make gradlew executable
RUN chmod +x ./gradlew

# Download dependencies (cached layer if build files unchanged)
RUN ./gradlew dependencies --no-daemon -q

# Copy source code
COPY src/ src/

# Build the fat jar (skip tests — CI handles tests separately)
RUN ./gradlew bootJar --no-daemon -x test

# ---- Runtime stage ----
FROM eclipse-temurin:25-jdk AS runtime
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
