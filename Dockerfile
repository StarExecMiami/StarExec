# Etapa 1: compilar SCSS con dart-sass (npx)
FROM node:20-alpine AS assets
WORKDIR /app
COPY . .
RUN npx -y sass --style=compressed --load-path src/main/webapp/css src/main/webapp/css:src/main/webapp/css

# Etapa 2: construir el WAR con Maven
FROM maven:3.8.8-eclipse-temurin-11 AS builder
WORKDIR /app
COPY . .
# Traer los CSS ya compilados desde la etapa de assets
COPY --from=assets /app/src/main/webapp/css /app/src/main/webapp/css

# Generate build information
RUN echo "BUILD_VERSION=\"$(git describe --tags --always 2>/dev/null || echo 'unknown')\"" > /app/build-info.env && \
    echo "BUILD_USER=\"$(id -un 2>/dev/null || echo 'builder')\"" >> /app/build-info.env && \
    echo "BUILD_DATE=\"$(date -u '+%Y-%m-%d %H:%M:%S UTC')\"" >> /app/build-info.env && \
    echo "# Generated build info for StarExec" >> /app/build-info.env

# Construir el WAR
RUN mvn -q clean package -Dmaven.test.skip=true

# Etapa 3: ejecutar en Tomcat
FROM tomcat:9.0-jdk11-temurin
RUN rm -rf /usr/local/tomcat/webapps/*
# Copiar información de build
COPY --from=builder /app/build-info.env /tmp/build-info.env
# Configurar variables de entorno en setenv.sh para Tomcat
RUN . /tmp/build-info.env && \
    echo "export STAREXEC_BUILD_VERSION=$BUILD_VERSION" >> /usr/local/tomcat/bin/setenv.sh && \
    echo "export STAREXEC_BUILD_USER=$BUILD_USER" >> /usr/local/tomcat/bin/setenv.sh && \
    echo "export STAREXEC_BUILD_DATE=$BUILD_DATE" >> /usr/local/tomcat/bin/setenv.sh && \
    chmod +x /usr/local/tomcat/bin/setenv.sh

# Desplegar como starexec.war para acceso en http://localhost:8080/starexec/
COPY --from=builder /app/target/starexec.war /usr/local/tomcat/webapps/starexec.war

EXPOSE 8080
CMD ["catalina.sh", "run"]