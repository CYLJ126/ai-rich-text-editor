#!/bin/sh
set -eu

cp /arte-docs/arte-intro.md /var/lib/mysql-files/arte-intro.md
cp /arte-docs/arte-features.md /var/lib/mysql-files/arte-features.md
chmod 0777 /var/lib/mysql-files/content-init-state
touch /var/lib/mysql-files/content-init-state/public-docs.pending
