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
    bzip2

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
# Stage 3: Build Application with Maven
# ==============================================================================
FROM docker.io/library/maven:3.9-eclipse-temurin-17-alpine AS builder

LABEL stage=builder

WORKDIR /build

# Set Maven options for faster builds in containers
ENV MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Xmx3g"

# Copy POM and NPM files first for dependency caching
COPY pom.xml package.json package-lock.json* ./

# Download dependencies (cached layer if pom.xml unchanged)
RUN mvn dependency:go-offline dependency:resolve-plugins -B || true

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
# Stage 4: Runtime - Tomcat with Security Hardening
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
# - mysql-client: MariaDB client for basic compatibility (primary)
# - procps: Provides ps command with -p option for process monitoring
# - sudo: Required by job execution scripts to switch to sandbox users
RUN apk add --no-cache \
    bash \
    curl \
    ca-certificates \
    tzdata \
    tini \
    tcsh \
    unzip \
    util-linux \
    mysql-client \
    procps \
    sudo && \
    rm -rf /var/cache/apk/*

# Install official MySQL client for MySQL 8+ caching_sha2_password support
# This is required for connecting to modern MySQL servers from job execution scripts
RUN apk add --no-cache mysql-client-openssl=8.0.* || \
    (echo "Note: Official MySQL 8.0 client not available; using MariaDB client" && \
     echo "Job scripts will use mysql command from MariaDB client")

# Verify critical tools for job execution
RUN which flock lscpu mysql ps sudo && \
    flock --version && \
    lscpu --version && \
    mysql --version && \
    ps --version && \
    sudo --version

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
    # Remove default webapps for security
    rm -rf ${CATALINA_HOME}/webapps/examples \
           ${CATALINA_HOME}/webapps/docs \
           ${CATALINA_HOME}/webapps/ROOT \
           ${CATALINA_HOME}/webapps/manager \
           ${CATALINA_HOME}/webapps/host-manager && \
    # Create necessary directories
    mkdir -p ${STAREXEC_DATA_DIR} ${STAREXEC_LOG_DIR} && \
    mkdir -p /app/backend /app/work /app/data /app/sandbox && \
    mkdir -p /app/data/jobin /app/data/jobout /app/data/pictures && \
    mkdir -p /starexec/clustergraphs /starexec/jobgraphs && \
    # Set permissions
    chown -R starexec:starexec ${CATALINA_HOME} ${STAREXEC_DATA_DIR} ${STAREXEC_LOG_DIR} \
                               /app/backend /app/work /app/data /app/sandbox /starexec

# Copy runsolver binary from builder stage
COPY --from=runsolver-builder /tmp/runsolver-output/runsolver /usr/local/bin/runsolver
RUN chmod +x /usr/local/bin/runsolver && \
    chown root:root /usr/local/bin/runsolver

# Copy WAR from builder and expand it
COPY --from=builder --chown=starexec:starexec /build/output/starexec.war ${CATALINA_HOME}/webapps/starexec.war

# Expand WAR file to allow runtime configuration modification
RUN cd ${CATALINA_HOME}/webapps && \
    mkdir -p starexec && \
    cd starexec && \
    unzip -q ../starexec.war && \
    rm ../starexec.war && \
    # Use the template file with placeholders for runtime substitution
    cd META-INF && \
    cp context.xml.template context.xml && \
    chown -R starexec:starexec ${CATALINA_HOME}/webapps/starexec

# Copy default pictures (only the files needed)
COPY --from=builder /build/src/main/resources/static/default-pics/* /app/data/pictures/
RUN chmod -R a+r /app/data/pictures && \
    chown -R starexec:starexec /app/data/pictures

# Copy external configuration assets
COPY --from=builder /build/src/main/java/org/starexec/config /config
RUN chown -R starexec:starexec /config

# Patch SGE scripts for container compatibility
# Add --skip-ssl for MariaDB client (Alpine's mysql command)
# This disables SSL for container-internal MySQL connections
RUN sed -i 's/mysql -u/mysql --skip-ssl -u/g' /config/sge/functions.bash

# Copy build metadata
COPY --from=builder --chown=starexec:starexec /build/build-metadata/build-info.properties /tmp/build-info.properties

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

