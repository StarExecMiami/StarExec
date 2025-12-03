# ==============================================================================
# Stage 1: Compile SCSS/CSS Assets
# ==============================================================================
FROM docker.io/library/node:20-alpine AS assets

LABEL stage=assets

WORKDIR /build

# Copy package files first (better caching)
COPY package.json package-lock.json* ./

# Install dependencies (npm ci for deterministic builds)
RUN npm ci --silent

# Copy CSS sources
COPY src/main/webapp/css ./src/main/webapp/css

# Compile SCSS to CSS
RUN npx sass --style=compressed --load-path src/main/webapp/css src/main/webapp/css

# ==============================================================================
# Stage 2: Compile runsolver (separate stage for build tools)
# ==============================================================================
FROM docker.io/library/alpine:3.19 AS runsolver-builder

WORKDIR /tmp

# Install build dependencies
RUN apk add --no-cache \
    curl \
    build-base \
    tar \
    bzip2 \
    numactl-dev

# Download, patch, and compile runsolver
RUN curl -L https://www.cril.univ-artois.fr/~roussel/runsolver/runsolver-3.4.1.tar.bz2 -o runsolver.tar.bz2 && \
    tar xjf runsolver.tar.bz2 && \
    cd runsolver/src && \
    # Apply patches to runsolver to remove NUMA support (not available in containers)
    # and change 'long long' to 'long' to avoid compilation issues
    sed -i 's/long long mem,memFree;/long mem,memFree;/g' runsolver.cc && \
    sed -i 's/-DWITH_NUMA//g' Makefile && \
    sed -i 's/-lnuma//g' Makefile && \
    make && \
    mkdir -p /tmp/runsolver-output && \
    cp runsolver /tmp/runsolver-output/runsolver && \
    chmod +x /tmp/runsolver-output/runsolver

# ==============================================================================
# Stage 3: Build Credential Handler for Tomcat lib
# ==============================================================================
FROM docker.io/library/maven:3.9-eclipse-temurin-17-alpine AS credential-handler

WORKDIR /build
COPY tomcat-credential-handler/pom.xml ./pom.xml
COPY tomcat-credential-handler/src ./src
RUN mvn clean package -B -q

# ==============================================================================
# Stage 4: Build Application with Maven
# ==============================================================================
FROM docker.io/library/maven:3.9-eclipse-temurin-17-alpine AS builder

LABEL stage=builder

WORKDIR /build

# Set Maven options for faster builds in containers
ENV MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Xmx3g"

# Copy POM and NPM files first for dependency caching
COPY pom.xml package.json package-lock.json* ./

# Download dependencies 
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Copy compiled CSS from assets stage
COPY --from=assets /build/src/main/webapp/css ./src/main/webapp/css

# Build metadata using ARG (no git dependency needed)
ARG BUILD_DATE=unknown
ARG VCS_REF=unknown
ARG VERSION=unknown

RUN mkdir -p /build/build-metadata && \
    echo "BUILD_VERSION=${VERSION}" > /build/build-metadata/build-info.properties && \
    echo "BUILD_DATE=${BUILD_DATE}" >> /build/build-metadata/build-info.properties && \
    echo "VCS_REF=${VCS_REF}" >> /build/build-metadata/build-info.properties && \
    echo "BUILD_USER=docker" >> /build/build-metadata/build-info.properties

# Build the WAR (skip tests to speed iteration)
RUN mvn clean package -DskipTests -B -V && \
    mkdir -p /build/output && \
    cp target/starexec.war /build/output/starexec.war

# ==============================================================================
# Stage 5: Runtime - Tomcat with Security Hardening
# ==============================================================================
FROM docker.io/library/eclipse-temurin:17-jre-alpine

LABEL maintainer="StarExec Team" \
    org.opencontainers.image.title="StarExec" \
    org.opencontainers.image.description="Logic solver evaluation platform" \
    org.opencontainers.image.vendor="StarExec"

