# Docker Image Investigation - Complete Guide

## Overview

This guide provides comprehensive analysis of the StarExec Docker image size, composition, and optimization opportunities. The investigation reveals that the image is **well-optimized at 336 MB** for a Java/Tomcat-based application.

---

## Quick Facts

| Metric | Value | Status |
|--------|-------|--------|
| **Current Size** | 336 MB | ✅ Acceptable |
| **Compressed (Registry)** | ~120-150 MB | ✅ Good |
| **Build Time** | ~6-10 minutes | ✅ Reasonable |
| **Startup Time** | ~30-60 seconds | ✅ Good |
| **Runtime Memory** | 600-900 MB | ✅ Configurable |
| **Base Image** | Alpine + Java 17 | ✅ Excellent |

**Verdict:** The image is well-architected and uses modern Docker best practices.

---

## Documentation Structure

### 📄 Main Analysis Document
**File:** `DOCKER_IMAGE_ANALYSIS.md` (374 lines)

Complete deep-dive analysis covering:
- **Layer Breakdown** - Detailed breakdown of all image layers
- **WAR File Analysis** - What's inside the 64 MB WAR file
- **Build Process Analysis** - Multi-stage build strategy
- **Optimization Opportunities** - 5 different optimization paths
- **Performance Considerations** - Memory usage and tuning
- **Comparison** - How StarExec compares to other Java applications
- **Security Considerations** - Best practices implemented
- **Recommendations** - Prioritized by impact and effort

**When to read:** For complete understanding of the image architecture and all optimization options.

**Key Sections:**
- Optimization Opportunities (page 6-8)
- Recommendations Prioritized by Impact (page 10)
- Size Optimization Roadmap (page 11)

### 📋 Quick Reference Guide
**File:** `docker/IMAGE_SIZE_REFERENCE.md` (237 lines)

Quick lookup reference including:
- **Current Stats** - At-a-glance metrics
- **Layer Composition** - Visual breakdown
- **Quick Size Estimates** - Different scenarios
- **Optimization Quick Wins** - Easy vs. hard optimizations
- **Memory Footprint** - Runtime memory usage
- **Environment Variables** - Tuning parameters
- **Build Performance** - Build time optimization
- **Troubleshooting** - Common issues and solutions

**When to use:** For quick lookups, configuration examples, and troubleshooting.

**Best for:**
- Developers deploying StarExec
- DevOps engineers tuning container resources
- Quick reference during deployment

### 🔧 Automation Script
**File:** `scripts/analyze-war-size.sh` (236 lines, executable)

Automated analysis tool for WAR file inspection:

**Usage:**
```bash
# Basic analysis
./scripts/analyze-war-size.sh

# Keep extracted contents for manual inspection
./scripts/analyze-war-size.sh --extract
```

**Provides:**
- WAR file size breakdown
- Top 20 largest libraries in WEB-INF/lib/
- Compiled code size and top packages
- Static asset analysis (CSS, JS, images)
- Summary statistics with percentages
- Duplicate library detection
- Large JAR warnings (>5MB)
- Optimization suggestions

**When to use:** When investigating actual WAR contents or looking for dependency bloat.

---

## Key Findings Summary

### Size Breakdown (336 MB total)

```
Base OS Layers (180 MB - 56%)
├─ Alpine Linux: 8.32 MB
└─ Java 17 JRE: 173 MB

Application Layers (155 MB - 44%)
├─ Tomcat 9.0.82: 11.4 MB
├─ StarExec WAR (expanded): 64 MB
├─ Runtime dependencies: 18.8 MB
├─ Configuration/metadata: 4.39 MB
├─ Runsolver binary: 1.27 MB
└─ Other (scripts, assets): ~55 MB
```

### Architecture Highlights

✅ **5-Stage Multi-Stage Build**
- Assets compilation (Node.js)
- Runsolver compilation (C/C++)
- Credential handler (Java)
- Main application build (Maven)
- Lightweight runtime

✅ **Best Practices Implemented**
- Non-root user execution
- Health checks configured
- Proper .dockerignore
- Security hardening
- Build reproducibility

### Optimization Opportunities

| Opportunity | Savings | Effort | Risk |
|------------|---------|--------|------|
| Lazy WAR expansion | 27 MB | Low | Very Low |
| Remove unused dependencies | 5-10 MB | Medium | Low |
| PostgreSQL client (optional) | 1-2 MB | Low | Medium |
| WAR minification | 5-15 MB | High | Medium |
| **Total realistic potential** | **~35 MB** | - | **Low** |

**Realistic target:** 336 MB → 300 MB (10% reduction)

---

## Navigation Guide

### For Different Audiences

**👨‍💻 Developers**
1. Start: `DOCKER_IMAGE_ANALYSIS.md` - Understand architecture
2. Reference: `docker/IMAGE_SIZE_REFERENCE.md` - Configuration options
3. Tool: `scripts/analyze-war-size.sh` - Investigate dependencies

