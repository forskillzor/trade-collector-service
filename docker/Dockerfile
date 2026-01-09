# Stage 1: Build with GraalVM
FROM ghcr.io/graalvm/graalvm-ce:java17-22.3.2 AS builder

WORKDIR /app

# Install native-image
RUN gu install native-image

# Copy Gradle files
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Copy source code
COPY src src

# Build native image
RUN ./gradlew nativeCompile

# Stage 2: Minimal runtime
FROM ubuntu:22.04

RUN apt-get update && apt-get install -y \
    libstdc++6 \
    libgcc-s1 \
    libz-dev \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy native binary from builder
COPY --from=builder /app/build/native/nativeCompile/trade-collector .

# Copy config
COPY config/production.json /app/config.json

# Create user
RUN useradd -m -u 1000 trader && \
    mkdir -p /var/lib/trade-collector/{aggregates,exports,logs} && \
    chown -R trader:trader /var/lib/trade-collector /app

USER trader

# JVM flags for Arrow
ENV JAVA_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"

EXPOSE 8080

CMD ["./trade-collector"]