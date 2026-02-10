# StarExec Docker Image Analysis

## Executive Summary

The StarExec Docker image is **336 MB** in compressed size, which is reasonable for a Java/Tomcat-based application server. This analysis investigates the composition, identifies optimization opportunities, and provides recommendations for reducing the image size if needed.

**Current Image Details:**
- **Repository:** `ghcr.io/starexecmiami/starexec`
- **Tag:** `latest`
- **Uncompressed Size:** 336 MB
- **Build Date:** 2025-12-09 (29 hours ago at analysis time)
- **Base Image:** Alpine Linux 3.22.2 (8.32 MB)
- **Java Runtime:** Eclipse Temurin JRE 17

---

## Image Layer Breakdown

### Base Image Layers (~180 MB)
| Layer | Size | Purpose |
|-------|------|---------|
| Alpine Linux 3.22.2 | 8.32 MB | Minimal Linux distribution |
| Java 17 JRE (APK setup) | 33.3 MB | Java package manager setup |
| Java 17 JRE (binaries) | 140 MB | Java runtime environment |
| **Subtotal** | **181.3 MB** | **56.1% of image** |

### Application Layers (~155 MB)
| Layer | Size | Purpose |
|-------|------|---------|
| Runtime dependencies | 18.8 MB | bash, curl, postgresql-client, sudo, etc. |
| Tomcat 9.0.82 | 11.4 MB | Application server |
| WAR file (expanded) | 64 MB | StarExec application code + dependencies |
| starexec.war (copied) | 50.6 MB | WAR artifact |
| Configuration/metadata | 4.39 MB | Configuration files from source |
| Default pictures | 63.9 KB | UI assets |
| Runsolver binary | 1.27 MB | Solver execution wrapper |
| Scripts & setup | ~4 MB | Entrypoint, setenv, etc. |
| **Subtotal** | **~154.7 MB** | **43.9% of image** |

---

## WAR File Analysis

The WAR file is one of the largest components. Here's its structure:

**Local Build Artifacts:**
- `starexec.war` (compressed): **64 MB**
- `starexec/` (expanded in container): **81 MB**
- **Expansion overhead:** ~27%

### What's Inside the WAR

The WAR file includes:
- **Java bytecode** (.class files) - compiled source code
- **Third-party dependencies** - all Maven dependencies bundled in `WEB-INF/lib/`
- **Web assets** - CSS (compiled from SCSS), JavaScript, images
- **Configuration files** - web.xml, property files, etc.

**Estimated Breakdown:**
- Java libraries in `WEB-INF/lib/`: ~40 MB
- Compiled classes: ~8 MB
- Web assets (CSS, JS): ~2 MB
- Configuration & metadata: ~2 MB

---

## Build Process Analysis

### Multi-Stage Build Strategy ✅

The Dockerfile uses a **5-stage build approach**, which is excellent for optimization:

1. **Stage 1 (assets):** Node.js Alpine - Compiles SCSS to CSS
2. **Stage 2 (runsolver-builder):** Alpine - Builds runsolver from source
3. **Stage 3 (credential-handler):** Maven Alpine - Builds custom Tomcat credential handler
4. **Stage 4 (builder):** Maven Alpine - Builds the main WAR
5. **Stage 5 (runtime):** Eclipse Temurin JRE Alpine - Final runtime image

**Benefit:** Build tools (Node.js, Maven, build-essential) are discarded, keeping only the final application artifacts.

### Build Configuration

**Build Parameters in Stage 4:**
```dockerfile
ENV MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Xmx3g"
```
- TieredCompilation: Multi-level JIT compilation for faster builds
- Xmx3g: 3GB heap for build process (temporary)

**Maven Build Command:**
```dockerfile
RUN mvn dependency:go-offline -B -pl starexec-app  # Download dependencies for caching
RUN mvn clean install -DskipTests -B -V -pl starexec-app  # Skip tests for faster iteration
```

### .dockerignore Optimization ✅

The `.dockerignore` file properly excludes:
- `target/` - Local build artifacts
- `.git` - Version control
- `node_modules/` - Development dependencies
- `**/starexeccommand.zip` - Large CLI distribution (~736 MB if present)
- `*.log` - Log files
- `.env` files - Secrets
- `web-docs/`, `README.markdown` - Documentation

---

## Optimization Opportunities

### 1. **WAR File Size Optimization** (Potential Savings: 5-15 MB)

**Current State:**
- WAR file is 64 MB (compressed) → 81 MB (expanded)
- Contains all Maven dependencies in `WEB-INF/lib/`

