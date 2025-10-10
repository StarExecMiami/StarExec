#!/bin/bash
set -e

# Substitute environment variables in context.xml
envsubst < /usr/local/tomcat/webapps/starexec/META-INF/context.xml > /usr/local/tomcat/webapps/starexec/META-INF/context.xml.tmp
mv /usr/local/tomcat/webapps/starexec/META-INF/context.xml.tmp /usr/local/tomcat/webapps/starexec/META-INF/context.xml

# Copy SGE scripts to data directory if they do not exist
if [ ! -f /app/data/sge_scripts/functions.bash ]; then
    echo "Initializing SGE scripts in /app/data/sge_scripts..."
    mkdir -p /app/data/sge_scripts
    cp -r /config/sge/* /app/data/sge_scripts/
    chmod -R 755 /app/data/sge_scripts
    echo "SGE scripts initialized successfully."
fi

# Start Tomcat
exec catalina.sh run
