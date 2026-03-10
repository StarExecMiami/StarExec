# Developer Guide

Complete guide to setting up a StarExec development environment and contributing to the project.

## Overview

This guide covers:

- Development environment setup
- Building from source
- Running tests
- Code structure overview
- Contribution workflow
- Debugging techniques

---

## Quick Start

```bash
# Clone repository
git clone https://github.com/StarExecMiami/StarExec.git
cd StarExec
git checkout containerised

# Start development environment
make start

# Access at http://localhost:7827/starexec
# Default credentials: admin:admin
```

---

## Prerequisites

### Required

| Tool | Version | Purpose |
|------|---------|---------|
| Java JDK | 17+ | Application runtime and compilation |
| Maven | 3.8+ | Build system |
| Podman | 4.0+ | Container runtime |
| Git | 2.0+ | Version control |

### Optional

| Tool | Version | Purpose |
|------|---------|---------|
| Node.js | 20+ | SCSS compilation |
| PostgreSQL client | 15+ | Direct database access |
| IntelliJ IDEA / VS Code | Latest | IDE with Java support |

### Installation

#### Ubuntu/Debian

```bash
# Java and Maven
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk maven

# Podman
sudo apt-get install -y podman catatonit passt fuse-overlayfs

# Optional: Node.js
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs

# Optional: PostgreSQL client
sudo apt-get install -y postgresql-client
```

#### macOS

```bash
# Homebrew
brew install openjdk@17 maven podman

# Add Java to PATH
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc

# Optional
brew install node postgresql@15
```

#### Verify Installation

```bash
java --version    # Should show 17+
mvn --version     # Should show 3.8+
podman --version  # Should show 4.0+
node --version    # Should show 20+ (optional)
```

---

## Project Structure

```
StarExec/
├── src/
│   ├── main/
│   │   ├── java/org/starexec/    # Java source code
│   │   │   ├── app/              # Application bootstrap
│   │   │   ├── backend/          # Execution backends
│   │   │   ├── command/          # CLI (StarExecCommand)
│   │   │   ├── constants/        # Constants and configuration
│   │   │   ├── data/             # Data access layer
│   │   │   ├── jobs/             # Job management
│   │   │   ├── servlets/         # HTTP handlers
│   │   │   ├── services/         # REST services
│   │   │   └── util/             # Utilities
│   │   └── webapp/               # Web resources
│   │       ├── css/              # SCSS stylesheets
│   │       ├── js/               # JavaScript
│   │       ├── images/           # Static images
│   │       └── secure/           # JSP pages
│   └── test/java/                # Unit tests
├── sql/                          # Flyway migrations
├── charts/starexec/              # Helm chart
├── scripts/                      # DevOps scripts
├── docker/                       # Container files
├── docs/                         # Documentation
├── Dockerfile                    # Container build
├── Makefile                      # DevOps automation
├── pom.xml                       # Maven configuration
└── docker-compose.yml            # Docker Compose config
```

---

## Building from Source

### Development Build

```bash
# Build with Maven (uses cache)
mvn clean package -DskipTests

# Build container image
make build

# Build without cache (slower, fresh build)
make build-fresh
```

### Production Build

```bash
# Build production image with registry tag
make build-prod IMAGE_TAG=v1.2.3
```

### Build Artifacts

| Artifact | Location | Description |
|----------|----------|-------------|
| WAR file | `target/starexec.war` | Deployable web application |
| Container | `starexec:latest` | Container image |
| Helm chart | `charts/starexec/` | Kubernetes deployment |

### Common Build Issues

#### Maven Out of Memory

```bash
export MAVEN_OPTS="-Xmx2g"
mvn clean package
```

#### SCSS Compilation Fails

```bash
# Ensure Node.js is installed
node --version

# Install SCSS compiler
npm install -g sass

# Compile manually
sass src/main/webapp/css/global.scss:src/main/webapp/css/global.css
```

#### Java Version Mismatch

```bash
# Check Java version
java --version

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export PATH=$JAVA_HOME/bin:$PATH
```

---

## Running the Application

### With Makefile (Recommended)

```bash
# Start development environment
make start

# Stop environment
make stop

# View logs
make logs

# Check status
make status

# Complete reset (deletes data)
make reset ENV=dev
```

### With Docker Compose

```bash
# Start
docker compose up --build

# Stop
docker compose down

# With fresh database
docker compose down -v && docker compose up --build
```

### Manual Run (Advanced)

```bash
# Start PostgreSQL
podman run -d --name starexec-postgres \
  -e POSTGRES_USER=starexec \
  -e POSTGRES_PASSWORD=starexec \
  -e POSTGRES_DB=starexec \
  -p 5432:5432 \
  postgres:15

# Run application
mvn tomcat7:run
```

