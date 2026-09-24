# ---- 1. Build the jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Download dependencies first so they're cached between deploys
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- 2. Run it, with ffmpeg + fonts for reel rendering ----
FROM eclipse-temurin:21-jre

# ffmpeg: renders reels
# fonts-noto-core: Latin + Devanagari, Tamil, Telugu, Kannada, Malayalam, Bengali,
#                  Gujarati, Gurmukhi, Odia — so on-screen text works in every language we support
# fonts-noto-color-emoji: emoji in on-screen text
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg fonts-noto-core fonts-noto-color-emoji \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/postkaro-api-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]