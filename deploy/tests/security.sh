#!/bin/sh
set -eu
umask 077
test_dir=$(mktemp -d)
generate() { java -cp '/app/bootstrap:/app/bootstrap/*' DeploymentSecrets "$1" "$2"; }
generate "$test_dir/first" "$test_dir/first.env"
original=$(sha256sum "$test_dir/first/security.properties")
generate "$test_dir/first" "$test_dir/repeated.env"
test "$original" = "$(sha256sum "$test_dir/first/security.properties")"
cmp "$test_dir/first.env" "$test_dir/repeated.env"
generate "$test_dir/second" "$test_dir/second.env"
! cmp -s "$test_dir/first.env" "$test_dir/second.env"
. "$test_dir/first.env"
generate "$test_dir/imported" "$test_dir/imported.env"
cmp "$test_dir/first.env" "$test_dir/imported.env"
export SECURITY_SM2_PUBLICKEY=04deadbeef
if generate "$test_dir/invalid" "$test_dir/invalid.env" > /dev/null 2>&1; then
    echo 'Mismatched SM2 keys were accepted' >&2
    exit 1
fi
test ! -e "$test_dir/invalid/security.properties"
test "$(stat -c '%a' "$test_dir/first/security.properties")" = 600
echo 'PASS: generation, reuse, independent installations, explicit import, pair validation, permissions'
