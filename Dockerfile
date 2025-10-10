# Stage 1: Compile SCSS with dart-sass (npx)
FROM docker.io/library/node:20-alpine AS assets
WORKDIR /app

# Copy only manifest so npm install step can be cached
COPY package.json package-lock.json ./
RUN npm ci --silent

# Copy only the SCSS/CSS sources that need compiling
COPY src/main/webapp/css src/main/webapp/css

# Compile CSS
RUN npx -y sass --style=compressed --load-path src/main/webapp/css src/main/webapp/css

# Stage 2: Build the WAR with Maven
FROM docker.io/library/maven:3.8.8-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy only pom.xml first to cache dependency resolution
COPY pom.xml ./

# NOTE: the explicit "dependency:go-offline" can fail inside the builder
# because some IDE-specific plugins (eg. org.eclipse.m2e:lifecycle-mapping)
# are not available from central; remove the prefetch to avoid hard failures
# and let the subsequent 'mvn package' download needed artifacts.
RUN true

# Copy only source tree required for the build
COPY src ./src
# If your build needs other files (web-docs, scripts, etc.) add explicit COPY lines for them here:
# COPY web-docs ./web-docs
# COPY script ./script

# Bring the already compiled CSS from the assets stage
COPY --from=assets /app/src/main/webapp/css /app/src/main/webapp/css

# Generate build information (git info will be 'unknown' if .git is not copied intentionally)
RUN echo "BUILD_VERSION=\"$(git describe --tags --always 2>/dev/null || echo 'unknown')\"" > /app/build-info.env && \
    echo "BUILD_USER=\"$(id -un 2>/dev/null || echo 'builder')\"" >> /app/build-info.env && \
    echo "BUILD_DATE=\"$(date -u '+%Y-%m-%d %H:%M:%S UTC')\"" >> /app/build-info.env && \
    echo "# Generated build info for StarExec" >> /app/build-info.env

# Build the WAR (skip tests to speed iteration; remove -DskipTests if you want tests)
COPY package.json ./
RUN mvn -q clean package -DskipTests

# Stage 3: Run in Tomcat
FROM docker.io/library/tomcat:9.0-jdk17-temurin

# Install dependencies:
# - tcsh: required by benchmark processors
# - mysql-client: required by jobscripts for status updates to database
# - sudo: required by jobscript to run solver as sandbox user
# - build tools: required to compile runsolver
# - unzip: required to extract solver/benchmark archives
RUN apt-get update && apt-get install -y \
    tcsh \
    gettext-base \
    default-mysql-client \
    sudo \
    build-essential \
    curl \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Download and compile runsolver
# Modified to work in containers: removed NUMA support and cores option
RUN cd /tmp && \
    curl -L https://www.cril.univ-artois.fr/~roussel/runsolver/runsolver-3.4.1.tar.bz2 -o runsolver.tar.bz2 && \
    tar xjf runsolver.tar.bz2 && \
    cd runsolver/src && \
    # Apply patches to runsolver to remove NUMA support (not available in containers)
    # and change 'long long' to 'long' to avoid compilation issues
    sed -i 's/long long mem,memFree;/long mem,memFree;/g' runsolver.cc && \
    sed -i 's/-DWITH_NUMA//g' Makefile && \ 
    sed -i 's/-lnuma//g' Makefile && \
    make && \
    cp runsolver /usr/local/bin/ && \
    chmod +x /usr/local/bin/runsolver && \
    cd /tmp && rm -rf runsolver*

# Create sandbox users for job execution
RUN useradd -m -s /bin/bash starexec1 && \
    useradd -m -s /bin/bash starexec2

RUN rm -rf /usr/local/tomcat/webapps/*

# Copy build information and WAR only
COPY --from=builder /app/build-info.env /tmp/build-info.env
COPY --from=builder /app/target/starexec.war /usr/local/tomcat/webapps/starexec.war

# Expand the WAR to allow modification of context.xml
RUN mkdir -p /usr/local/tomcat/webapps/starexec && cd /usr/local/tomcat/webapps/starexec && jar -xf ../starexec.war

# Copy the context.xml template for runtime substitution
COPY src/main/webapp/META-INF/context.xml.template /usr/local/tomcat/webapps/starexec/META-INF/context.xml

# Configure environment variables and JVM system properties in setenv.sh for Tomcat.
# This script is evaluated at container runtime so it can pick up environment variables
# provided by the pod/container runtime (Helm, podman, docker, etc.). We also embed
# build-time metadata into the script so those values are available at runtime.
RUN . /tmp/build-info.env

COPY docker/setenv.sh /usr/local/tomcat/bin/setenv.sh
RUN chmod +x /usr/local/tomcat/bin/setenv.sh

# Copy entrypoint script that initializes SGE scripts and starts Tomcat
COPY docker/entrypoint.sh /usr/local/tomcat/bin/entrypoint.sh
RUN chmod +x /usr/local/tomcat/bin/entrypoint.sh

# Create backend directories required for job processing
# These directories must be writable by the Tomcat user for job submission and processing
# Note: These paths must match the environment variables set in deployment.yaml:
#   STAREXEC_BACKEND_WORKING_DIR=/app/backend
#   STAREXEC_DATA_DIR=/app/data
#   STAREXEC_SANDBOX_DIR=/app/sandbox
RUN mkdir -p /app/backend /app/work /app/data /app/sandbox && \
    mkdir -p /app/data/jobin /app/data/jobout /app/data/pictures && \
    mkdir -p /starexec/clustergraphs /starexec/jobgraphs && \
    chmod -R 755 /app/backend /app/work /app/data /app/sandbox /starexec

# Copy default pictures (only the files needed)
COPY --from=builder /app/src/main/resources/static/default-pics/* /app/data/pictures/
RUN chmod -R a+r /app/data/pictures || true

# Copy external configuration assets expected on filesystem (pagination SQL, email templates, sge scripts, etc.)
COPY --from=builder /app/src/main/java/org/starexec/config /config

EXPOSE 8080
CMD ["entrypoint.sh"]