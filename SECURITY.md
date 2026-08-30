# Security Policy

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting or security advisory feature
for this repository. Do not publish credentials, exploit details, or personal data
in a public issue.

Include the affected version or commit, reproduction conditions, expected impact,
and a minimal proof of concept when possible. Maintainers will acknowledge the
report and coordinate disclosure after a fix is available.

## Deployment credentials

Files ending in `.example` contain examples only. Operators must provide their own
database passwords, API keys, JWT signing secret, encryption keys, and administrator
passwords. Any credential that has previously been committed to Git history must be
rotated; deleting it from the current tree does not revoke it or erase it from history.