**Recommendations:**

a) **Analyze unnecessary dependencies:**
   ```bash
   # List all JARs in the WAR
   cd starexec-app/target/starexec/WEB-INF/lib
   ls -lhS | head -20
   ```
   - Check for duplicate libraries
   - Remove test dependencies that shouldn't be in the WAR
   - Identify optional libraries that can be excluded

b) **Use Maven Shade Plugin more aggressively:**
   - The Shade Plugin is used for `StarexecCommand.jar` but not the main WAR
   - Consider if JAR minification (using ProGuard or similar) would help
   - This is a trade-off: smaller size vs. more complex build

c) **Enable WAR compression in Tomcat:**
   - Already configured: Tomcat can serve compressed responses
   - Ensure gzip compression is enabled for CSS/JS assets

### 2. **Runtime Configuration Optimization** (Potential Savings: 2-5 MB)

**Java Heap Settings:**
Current: `-Xms512m -Xmx2048m` (512 MB initial, 2 GB max)

**Recommendation:**
```dockerfile
# For light deployments:
ENV JAVA_OPTS="-Djava.security.egd=file:/dev/./urandom -Djava.awt.headless=true -Xms256m -Xmx1024m -XX:+UseG1GC -XX:+UseStringDeduplication"

# For production:
ENV JAVA_OPTS="-Djava.security.egd=file:/dev/./urandom -Djava.awt.headless=true -Xms512m -Xmx2048m -XX:+UseG1GC -XX:+UseStringDeduplication"
```
- Lower initial heap reduces container memory pressure
- Max heap can be set via environment variables at runtime

### 3. **Unused Runtime Dependencies** (Potential Savings: 1-2 MB)

**Current Runtime Packages:**
```
bash, curl, ca-certificates, tzdata, tini, tcsh, unzip, util-linux,
postgresql-client, procps, sudo, libstdc++, libgcc, gcompat
```

**Review:**
- `tcsh`: Only needed if solvers require it - consider making optional
- `postgresql-client`: Only used for migrations - could move to init container
- `unzip`: Used for WAR expansion - already included

**Recommendation:** Keep current set for solver compatibility. These tools are often required by third-party solvers.

### 4. **JAR Expansion Overhead** (Potential Savings: 27 MB)

**Current Process:**
1. Copy `starexec.war` (50.6 MB)
2. Unzip in container to `webapps/starexec/` (81 MB)
3. Both exist in image layers = ~132 MB used

**Option A: Lazy Expansion (Best)**
```dockerfile
# Keep WAR compressed, let Tomcat expand at startup
COPY --from=builder /build/output/starexec.war ${CATALINA_HOME}/webapps/
# Delete the line that unzips it
```
**Savings:** ~27 MB
**Trade-off:** Slower first startup (5-10 seconds), not a major issue for most deployments

**Option B: Cleanup After Expansion (Good)**
```dockerfile
COPY --from=builder /build/output/starexec.war ${CATALINA_HOME}/webapps/starexec.war
RUN cd ${CATALINA_HOME}/webapps && unzip -q starexec.war && rm starexec.war
```
**Savings:** ~50 MB
**Trade-off:** Tomcat can't auto-redeploy without pre-expanding

### 5. **Build Cache Optimization** (No Size Impact, Faster Builds)

**Current:** `RUN mvn dependency:go-offline` downloads all dependencies
**Improvement:** Create a dedicated dependency layer that's cached better

```dockerfile
# More granular dependency caching
COPY pom.xml ./
COPY starexec-app/pom.xml ./starexec-app/
RUN mvn dependency:resolve -B -pl starexec-app

COPY starexec-app/src ./starexec-app/src
# Build only happens if source changes
```

---

## Performance Considerations

### Image Size vs. Runtime Performance

| Aspect | Current | Impact |
|--------|---------|--------|
| Alpine base | 8.32 MB | ✅ Minimal, good |
| Java 17 JRE | 173 MB | ✅ Necessary, modern |
| WAR file | 64 MB | ⚠️ Could optimize to 55-60 MB |
| Expanded WAR | 81 MB | ⚠️ Could eliminate via lazy expansion |
| Runtime packages | 18.8 MB | ✅ Necessary for solvers |

### Container Memory Usage at Runtime

**Expected memory footprint (with Java settings):**
- Base OS: ~30-50 MB
- Java JVM: ~200-300 MB (depends on workload)
- Tomcat + App: ~400-600 MB under load
- **Total typical:** 600-900 MB

