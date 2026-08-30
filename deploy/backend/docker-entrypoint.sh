#!/bin/sh
set -e

REDIS_ADDRESS="${REDIS_ADDRESS:-redis://redis:6379}"

mkdir -p /tmp/BOOT-INF/classes
cat > /tmp/BOOT-INF/classes/redisson.yml <<EOF
singleServerConfig:
  password: ${REDIS_PASSWORD:-}
  clientName:
  address: "${REDIS_ADDRESS}"
EOF

cd /tmp
zip -q -u /app/nip-app-boot.jar BOOT-INF/classes/redisson.yml
cd /app

exec sh -c "java ${JAVA_OPTS:-} -jar /app/nip-app-boot.jar"
