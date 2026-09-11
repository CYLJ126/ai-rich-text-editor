#!/bin/sh
# The MySQL entrypoint sources this file when it lacks the executable bit
# (e.g. after a Windows checkout). Keep "set -u" out: it would leak into the
# entrypoint shell and crash docker_process_sql ($1 unbound) on the next file.
set -e

cp /arte-docs/arte-intro.md /var/lib/mysql-files/arte-intro.md
cp /arte-docs/arte-features.md /var/lib/mysql-files/arte-features.md
chmod 0777 /var/lib/mysql-files/content-init-state
touch /var/lib/mysql-files/content-init-state/public-docs.pending