**JAVA_OPTS settings allow configuration:**
```bash
# For memory-constrained environments
docker run -e JAVA_OPTS="-Xms256m -Xmx512m" ghcr.io/starexecmiami/starexec
```

---

## Recommendations Prioritized by Impact

### High Priority (Do These)

1. **Add `.dockerignore` comment explaining StarexecCommand exclusion** ✅ (Already Done)
   - Documents the 736 MB savings achieved

2. **Document WAR optimization opportunities**
   - Analyze `WEB-INF/lib/` for unnecessary dependencies
   - Measure actual savings before implementing

### Medium Priority (Consider for Next Release)

3. **Implement lazy WAR expansion**
   - Savings: ~27 MB
   - Effort: Low
   - Trade-off: +5-10s first startup time

4. **Add environment variable for Java heap tuning**
   - Allows runtime optimization
   - Effort: Low
   - No image size impact

### Low Priority (Nice to Have)

5. **Advanced WAR minification**
   - Savings: 5-15 MB
   - Effort: High
   - Complexity increase: Moderate
   - Only worth if size is critical bottleneck

---

## Size Optimization Roadmap

### Current State: 336 MB ✅ (Reasonable)

**Realistic Optimizations:**

| Change | Estimated Savings | Effort | Risk |
|--------|-----------------|--------|------|
| Remove unnecessary dependencies | 5-10 MB | Medium | Low |
| Lazy WAR expansion | 27 MB | Low | Very Low |
| PostgreSQL client (optional) | 1-2 MB | Low | Medium |
| **Potential Total** | **~35 MB** | - | - |
| **Optimized Size** | **~300 MB** | - | **10% reduction** |

### For Future Major Release

- Consider lightweight Java distributions (like GraalVM native-image)
- **Potential savings:** 100+ MB
- **Trade-off:** Significant engineering effort, compatibility testing required

---

## Comparison with Similar Applications

| Application | Base | Size | Notes |
|-------------|------|------|-------|
| **StarExec (current)** | Alpine + Java 17 | 336 MB | ✅ Good |
| Tomcat 9 + JRE | Alpine | ~280 MB | Baseline |
| Jenkins | Alpine + Java | 400-500 MB | More heavy-duty |
| GitLab Runner | Alpine | 800+ MB | More features |

StarExec's image size is **competitive and reasonable** for a Java-based solver evaluation platform.

---

## Build Reproducibility

The Dockerfile includes build metadata to ensure reproducible builds:

```dockerfile
ARG BUILD_DATE=unknown
ARG VCS_REF=unknown
ARG VERSION=unknown
```

These are captured in OCI labels:
- `org.opencontainers.image.created`
- `org.opencontainers.image.revision`
- `org.opencontainers.image.version`

**Benefit:** Can track which commit produced which image.

---

## Health Check

The image includes a health check:

```dockerfile
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/starexec/ || exit 1
```

**Benefits:**
- Container orchestration can detect failed instances
- Automatic restart on health check failure
- 60-second grace period for startup

---

## Security Considerations

The Dockerfile implements several security best practices:

1. ✅ **Non-root user:** Runs as `starexec:starexec` (uid 1000)
2. ✅ **Alpine base:** Smaller attack surface
3. ✅ **Minimal packages:** No SSH, telnet, etc.
4. ✅ **Tini init process:** Proper signal handling, zombie reaping
5. ✅ **Sudo hardening:** Only specific commands allowed without password
6. ✅ **CA certificates:** Included for HTTPS/TLS

---

## Conclusion

The StarExec Docker image at **336 MB is well-optimized for its use case.** The multi-stage build approach is excellent, and the choice of Alpine Linux + OpenJDK provides a good balance of functionality and size.

### Key Strengths
- ✅ Multi-stage build (no build tools in final image)
- ✅ Alpine base (minimal OS overhead)
- ✅ Modern Java 17 JRE
- ✅ Proper .dockerignore configuration
- ✅ Security hardening implemented
- ✅ Health checks configured
- ✅ Build reproducibility

### Realistic Optimization Path
If size reduction is a requirement, focus on:
1. Analyzing WAR dependencies (5-10 MB potential savings)
2. Lazy WAR expansion (27 MB savings)
3. Document and guide configuration for different environments

These changes could realistically reduce the image to **~300 MB** without significant complexity increase.

**Recommendation:** The current size is acceptable. Only pursue aggressive optimization if storage/bandwidth constraints make it necessary.