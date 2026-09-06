#!/bin/sh
set -e
umask 077

java -cp '/app/bootstrap:/app/bootstrap/*' DeploymentSecrets /app/secrets /app/config/security.env
. /app/config/security.env

if [ -n "${ARTE_BACKEND_CONFIG_FILE:-}" ]; then
  awk '/^(spring\.datasource\.druid\.(app|chlorophyll)\.|elasticsearch\.)/' \
    "${ARTE_BACKEND_CONFIG_FILE}" > /app/config/external-backend.properties
  export SPRING_CONFIG_ADDITIONAL_LOCATION="optional:file:/app/config/external-backend.properties"
fi

REDIS_ADDRESS="${REDIS_ADDRESS:-redis://redis:6379}"

cat > /app/config/redisson.yml <<EOF
singleServerConfig:
  password: ${REDIS_PASSWORD:-}
  clientName:
  address: "${REDIS_ADDRESS}"
EOF

exec sh -c "java ${JAVA_OPTS:-} -Dloader.path=/app/config -cp /app/arte-app-boot.jar org.springframework.boot.loader.launch.PropertiesLauncher"