**🔧 DevOps Engineers**
1. Start: `docker/IMAGE_SIZE_REFERENCE.md` - Quick facts and tuning
2. Deep dive: `DOCKER_IMAGE_ANALYSIS.md` - Performance section
3. Tool: Use `docker history` and `docker inspect` commands

**📊 Architects/Leads**
1. Start: This file (executive summary)
2. Key section: "Recommendations Prioritized by Impact" in `DOCKER_IMAGE_ANALYSIS.md`
3. Reference: "Comparison with Similar Applications" section

**🚀 Deployment Engineers**
1. Start: `docker/IMAGE_SIZE_REFERENCE.md` - Environment variables
2. Reference: Memory footprint and tuning sections
3. Troubleshooting: Last section of quick reference guide

### By Use Case

**"How do I reduce image size?"**
→ See `DOCKER_IMAGE_ANALYSIS.md` pages 6-11 (Optimization Opportunities + Roadmap)

**"What are my tuning options?"**
→ See `docker/IMAGE_SIZE_REFERENCE.md` (Environment Variables section)

**"Is the image too large?"**
→ See this file's "Key Findings Summary" - Answer: No, it's appropriate for its purpose

**"Where is the space being used?"**
→ Run `scripts/analyze-war-size.sh` to see actual breakdown

**"How fast does it start?"**
→ See `docker/IMAGE_SIZE_REFERENCE.md` (Build Performance section)

**"What's actually in the WAR file?"**
→ Run `scripts/analyze-war-size.sh` to get detailed breakdown

**"How much memory will it use?"**
→ See `docker/IMAGE_SIZE_REFERENCE.md` (Memory Footprint section)

---

## Quick Commands

### Inspect Image Size
```bash
# Show total size
docker images ghcr.io/starexecmiami/starexec

# Show layer sizes
docker history ghcr.io/starexecmiami/starexec:latest --human

# Inspect metadata
docker inspect ghcr.io/starexecmiami/starexec:latest
```

### Analyze WAR File
```bash
# Run the analysis script
./scripts/analyze-war-size.sh

# Extract and keep contents for manual inspection
./scripts/analyze-war-size.sh --extract

# Manual inspection (requires unzip)
unzip -l starexec-app/target/starexec.war | head -50
```

### Tune at Runtime
```bash
# Low memory mode
docker run -e JAVA_OPTS="-Xms256m -Xmx512m" ghcr.io/starexecmiami/starexec

# High performance mode
docker run -e JAVA_OPTS="-Xms1024m -Xmx4096m -XX:+UseG1GC" ghcr.io/starexecmiami/starexec

# Custom configuration
docker run -e JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC" ghcr.io/starexecmiami/starexec
```

---

## Recommendation Summary

### For Current Deployment
✅ **The image is acceptable as-is.** 336 MB is reasonable for a Java/Tomcat application.

### For Size-Conscious Deployments
Consider implementing:
1. **Lazy WAR expansion** (27 MB savings, low risk)
2. **Dependency analysis** (5-10 MB potential savings)

### For Future Major Release
Evaluate:
- GraalVM native-image for 50% size reduction
- But requires significant testing and engineering effort

### For General Operations
- Use environment variables to tune memory for your infrastructure
- Leverage registry compression (already ~56% compression)
- Monitor image growth with each release

---

## Document Maintenance

These documents were created on **2025-12-10** based on:
- Docker image build: 2025-12-09
- WAR file: starexec.war (64 MB)
- Build artifacts: starexec-app/target/

**To update this analysis:**
1. Rebuild the image: `docker build -t starexec:analysis .`
2. Run the analysis script: `./scripts/analyze-war-size.sh`
3. Compare results with previous analysis
4. Update markdown files if significant changes occurred

---

## Related Files

- **Dockerfile** - Image build configuration
- **.dockerignore** - Files excluded from build context
- **docker-compose.yml** - Multi-container setup
- **docker/entrypoint.sh** - Container startup script
- **docker/setenv.sh** - Tomcat environment configuration

---

## Further Reading

### External Resources
- [Docker Best Practices](https://docs.docker.com/develop/develop-images/dockerfile_best-practices/)
- [Alpine Linux Documentation](https://wiki.alpinelinux.org/)
- [Tomcat Configuration](https://tomcat.apache.org/tomcat-9.0-doc/)
- [Java Performance Tuning](https://docs.oracle.com/javase/8/docs/technotes/guides/vm/index.html)

### Related StarExec Documentation
- README.md - Project overview
- CLAUDE.md - Development philosophy
- docker-compose.yml - Container orchestration setup

---

## Contact & Questions

For questions about Docker image optimization, refer to:
1. Check relevant section in DOCKER_IMAGE_ANALYSIS.md
2. Run analysis script for current data: `./scripts/analyze-war-size.sh`
3. Review this index for navigation to specific topics