# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.1.0] - 2026-02-27

### Added
- **Asynchronous Upload System**
  - Implemented robust background processing for large benchmark archives.
  - Added real-time progress tracking with percentage and file counts for extraction, validation, and insertion phases.
  - **System Pulse (Heartbeat)**: Added a "last active" indicator in the UI to monitor background worker health.
  - **Automated Stuck Job Detection**: Periodic heartbeat monitoring to flag stalled background tasks.
  - **Improved Error Transparency**: Explicitly captures and surfaces benchmark validation and shell errors (e.g., tcsh globbing) to the UI.
  - New terminal status: `COMPLETED_WITH_ERRORS` to distinguish between perfect and partial successes.
- **Modernized Component Library (Phase 5)**
  - Established a scalable SCSS architecture using modular partials and CSS custom properties (design tokens).
  - Implemented a centralized `_index.scss` manifest for deterministic styling.
  - Standardized component naming conventions (singular partials) across the entire codebase.
- **UI/UX Enhancements**
  - **Adaptive Polling**: Implemented exponential backoff for status checks to reduce server load during long-running tasks.
  - **Refined Trash Component**: Replaced legacy layouts with unified Flexbox centering and modernized styling.
  - **Improved Community View**: Redesigned user community memberships using a responsive badge-based system.
  - **Smart Navigation**: Defaulted the Space Explorer to the Root Space (ID 1) when no ID is provided.

### Changed
- **Archive Extraction Engine**: Updated `ArchiveExtractor` to support progress listeners and improved safety checks.
- **Form Defaults**: Set "Local File" as the default upload method for benchmarks to streamline the most common user workflow.
- **Global Stylings**: Integrated typography, spacing, and color foundation imports into the main stylesheet for application-wide consistency.

### Fixed
- **Critical: Job Pair Deadlock**: Fixed a bug in `UpdatePairStatus` stored procedure where uninitialized variables prevented job completion detection.
- **Shell Compatibility**: Implemented a global container-level fix for `tcsh` benchmark processors failing on `?` characters (metacharacter globbing).
- **Archive Off-by-One**: Fixed an error in `ArchiveExtractor` that caused inaccurate file counts for TAR archives.
- **Resource 404s**: Corrected relative paths for JQuery UI icons in the compiled production CSS.
- **Build Integrity**: Fixed compilation errors in `RESTHelpers` related to DataTables pagination.

## [2.0.0] - 2026-02-12

### BREAKING CHANGES

#### 🏗️ Major Build System Migration (Ant → Maven)
- **Commits**: `c56b08bef`, `79f377b68`
- **Impact**: CRITICAL - Build process and directory structure completely changed.
- **Changes**:
  - Legacy Ant `build.xml` removed.
  - Multi-module Maven architecture introduced (`starexec-app`, `tomcat-credential-handler`).
  - Source code reorganized from legacy root structure to Maven-standard layout.
  - Dependency management moved to Central Repository (replaces local `lib/` jars).

#### 🗄️ Database Transformation (MySQL → PostgreSQL)
- **Commit**: `d9f76afd2`
- **Impact**: CRITICAL - MySQL is no longer supported. Requires full data migration.
- **Changes**:
  - Primary database changed from MySQL 8.4 to PostgreSQL 15+.
  - Complete rewrite of all stored procedures, functions, and views into PL/pgSQL.
  - Implementation of **Flyway** for automated, version-controlled schema migrations (V0001-V0023).
  - Migration from integer-based "deleted" flags to native PostgreSQL BOOLEAN types.

#### 🔒 Security & Identity (SHA-512 → BCrypt)
- **Commits**: `22a79973f`, `71e113180`
- **Impact**: CRITICAL - All existing password hashes invalidated.
- **Changes**:
  - Removed legacy SHA-512 hashing logic.
  - Implemented modern BCrypt authentication.
  - Added CSRF protection to the password reset flow.
  - Forced password reset requirement for all migrated users.

#### ☕ Runtime Modernization (Java 17)
- **Commit**: `15feecf97`
- **Impact**: HIGH - Java 8 and 11 are no longer supported.
- **Changes**:
  - Minimum runtime and build requirements updated to Java 17.
  - Docker base images migrated to Alpine-based Temurin 17 JRE.

### Added

#### 🚀 Container-Native Backends
- **Podman Backend**: Implemented native Podman container execution for compute nodes.
- **Kubernetes Native Backend**: Added support for direct execution on Kubernetes clusters via Pods.
- **DooD (Docker-outside-of-Docker)**: Implementation of container management via socket mounting.
- **Optimized Images**: Reduced job runner image size by 83% (115MB → 19MB).

#### 📈 Advanced Monitoring & Scaling
- **Job Pair Stage Tracking**: New persistence layer for detailed execution stages (pre/run/post timing) and hostname tracking.
- **Concurrent Local Backend**: Refactored LocalBackend to support parallel job execution (previously sequential).
- **Helm Charts**: Official production-ready Kubernetes deployment manifests.
- **Adaptive Poll Interval**: Dynamic scaling of job monitoring frequency based on system load.

### Changed
- **Logging Architecture**: Full migration from legacy internal logging to **SLF4J/Logback**.
- **Encoding Standard**: Enforced **UTF-8** across all configuration files, email templates, and log file I/O to prevent character corruption.
- **Session Handling**: Modernized session user retrieval and permission caching for improved performance.

### Fixed
- **Race Conditions**: Resolved directory creation race conditions during multi-threaded uploads.
- **Cookie Security**: Updated cookie encoding to comply with RFC 6265.
- **Result Integrity**: Implemented comma-escaping in result files to prevent CSV corruption.
- **Volume Export Validation**: Improved health checks for exported volumes in container environments.

## [1.0.0] - 2022-03-22
*Initial baseline for the modernization project.*

### Added
- Standardized time limit variables for processors.
- Added Cluster MachineSpecs and overrides configuration for reproducible builds.
- Initial implementation of the user Trash Bin/Recycle logic.

[Unreleased]: https://github.com/StarExecMiami/StarExec/compare/v2.1.0...HEAD
[2.1.0]: https://github.com/StarExecMiami/StarExec/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/StarExecMiami/StarExec/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/StarExecMiami/StarExec/releases/tag/v1.0.0