---

## Running Tests

### All Tests

```bash
mvn test
```

### Specific Test Class

```bash
mvn test -Dtest=LocalBackendTest
```

### Specific Test Method

```bash
mvn test -Dtest=LocalBackendTest#testJobSubmission
```

### Skip Tests During Build

```bash
mvn package -DskipTests
```

### Test Coverage

```bash
mvn jacoco:report
# Report at: target/site/jacoco/index.html
```

### Integration Tests

```bash
# Requires running database
mvn verify -Pintegration-tests
```

---

## Code Style

### Java Style Guide

- **Indentation**: 4 spaces (no tabs)
- **Line length**: 120 characters max
- **Naming**:
  - Classes: `PascalCase`
  - Methods/variables: `camelCase`
  - Constants: `SCREAMING_SNAKE_CASE`
- **Braces**: Same line as statement
- **Imports**: No wildcards, organize by package

### Example

```java
package org.starexec.example;

import org.starexec.data.Database;
import org.starexec.util.Util;

public class ExampleClass {
    
    private static final int MAX_RETRIES = 3;
    
    private final Database database;
    
    public ExampleClass(Database database) {
        this.database = database;
    }
    
    public void processJob(int jobId) {
        if (jobId <= 0) {
            throw new IllegalArgumentException("Invalid job ID: " + jobId);
        }
        
        for (int i = 0; i < MAX_RETRIES; i++) {
            if (tryProcess(jobId)) {
                return;
            }
        }
    }
    
    private boolean tryProcess(int jobId) {
        // Implementation
        return true;
    }
}
```

### JavaScript Style

- Use ES6+ features
- Prefer `const` over `let`
- Use template literals for string interpolation
- Document public APIs with JSDoc

### SCSS Style

- Use variables for colors and spacing
- Follow BEM naming convention
- Keep specificity low
- Use component-based organization

---

## Database Development

### Accessing the Database

```bash
# Via Makefile
make db-shell

# Direct access
podman exec -it starexec-postgres psql -U starexec
```

### Creating Migrations

1. Create a new file in `sql/`:
   ```bash
   touch sql/V{next_version}__Description_of_change.sql
   ```

2. Add SQL statements:
   ```sql
   -- V15__Add_job_priority.sql
   ALTER TABLE jobs ADD COLUMN priority INTEGER DEFAULT 0;
   CREATE INDEX idx_jobs_priority ON jobs(priority);
   ```

3. Test migration:
   ```bash
   make stop && make start
   make db-status
   ```

### Migration Best Practices

- **One concern per migration**
- **Never modify existing migrations**
- **Test backwards compatibility**
- **Include rollback comments** (for manual rollback if needed)

### Viewing Migration Status

```bash
make db-status

# Or directly
make db-shell
SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;
```

---

## IDE Setup

### IntelliJ IDEA

1. **Import Project**
   - File → Open → Select `pom.xml`
   - Choose "Open as Project"

2. **Configure JDK**
   - File → Project Structure → Project SDK → 17+

3. **Install Plugins**
   - Lombok (if used)
   - CheckStyle-IDEA
   - Database Navigator

4. **Run Configuration**
   - Add Tomcat Local Server
   - Deploy `starexec:war exploded`
   - Set context path: `/starexec`

### VS Code

1. **Install Extensions**
   - Extension Pack for Java
   - Spring Boot Extension Pack
   - Docker

2. **Configure Java**
   ```json
   // settings.json
   {
     "java.configuration.runtimes": [
       {
         "name": "JavaSE-17",
         "path": "/usr/lib/jvm/java-17-openjdk",
         "default": true
       }
     ]
   }
   ```

3. **Debugging**
   ```json
   // launch.json
   {
     "type": "java",
     "name": "Debug StarExec",
     "request": "attach",
     "hostName": "localhost",
     "port": 5005
   }
   ```

---

## Debugging

### Remote Debugging

```bash
# Start with debug port
export JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
make start

# Connect IDE to localhost:5005
```

### Log-Based Debugging

```bash
# Enable debug logging
export LOGGING_LEVEL_ORG_STAREXEC=DEBUG
make stop && make start

# Tail logs
make logs-app | grep DEBUG
```

### Database Debugging

```bash
# Enable SQL logging
make db-shell
ALTER SYSTEM SET log_statement = 'all';
SELECT pg_reload_conf();

# View PostgreSQL logs
make logs-postgres
```

### Frontend Debugging

1. Open browser Developer Tools (F12)
2. Check Console for JavaScript errors
3. Use Network tab for API calls
4. Use Sources tab for breakpoints

---

## Contribution Workflow

### 1. Fork and Clone

