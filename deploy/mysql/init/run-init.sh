#!/bin/sh
set -eu

MYSQL_HOST="${MYSQL_HOST:-mysql}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-nip}"
MYSQL_USER="${MYSQL_USER:-nip}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-nip}"

echo "Waiting for MySQL at ${MYSQL_HOST}:${MYSQL_PORT}..."
until MYSQL_PWD="${MYSQL_PASSWORD}" mysqladmin ping \
  -h "${MYSQL_HOST}" \
  -P "${MYSQL_PORT}" \
  -u "${MYSQL_USER}" \
  --silent >/dev/null 2>&1; do
  sleep 2
done

if [ -n "${MYSQL_ROOT_PASSWORD:-}" ]; then
  echo "Ensuring databases exist with root account when available..."
  if MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql \
    -h "${MYSQL_HOST}" \
    -P "${MYSQL_PORT}" \
    -uroot \
    -e "CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}\` DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci; CREATE DATABASE IF NOT EXISTS eladmin DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci; GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%'; GRANT ALL PRIVILEGES ON eladmin.* TO '${MYSQL_USER}'@'%'; FLUSH PRIVILEGES;" >/dev/null 2>&1; then
    echo "Database check finished."
  else
    echo "Root database check skipped; continuing with ${MYSQL_USER}."
  fi
fi

for sql_file in \
  /sql/nip-base-ddl-mysql.sql \
  /sql/nip-home-ddl-mysql.sql \
  /sql/nip-home-dml-mysql.sql \
  /sql/nip-rbac-ddl-mysql.sql \
  /sql/nip-rbac-dml-mysql.sql \
  /sql/nip-rt-ddl-mysql.sql; do
  echo "Running ${sql_file}..."
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
    -h "${MYSQL_HOST}" \
    -P "${MYSQL_PORT}" \
    -u "${MYSQL_USER}" \
    --default-character-set=utf8mb4 \
    "${MYSQL_DATABASE}" < "${sql_file}"
done

echo "MySQL initialization finished."