# Install runtime dependencies
# - bash: Required for job execution scripts
# - curl: Health checks and downloads
# - ca-certificates: HTTPS/TLS support
# - tzdata: Timezone data
# - tini: Init process for proper signal handling
# - tcsh: Alternative shell (solver requirements)
# - unzip: WAR/archive extraction
# - util-linux: Provides flock (with -w option) and lscpu for job scripts
# - postgresql-client: PostgreSQL client for database connectivity
# - procps: Provides ps command with -p option for process monitoring
# - sudo: Required by job execution scripts to switch to sandbox users
# - libstdc++: C++ standard library (required by runsolver)
# - libgcc: GCC runtime library (required by runsolver)
# - gcompat: glibc compatibility layer for musl (required by solver binaries compiled against glibc)
RUN apk add --no-cache \
    bash \
    curl \
    ca-certificates \
    tzdata \
    tini \
    tcsh \
    unzip \
    util-linux \
    postgresql-client \
    procps \
    sudo \
    libstdc++ \
    libgcc \
    gcompat && \
    rm -rf /var/cache/apk/*

# Verify critical tools for job execution
RUN which flock lscpu psql ps sudo && \
    flock --version && \
    lscpu --version && \
    psql --version && \
    ps --version && \
    sudo --version

# Verify PostgreSQL client tools required for migration checks are present
RUN echo "Verifying PostgreSQL client tools for migrations..." && \
    which pg_isready || { echo "ERROR: pg_isready not found - required for migration DB checks"; exit 1; } && \
    pg_isready --version && \
    echo "✅ pg_isready verified successfully"

# Database migrations are handled by EmbeddedFlywayLauncher (in the application WAR)
# which uses flyway-core from the Maven dependency (included in WEB-INF/lib).
# This approach keeps the container image smaller and avoids bundling the Flyway CLI.

# Create non-root user and group
RUN addgroup -g 1000 starexec && \
    adduser -D -u 1000 -G starexec starexec

# Set environment variables
ENV CATALINA_HOME=/opt/tomcat \
    TOMCAT_VERSION=9.0.82 \
    JAVA_OPTS="-Djava.security.egd=file:/dev/./urandom -Djava.awt.headless=true -Xms512m -Xmx2048m -XX:+UseG1GC -XX:+UseStringDeduplication" \
    STAREXEC_DATA_DIR=/var/starexec/data \
    STAREXEC_LOG_DIR=/var/log/starexec

# Download and install Tomcat
RUN cd /tmp && \
    curl -L https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz -o tomcat.tar.gz && \
    tar xzf tomcat.tar.gz && \
    mv apache-tomcat-${TOMCAT_VERSION} ${CATALINA_HOME} && \
    rm tomcat.tar.gz && \
    # Remove default webapps for security (keep ROOT for loading page)
    rm -rf ${CATALINA_HOME}/webapps/examples \
    ${CATALINA_HOME}/webapps/docs \
    ${CATALINA_HOME}/webapps/manager \
    ${CATALINA_HOME}/webapps/host-manager && \
    # Create minimal ROOT webapp with loading page placeholder
    mkdir -p ${CATALINA_HOME}/webapps/ROOT && \
    # Create necessary directories
    mkdir -p ${STAREXEC_DATA_DIR} ${STAREXEC_LOG_DIR} && \
    mkdir -p /app/backend /app/work /app/data /app/sandbox && \
    mkdir -p /app/data/jobin /app/data/jobout /app/data/pictures && \
    # Create jobgraphs under the expanded webapp (will be created during WAR expansion)
    # Avoid creating a top-level /starexec directory here so we can create a top-level
    # symlink to the webapp at /starexec later during WAR expansion.
    # Set permissions
    chown -R starexec:starexec ${CATALINA_HOME} ${STAREXEC_DATA_DIR} ${STAREXEC_LOG_DIR} \
    /app/backend /app/work /app/data /app/sandbox

# Copy runsolver binary from builder stage
COPY --from=runsolver-builder /tmp/runsolver-output/runsolver /usr/local/bin/runsolver
RUN chmod +x /usr/local/bin/runsolver && \
    chown root:root /usr/local/bin/runsolver

# Copy GetComputerInfo binary for system monitoring
COPY scripts/GetComputerInfo /usr/local/bin/GetComputerInfo
RUN chmod +x /usr/local/bin/GetComputerInfo && \
    chown root:root /usr/local/bin/GetComputerInfo && \
    mkdir -p /home/starexec/bin && \
    ln -s /usr/local/bin/GetComputerInfo /home/starexec/bin/GetComputerInfo

# Copy BCrypt credential handler to Tomcat lib (needed for Realm initialization)
COPY --from=credential-handler /build/target/starexec-credential-handler-1.0.0.jar ${CATALINA_HOME}/lib/

# Copy WAR from builder and expand it
COPY --from=builder --chown=starexec:starexec /build/output/starexec.war ${CATALINA_HOME}/webapps/starexec.war

# Expand WAR file to allow runtime configuration modification
RUN cd ${CATALINA_HOME}/webapps && \
    mkdir -p starexec && \
    cd starexec && \
    unzip -q ../starexec.war && \
    rm ../starexec.war && \
    # Create clustergraphs directory for chart images
    mkdir -p secure/clustergraphs && \
    # Create symlink for STAREXEC_ROOT
    ln -sf ${CATALINA_HOME}/webapps/starexec /starexec && \
    chown -R starexec:starexec ${CATALINA_HOME}/webapps/starexec /starexec

# Copy default pictures (only the files needed)
COPY --from=builder /build/src/main/resources/static/default-pics/* /app/data/pictures/
RUN chmod -R a+r /app/data/pictures && \
    chown -R starexec:starexec /app/data/pictures

# Copy external configuration assets
COPY --from=builder /build/src/main/java/org/starexec/config /config
RUN chown -R starexec:starexec /config

# Copy build metadata
COPY --from=builder --chown=starexec:starexec /build/build-metadata/build-info.properties /tmp/build-info.properties

# Copy loading page for startup
COPY --chown=starexec:starexec docker/loading.html ${CATALINA_HOME}/webapps/ROOT/index.html

# Copy entrypoint and configuration scripts
COPY --chown=starexec:starexec docker/entrypoint.sh /usr/local/bin/entrypoint.sh
COPY --chown=starexec:starexec docker/setenv.sh /usr/local/tomcat/bin/setenv.sh
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/tomcat/bin/setenv.sh

# Create sandbox users for job execution
RUN addgroup -g 2001 starexec1 && \
    adduser -D -u 2001 -G starexec1 starexec1 && \
    addgroup -g 2002 starexec2 && \
    adduser -D -u 2002 -G starexec2 starexec2

# Configure sudo for passwordless execution (required for job execution)
# Allow starexec user to run commands as starexec1/starexec2 without password
# Also allow chown as root for file ownership management during solver uploads
RUN echo "starexec ALL=(starexec1,starexec2) NOPASSWD: ALL" > /etc/sudoers.d/starexec && \
    echo "starexec ALL=(root) NOPASSWD: /bin/chown" >> /etc/sudoers.d/starexec && \
    chmod 0440 /etc/sudoers.d/starexec

# Set proper permissions for sandbox directory so all users can access it
# Add sandbox users to starexec group to allow shared access
RUN addgroup starexec1 starexec && \
    addgroup starexec2 starexec && \
    chmod g+rwxs /app/sandbox && \
    chmod g+rwxs /app/work

# Security hardening - Update Tomcat server.xml
RUN sed -i 's/port="8080"/port="8080" maxThreads="200" minSpareThreads="10"/' ${CATALINA_HOME}/conf/server.xml

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/starexec/ || exit 1

# Expose port
EXPOSE 8080

# Switch to non-root user
USER starexec

# Set working directory
WORKDIR ${CATALINA_HOME}

# Use tini as init process (handles signals properly)
ENTRYPOINT ["/sbin/tini", "--"]

# Start with entrypoint script
CMD ["/usr/local/bin/entrypoint.sh"]