```bash
# Fork on GitHub
# Clone your fork
git clone https://github.com/YOUR_USERNAME/StarExec.git
cd StarExec

# Add upstream remote
git remote add upstream https://github.com/StarExecMiami/StarExec.git
```

### 2. Create Feature Branch

```bash
# Sync with upstream
git fetch upstream
git checkout containerised
git merge upstream/containerised

# Create feature branch
git checkout -b feature/my-feature
```

### 3. Make Changes

```bash
# Make your changes
# Write tests
# Run tests
mvn test

# Build and test locally
make stop && make build && make start
```

### 4. Commit Changes

```bash
# Stage changes
git add .

# Commit with descriptive message
git commit -m "feat: add job priority feature

- Add priority column to jobs table
- Update job submission to accept priority
- Add priority sorting in job queue

Closes #123"
```

### Commit Message Format

```
type(scope): subject

body (optional)

footer (optional)
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation
- `style`: Formatting (no code change)
- `refactor`: Code restructuring
- `test`: Adding tests
- `chore`: Maintenance

### 5. Push and Create PR

```bash
# Push to your fork
git push origin feature/my-feature

# Create Pull Request on GitHub
# Target: containerised branch
```

### 6. Address Review Comments

```bash
# Make requested changes
git add .
git commit -m "fix: address review comments"
git push origin feature/my-feature
```

---

## Testing Guidelines

### Unit Tests

- Test individual components in isolation
- Mock external dependencies
- Aim for 80% code coverage

```java
@Test
public void testJobSubmission_validJob_returnsId() {
    // Arrange
    Job job = new Job();
    job.setName("Test Job");
    
    // Act
    int jobId = jobManager.submit(job);
    
    // Assert
    assertTrue(jobId > 0);
}
```

### Integration Tests

- Test component interactions
- Use test database
- Clean up after tests

### End-to-End Tests

- Test complete user flows
- Use Selenium or similar
- Run in CI pipeline

---

## Makefile Reference

```bash
# Build
make build              # Build container image
make build-fresh        # Build without cache
make build-prod         # Production build

# Deployment
make start              # Start environment
make stop               # Stop environment
make status             # Check status
make logs               # View logs
make reset              # Full reset

# Database
make db-shell           # Database CLI
make db-status          # Migration status
make db-dump            # Create backup

# Volumes
make volumes-create     # Create volumes
make volumes-backup     # Backup volumes
make volumes-restore    # Restore volumes

# Maintenance
make clean-podman       # Clean containers
make clean-cache        # Clear build cache
make lint               # Lint Helm charts
```

See `make help` for complete list.

---

## Troubleshooting Development Issues

### Build Fails

```bash
# Clear Maven cache
rm -rf ~/.m2/repository/org/starexec
mvn clean package -U
```

### Container Won't Start

```bash
# Check logs
podman logs starexec-app

# Reset environment
make reset ENV=dev
```

### Database Issues

```bash
# Reset database
make volumes-delete ENV=dev VOLUMES=postgres
make volumes-create ENV=dev
make start
```

### Port Already in Use

```bash
# Find process
sudo lsof -i :7827

# Use different port
make deploy-podman APP_PORT=8080
```

---

## Resources

### Documentation

- **[Architecture](ARCHITECTURE.md)** - System design
- **[API Guide](API_GUIDE.md)** - REST API reference
- **[Configuration](CONFIGURATION.md)** - Configuration options
- **[Troubleshooting](TROUBLESHOOTING.md)** - Common issues
- **[Test Resources](test-resources.md)** - Maven test profiles and test-data fixtures
- **[Database Standards](DATABASE_STANDARDS.md)** - Schema conventions and FK constraints
- **[Local Backend Isolation](LOCAL_BACKEND_ISOLATION.md)** - CPU pinning for reproducible benchmark results

### External Resources

- [Maven Documentation](https://maven.apache.org/guides/)
- [Podman Documentation](https://docs.podman.io/)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)
- [Java 17 Documentation](https://docs.oracle.com/en/java/javase/17/)

### Getting Help

- **Issues**: [GitHub Issues](https://github.com/StarExecMiami/StarExec/issues)
- **Discussions**: [GitHub Discussions](https://github.com/StarExecMiami/StarExec/discussions)
- **Contributing**: [CONTRIBUTING.md](../CONTRIBUTING.md)

---

## Next Steps

After setting up your development environment:

1. **Explore the codebase** - Start with `src/main/java/org/starexec/app/`
2. **Run the tests** - `mvn test`
3. **Pick a good first issue** - Look for `good first issue` label
4. **Join discussions** - Participate in GitHub discussions
5. **Submit a PR** - Start with documentation or small fixes
