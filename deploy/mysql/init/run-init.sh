#!/bin/sh
set -eu

MYSQL_HOST="${MYSQL_HOST:-mysql}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-arte}"
MYSQL_USER="${MYSQL_USER:-arte}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-arte}"

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
  /sql/arte-base-ddl-mysql.sql \
  /sql/arte-home-ddl-mysql.sql \
  /sql/arte-home-dml-mysql.sql \
  /sql/arte-rbac-ddl-mysql.sql \
  /sql/arte-rbac-dml-mysql.sql \
  /sql/arte-rt-ddl-mysql.sql; do
  echo "Running ${sql_file}..."
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
    -h "${MYSQL_HOST}" \
    -P "${MYSQL_PORT}" \
    -u "${MYSQL_USER}" \
    --default-character-set=utf8mb4 \
    "${MYSQL_DATABASE}" < "${sql_file}"
done

echo "MySQL initialization finished."
