## Prerequisites for StarExec Development

Ensure the host meets the following requirements.

Required:

- Java 17+ (for local manual builds).
- Maven 3.8+ (for manual builds and packaging).
- PostgreSQL 15+ (production DB).
- Container runtime: Podman (recommended) or Docker.

Optional (for development):

- Node.js 20+ (SCSS compilation), `postgresql-client` for DB access.

Version matrix (high level):

| Component | Recommended version |
|-----------|---------------------|
| Java | 17 |
| Maven | 3.8+ |
| Node.js | 20+ (dev only) |
| PostgreSQL | 15 |

Notes:

- Required vs optional dependencies are separated above. When using containers
  (Makefile or Docker Compose), most host dependencies are optional because
  builds run inside the container images.