# Security Policy

## Supported Versions

Only the latest release on the `containerised` line receives security fixes:

| Version | Supported | Notes |
|---------|-----------|-------|
| `2.6.x` (`containerised`) | Yes | Active public release |
| `<= 2.4.x` / legacy `UMprod` | No | Upgrade to `2.6.0` or later |

## Reporting a Vulnerability

**Do not report security vulnerabilities in public GitHub issues, pull requests, or discussions.**

Send reports privately using one of these channels:

1. **GitHub Security Advisories** (preferred): open a private advisory in the repository's **Security** tab.
2. **Email:** `security@starexec.org`

Please include:

- A clear description of the vulnerability and affected component or endpoint
- Step-by-step reproduction instructions or a minimal proof of concept
- Potential security impact (privileges required, data exposed, execution impact)
- Any suggested remediation or workaround

We aim to acknowledge reports promptly, keep reporters updated during triage, and coordinate disclosure after a fix is validated.

## Security Hardening and Operations

For deployment hardening, credential management, CSRF protection details, private upload staging (`STAREXEC_UPLOAD_STAGING_DIR`), container isolation boundaries, incident response, and CI vulnerability scanning (`OWASP Dependency-Check`, `npm audit`, Trivy, CodeQL), see [docs/SECURITY.md](docs/SECURITY.md).
