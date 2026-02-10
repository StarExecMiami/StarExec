# Docker Image Size Quick Reference

## Current Image Stats

```
Image: ghcr.io/starexecmiami/starexec:latest
Uncompressed Size: 336 MB
Build Date: 2025-12-09
Base: Alpine Linux 3.22.2 + Eclipse Temurin JRE 17
```

## Layer Composition

### Base Layers (180 MB - 56%)
- Alpine Linux: 8.32 MB
- Java 17 JRE: 173 MB

### Application Layers (155 MB - 44%)
- Tomcat 9.0.82: 11.4 MB
- StarExec WAR (expanded): 64 MB
- Runtime dependencies: 18.8 MB
- Configuration/metadata: 4.39 MB
- Runsolver binary: 1.27 MB
- Other assets & scripts: ~55 MB

## WAR File Breakdown

```
Local Build:
  starexec.war (compressed): 64 MB
  starexec/ (expanded): 81 MB
  Expansion overhead: 27%

Contents:
  - Java libraries (WEB-INF/lib/): ~40 MB
  - Compiled classes: ~8 MB
  - Web assets (CSS, JS): ~2 MB
  - Configuration & metadata: ~2 MB
```

## Quick Size Estimates

| Scenario | Image Size |
|----------|-----------|
| Current (multi-stage) | 336 MB |
| With WAR pre-expanded | 336 MB (same) |
| Without WAR expansion | 309 MB |
| Minimal (stripped deps) | 300-310 MB |
| Native-image (future) | 150-200 MB |

## Optimization Quick Wins

### Easy (Low Risk, Low Effort)
1. **Lazy WAR expansion** → -27 MB
   - Let Tomcat expand at startup
   - Trade-off: +5-10s startup time

### Medium (Medium Risk, Medium Effort)
2. **Analyze WAR dependencies** → -5-10 MB
   - Remove unnecessary libraries
   - Requires testing

3. **PostgreSQL client optional** → -1-2 MB
   - Move to init container or sidecar

### Hard (High Effort, High Risk)
4. **WAR minification** → -5-15 MB
   - Use ProGuard or similar
   - Complex build changes

## Memory Footprint at Runtime

```
Typical Memory Usage:
  - Base OS: 30-50 MB
  - Java JVM: 200-300 MB
  - Tomcat + StarExec App: 400-600 MB
  
Total Typical: 600-900 MB
Can be tuned via JAVA_OPTS environment variable
```

## Environment Variables for Tuning

```bash
# Light deployment (512 MB total available)
docker run -e JAVA_OPTS="-Xms256m -Xmx512m" \
  ghcr.io/starexecmiami/starexec:latest

# Standard deployment (2 GB total available)
docker run -e JAVA_OPTS="-Xms512m -Xmx2048m" \
  ghcr.io/starexecmiami/starexec:latest

# Custom garbage collection
docker run -e JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC" \
  ghcr.io/starexecmiami/starexec:latest
```

## Build Performance

### Current Build Time Estimate
- Assets stage (SCSS compile): ~30-60s
- Runsolver build: ~2-3 min
- Credential handler: ~10-15s
- Maven build (with cache): ~2-3 min
- **Total: ~6-10 minutes** (first build ~10-15 min)

### Build Optimization Tips

1. **Leverage Docker cache:**
   ```bash
   # Build with BuildKit for better layer caching
   DOCKER_BUILDKIT=1 docker build -t starexec:custom .
   ```

2. **Build only what changed:**
   ```bash
   # If only source code changed (not dependencies):
   # The dependency layer will be cached and reused
   ```

3. **Parallel builds:**
   ```bash
   # Use BuildKit's parallelization
   docker buildx build --platform linux/amd64,linux/arm64 .
   ```

## Storage Considerations

### Single Instance
- Image: 336 MB
- Runtime data (PostgreSQL, job data): 50-500 MB
- Total: ~400-800 MB

### Multiple Instances
- First instance: 336 MB + 50-500 MB data
- Each additional instance: ~50-500 MB data (image shared)
- 10 instances: ~5 GB total (336 MB image + 9 × data)

### Registry Storage
- Compressed in registry: ~120-150 MB
- Savings via registry compression: ~56%

## Comparison

### vs. Other Java Apps
- **Minimal Tomcat**: 200 MB (no app)
- **StarExec current**: 336 MB (ready to run)
- **Spring Boot typical**: 250-400 MB
- **Jenkins**: 400-500 MB

### vs. Other Languages
- **Node.js Alpine**: 150-250 MB (smaller base)
- **Python Alpine**: 100-200 MB (smaller base)
- **Go standalone**: 50-100 MB (no VM)
- **Java + Tomcat**: 336 MB (VM overhead)

## What's Included by Default

✅ **Built-in:**
- Tomcat 9.0.82 application server
- PostgreSQL client (for migrations)
- Runsolver (solver execution wrapper)
- Bash, curl, sudo (for job execution)
- All necessary solvers and dependencies

❌ **Not included:**
- PostgreSQL server (use separate container)
- StarexecCommand CLI (distributed separately)
- Build tools (Maven, Node.js, GCC)
- Development tools (git, vim, etc.)

## Health Check Status

```
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3
  CMD curl -f http://localhost:8080/starexec/ || exit 1
```

- Checks every 30 seconds after 60-second startup grace period
- Marks unhealthy after 3 consecutive failures
- Docker/Kubernetes can auto-restart based on health

## Troubleshooting Size Issues

### If image is too large:
1. Check local Docker images: `docker images | grep starexec`
2. Clean up old layers: `docker image prune`
3. Check dangling images: `docker images -a | grep "<none>"`
4. Rebuild with cache bust: `docker build --no-cache .`

### If image is growing over time:
1. Check for layer bloat in Dockerfile
2. Verify .dockerignore is being respected
3. Ensure multi-stage build is working
4. Look for missing `rm` commands in RUN statements

### To inspect image contents:
```bash
# Show layer sizes
docker history ghcr.io/starexecmiami/starexec:latest --human

# Inspect what's in the image
docker run --rm -it ghcr.io/starexecmiami/starexec:latest /bin/sh
  # ls -la /app
  # du -sh /opt/tomcat/webapps/starexec
```

## Future Optimization Path

### Short-term (v1.1)
- [ ] Lazy WAR expansion (-27 MB)
- [ ] Analyze dependency bloat (-5-10 MB)
- [ ] Target: 300 MB

### Long-term (v2.0)
- [ ] GraalVM native-image (-50%)
- [ ] Modular builds (-10-20%)
- [ ] Target: 150-200 MB

## Key Metrics

| Metric | Value | Status |
|--------|-------|--------|
| Image size | 336 MB | ✅ Acceptable |
| Build time | ~6-10 min | ✅ Good |
| Startup time | ~30-60s | ✅ Good |
| Runtime memory | 600-900 MB | ✅ Configurable |
| Base OS size | 8.32 MB | ✅ Excellent |
| Optimization potential | ~35 MB | ⚠️ Low priority |

## References

- Full analysis: See `DOCKER_IMAGE_ANALYSIS.md`
- Dockerfile: `/Dockerfile`
- .dockerignore: `/.dockerignore`
- Docker Compose: `/docker-compose.yml`
