# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.6.0] - 2026-08-20

### Added
- **Kubernetes health endpoints**: Added `/starexec/public/health/liveness` and `/starexec/public/health/readiness` so orchestrators can distinguish a live process from one able to serve, and pointed the chart's probes at them.
- **SMT-aware CPU partitioning**: Added sibling-aware CPU partitioning that keeps SMT siblings within a single partition and rejects explicit partition layouts that split them.
- **Kubernetes execution observability**: Added pod-state observation alongside job state, so a pair whose pod has never started is no longer reported as running, and added the pod read permission the backend needs for it.
- **Recurring Kubernetes reconciliation**: Added a periodic sweep that settles ambiguous submissions, deletes orphaned jobs, revisits unverified executions, and inventories pods without jobs, configurable through `STAREXEC_K8S_ORPHAN_SWEEP_INTERVAL_MS` (`kubernetes.orphanSweepIntervalMs`, default `300000`).
- **Operational audits**: Added read-only SQL audits for disk quota, space hierarchy, and limit misclassification, with a two-phase disk quota repair.

### Changed
- **Kubernetes execution safety**: Execution is now treated as stopped only when both the controller and its pods are confirmed stopped. Unknown or unobservable cluster state fails closed, so StarExec defers replacement work and retains the accounting rather than risk two executions writing the same measurement.
- **Admission and dispatch**: A managed pod that cannot be accounted for defers new admission for its queue until it is resolved, instead of allowing unrelated pairs to be scheduled beside it.
- **Rerun semantics**: Manual reruns now require the previous execution to be confirmed stopped and report how many pairs were actually reset. Automatic reruns reset the pair and consume the automatic-rerun allowance in one atomic step, and execution identity is revalidated under the database row lock so a reset cannot land on a newer execution.
- **Measurement fidelity**: Timeout and memory-limit classification now uses runsolver's own `TIMEOUT=` and `MEMOUT=` verdicts rather than substring-matching its English output, all three runsolver output sources are read, and a run whose measurements cannot be read is no longer recorded as zero.
- **Job status accuracy**: Pairs are recorded against the node they actually ran on, queues with no nodes report why dispatch stopped, and a pod that never starts fails its pair instead of waiting indefinitely.

### Fixed
- **Disk accounting**: Recording runsolver statistics is now idempotent. Repeated calls previously added the reported figure to user and job totals each time while overwriting the stage row, leaving a surplus that no refund could reclaim.
- **Space hierarchy**: Moving a space into itself or into one of its own descendants is now rejected, preventing a space from becoming its own ancestor.
- **Benchmark uploads**: The asynchronous upload path now satisfies the same invariants as the synchronous one, creating the space association and charging disk usage. Uploaded benchmarks were previously invisible in their space, and deleting them could drive recorded disk usage negative.
- **Result recording**: A write refused because another writer already recorded a result is now distinguished from a write that failed, so a finished pair releases its container and an unfinished one stays discoverable.
- **Job pair locking**: Normalized job pair lock ordering and repaired stored procedures that returned only the first matching row.

### Security
- **Servlet authorization**: Corrected authorization checks that reported a failure without returning, so the privileged work ran anyway. The most serious allowed any authenticated user to rewrite the queue-to-community access list.
- **StarDev transfer**: A failed StarDev login now stops the transfer instead of continuing on a connection that never authenticated.
- **Build provenance**: The runsolver binary is now compiled from the source vendored in this repository. It was previously downloaded from an external host at image build time with no checksum or pinned digest, so the instrument every recorded measurement comes from is now reproducible from the tree it was audited against.

## [2.5.1] - 2026-08-03

> **Never published.** This version was prepared and versioned in source and an
> annotated tag existed only in a local clone; it was never pushed to `origin` and no
> GitHub Release was ever created for it. Its changes ship publicly for the first time
> as part of [2.6.0].

### Fixed
- **Description validation**: Primitive descriptions now consistently accept `+` and `-` across server, client, XML schema, and import paths, including URL-like scientific metadata.
- **Description editing**: Job descriptions are submitted as form data so values containing `/` round-trip correctly.

### Security
- **Description hardening**: Added pre-write import validation, contextual output encoding, bounded no-follow filesystem reads, and raw-description log redaction while retaining the legacy safety blacklist.

## [2.5.0] - 2026-07-29

> **Never published.** This version was prepared and versioned in source and an
> annotated tag existed only in a local clone; it was never pushed to `origin` and no
> GitHub Release was ever created for it. Its changes ship publicly for the first time
> as part of [2.6.0].

### Added
- **Upload extraction controls**: Added `STAREXEC_UPLOAD_EXTRACTION_TIMEOUT_SECONDS` and `STAREXEC_UPLOAD_EXTRACTION_MAX_UNCOMPRESSED_BYTES` for resumable benchmark upload extraction tuning and safety enforcement.
- **Upload artifact cleanup**: Added DB-driven cleanup metadata and a background cleanup worker for expired upload source archives, with retry expiry and path-safety validation.
- **Container CPU partitioning**: Added generic CPU partition scheduling with a shared queue across partition capacities.

### Changed
- **Long-running commands**: Extended command execution timeouts for operations that legitimately require additional processing time.

### Fixed
- **Resumable benchmark uploads**
  - `.tar`, `.tar.gz`, and `.tgz` uploads are now extracted in-process instead of relying on an external `tar` command with the global shell timeout.
  - Async upload extraction now uses temporary `.extracting` directories before promoting the final extracted tree, reducing cleanup and crash-recovery hazards.
  - Upload-session chunk directories are cleaned up after successful finalization, and partial `.assembling` files are removed on failed assembly.
  - Extraction cleanup now targets only unfinished temporary extraction directories, avoiding accidental removal of finalized benchmark files referenced by the database.
  - Async upload extraction now enforces configured extracted-size limits together with the uploader's remaining disk quota.
  - Timestamped upload-session paths are accepted by strict artifact validation, preventing valid uploads from failing before the first chunk or extraction.
  - Terminal upload errors now close the progress dialog before displaying an error, preventing the interface from remaining blocked behind a spinner.
  - Extraction progress and root causes are exposed for failed archives, and processor execution is skipped for `no_type` benchmarks.
- **Backend lifecycle**: Kubernetes jobs now transition pairs to running when work starts and detect already-active jobs during reconciliation; Podman preflight starts the rootless socket when needed.
- **Result classification**: Corrected solved, wrong, and unknown predicates and aligned their UI tooltips with canonical result semantics.
- **User interface**: Corrected misleading success responses and fixed clipping and close-button alignment in dialogs (#85).
- **Streaming**: Prevented concurrent timer cascades after broken SSE client connections.

### Security
- **Dependency hardening**: Remediated container and application dependency vulnerabilities, removed the shaded HTTP core transport, and strengthened CI vulnerability gates.

## [2.4.0] - 2026-05-06

### Added

#### Kubernetes Native Backend
- **Native backend implementation**: Added `KubernetesNativeBackend` class that creates Kubernetes Job resources directly through the fabric8 Kubernetes client (replaces legacy `kubectl` subprocess approach)
  - Direct Kubernetes API integration via `io.fabric8:kubernetes-client` v6.10.0
  - `KubernetesJobMonitor` with polling-based completion monitoring
  - Native Job resource creation with resource limits and node selectors
- **Configuration**: `STAREXEC_K8S_*` environment variables for native backend tuning
  - `STAREXEC_K8S_MAX_CONCURRENT_JOBS` (default `50`) caps in-flight Kubernetes jobs
  - `STAREXEC_K8S_ORPHAN_SWEEP_INTERVAL_MS` (default `300000`) controls orphaned-Job cleanup interval
- **Helm chart**: Enhanced `values-kubernetes.yaml` with native backend configuration, existing PVC claim support, worker selector, and K8s lifecycle tuning profiles
- **Startup reconciliation**: `KubernetesNativeBackend` reconciles orphaned ENQUEUED/RUNNING pairs at startup by rebuilding tracking state from live Kubernetes Jobs, and performs terminal-job drain during graceful shutdown
- **Orphan pair recovery**: Label-based identity tracking for post-crash pair reconciliation across both Podman and Kubernetes backends

#### Live Pair-Log Streaming
- **SSE streaming endpoint**: Added async Server-Sent Events (SSE) streaming at `/services/jobs/pairs/{id}/log/stream` for real-time pair log delivery
- **Non-blocking architecture**: Replaced the previous blocking pair-log stream with an asynchronous SSE implementation, improving server concurrency under load

#### Resumable Upload System
- **Upload session lifecycle**: New domain model and queue controls for resumable upload sessions
  - `POST /services/uploads/sessions`
  - `GET /services/uploads/sessions/{sessionId}`
  - `PUT /services/uploads/sessions/{sessionId}/chunks/{chunkIndex}`
  - `POST /services/uploads/sessions/{sessionId}/finalize`
  - `POST /services/uploads/sessions/{sessionId}/abort`
- **Progress UI**: Client-side upload progress tracking with pause/resume/cancel controls
- **Background recovery**: Stale processing jobs are recovered on application startup
- **Database migrations**: Added `V0107__resumable_upload_sessions.sql`, `V0108__upload_session_chunks.sql`, `V0109__upload_job_progress_monotonic_totals.sql`

#### REST API Endpoints
- **Benchmark metadata**: `GET /services/benchmarks/{id}/contents` remains the plain-text contents endpoint; added dedicated `GET /services/benchmarks/{id}/metadata` endpoint for the JSON metadata payload, including contents
- **Filtered pair queries**: `GET /services/jobs/pairs/filtered-by-solver` returns job pairs filtered by solver ID
- **Pre/post-processor lookup**: `GET /services/processors` returns available preprocessors and postprocessors
- **Stage metadata**: Pair queries now include stage execution metadata (pre/run/post timings, hostname)
- **Extended job attributes**: `GetSpaceJobsById` now returns additional job attributes for richer UI display
- **User load data**: New endpoint for active queue load representation

#### Database & Migrations
- **Foreign-key cleanup** (`V0110`, `V0111`): Enforced explicit `ON DELETE` actions on all foreign keys and documented intentional polymorphic references
- **Reproducibility manifest** (`V0105`): New table for job pair reproduction manifests
- **Batch rerun improvements** (`V0106`): Attempt-increment logic for idempotent batch reruns
- **Terminal status guard**: Stored procedures now enforce `IsTerminalPairStatus()` checks, rejecting terminal-to-non-terminal status downgrades
- **Atomic broken-pair handling**: `SetBrokenPairStatus()` uses compare-and-set semantics and preserves job completion side effects

#### Podman & Container Operations
- **Socket validation**: Automatic Podman socket URI normalization, validation, and UID correction at startup
- **Configurable security**: `runAsUser`/`runAsGroup` support for Podman deployments; security context for PostgreSQL container
- **Preflight checks**: Cgroup manager configuration check and broken pasta networking detection before container launch
- **Networking**: Podman networking disabled by default for job containers (reduces attack surface)
- **Offline builds**: Support for offline build mode with configurable pull policies

#### Build & Deployment
- **Native runsolver compilation**: Compiles runsolver natively on Alpine Linux during build; automatic MicroK8s detection in Makefile
- **K8s auto-detection**: Rewrote `scripts/k8s-auto-detect.sh` and `node-setup.sh` for single-node hostPath deployments
- **Image building**: Added `build-fresh` target for no-cache builds; user namespace option for Podman builds
- **Deploy status**: `make status` now reports real Podman/K8s pod state; documented suspend recovery procedures
- **Enhanced build metadata**: UI now displays richer build version and environment information

#### CI/CD Pipeline
- **Jenkins pipeline**: Full Jenkinsfile with SCM polling (H/5 \* \* \* \*), production deploy stage with manual approval gate, notification email parameters, and cgroup manager configuration
- **Deployment verification**: Automatic post-deploy smoke test stage confirming application health
- **Pipeline migration**: Migrated from Podman-based to Kubernetes-based deployment in CI

#### Administration & Security
- **Gitleaks secret scanning**: Added `.gitleaks.toml` configuration for automated secret detection in CI
- **Admin email bypass**: Administrators can now bypass email verification for user accounts
- **Solver-wide pair filtering**: UI support for filtering job pairs across all solvers in a job space

### Changed
- **Kubernetes backend routing**: `STAREXEC_BACKEND_TYPE=kubernetes` now routes to `KubernetesNativeBackend` instead of legacy `KubernetesBackend`; the legacy backend emits an explicit deprecation warning
- **Pair status transitions**: `UpdatePairStatus` and `UpdatePairStatusPrecise` now enforce terminal-to-non-terminal downgrade rejection via `IsTerminalPairStatus()` guard
- **Broken-pair handling**: `SetBrokenPairStatus()` rewritten with atomic compare-and-set to prevent race conditions
- **User deletion cleanup**: Process now pre-gathers all related IDs before executing DB cascade cleanup, preventing orphaned rows
- **Space deletion cleanup**: Processor files are now removed after successful database commit (was previously removed before commit, risking ghost files on rollback)
- **Backend pair submission**: Both Kubernetes and Podman backends now use conditional pair claims to prevent stale-snapshot backend launches
- **CI workflows**: Renamed from "Deploy to Kubernetes" to "StarExec K8s Deploy"; merged container-build into container-publish to eliminate duplicate builds
- **Default queue handling**: Kept `DEFAULT_QUEUE_NAME` as `default`; Kubernetes backend now adds it only when no node labels are found and allows deletion when appropriate
- **Architecture decisions**: Pair log streaming migrated from blocking I/O to async SSE (breaking change for internal consumers only)

### Fixed
- **Database integrity**
  - Terminal status guard prevents downgrade of completed/failed pairs to active states
  - Broken-pair handler now uses atomic updates, preventing lost updates under concurrent access
  - Conditional pair update methods prevent stale-snapshot writes in backend pair processing
  - Status code reclassification ensures accurate complete/pending/failed counts in all queries
  - `getPairsByStatus` now returns ALL matching pairs instead of only the first row
  - `sge_id` column type corrected for RUNNING pair tracking
  - Terminal status filter widened to include error codes 8-13, 18, 21, 23-26 in community statistics and pagination queries
  - Upload job progress tracks monotonic totals for accurate progress reporting

- **Podman & Container Operations**
  - Podman networking disabled for job containers (was incorrectly enabled, exposing internal networks)
  - Socket path auto-corrected when UID mismatch detected between container and host
  - Preflight validation hardened: enforces Podman binary availability, socket reachability, and cgroup configuration before job submission
  - Rootless Podman socket group-ID permission fixed
  - Listener startup order enforced to prevent race between database pool init and job monitor
  - Podman deployment recovery and Postgres startup checks stabilized
  - Test-compatible retry paths preserved in Podman backend

- **Job Management**
  - Job pause/resume now correctly preserves pair state across the cycle
  - Processing and paused pairs classified as active (were incorrectly counted as idle)
  - Stuck pause dialog in job UI now recovers gracefully
  - Explorer selection state initialized correctly on page load
  - Completed job result handling stabilized against missing data
  - Job details layout and help text improved
  - Job views guarded against incomplete data rendering

- **Security & Access Control**
  - Processor lookup endpoint now authorizes by job creation access (was incorrectly gated)
  - Unused parameter in API endpoint removed to pass security scanning
  - Jenkins workspace cleanup prevents credential leakage between builds
  - Stale Podman job containers cleaned up to prevent resource exhaustion
  - Chart credentials now require explicit dev configuration (no more implicit defaults)
  - Embedded vs. external Postgres behavior properly separated in Helm charts
  - `starExecCommand` header value corrected to `StarExecCommand` for consistency

- **CI/CD Pipeline**
  - Jenkinsfile syntax: missing closing braces, duplicate stages block, `bexpression` typo all corrected
  - Production deploy stage gated to prevent double deployment; branch-only gating removed from deploy stages
  - Deploy-k8s workflow expression syntax error resolved
  - YAML indentation in integration workflow restored to valid format
  - OWASP Dependency Check workflow now skips error on PRs (non-blocking)
  - Docker image tag in integration tests uses `ci-test-latest` instead of stale references
  - `K8S_NAMESPACE` override prevents accidental production deploys from CI

- **Performance & Resource Management**
  - Thread safety improved in hot-path pair monitoring; polling load reduced via adaptive interval refinements
  - Archive downloads now stream directly to response (was heap-buffering, causing OOM on large archives)
  - DEBUG logging reduced to TRACE in hot-path session and auth code (significant reduction in log volume under load)

- **Build & Deployment**
  - Memory variable in `jobscript.sh` corrected to use proper variable expansion
  - Delete permissions repaired to prevent partial cleanup on error
  - Pause image tag updated to use `registry.k8s.io` mirror (was using deprecated `gcr.io`)
  - Schema location default updated to `public` directory for Helm chart compatibility
  - Migration launcher path corrected in deployment scripts
  - User namespace option added to Podman build commands for rootless builds

- **Secret Scanning & Credentials**
  - Rotated credentials remediated; known false positives suppressed in `.gitleaksignore`
  - Default dev credentials now require explicit configuration (no implicit fallback)

### Security
- Credential scanning (gitleaks) integrated into CI pipeline
- Podman job containers run with networking disabled by default (reduces network attack surface)
- API endpoint authorization hardened for processor lookups
- Unused REST parameters removed after security audit
- Default queue deletion allowed in Kubernetes backend (was incorrectly blocked)

### Deprecated
- **Legacy KubernetesBackend**: Emits deprecation warning; operators should migrate to `KubernetesNativeBackend` or `PodmanBackend`

### Performance
- CI Docker builds now use GitHub Actions cache layer to reduce redundant Maven steps
- Thread safety improvements reduce polling load in hot paths

## [2.3.0] - 2026-03-10

### Breaking Changes
- REST endpoint `POST /services/edit/user/{attr}/{userId}/{val}` removed;
  replaced by `POST /services/edit/user/{userId}` with JSON body
  (EditUserAttributeRequest DTO). External API clients must be updated.
- `Backend.submitScript()` interface gains `int pairId` as first parameter.
  Third-party Backend implementations outside this repository must be updated.
- `Statistics.addQueuePlotPoint()` and `Statistics.makeCommunityGraphs()`
  deleted. Any caller outside this repository will fail to compile.
- `getAdaptivePollMaxInterval()` default changed from 10 000 ms to 120 000 ms.
  Deployments not setting `STAREXEC_POLL_MAX_INTERVAL_MS` will poll 12× slower.
- `checkIfBenchmarkDependenciesExists` return-code convention inverted
  (0 = success, 1 = failure, correcting a longstanding bug). External scripts
  sourcing `functions.bash` using the old convention are broken.

### New Features
- Queue metrics history: new `queue_metrics_history` table, REST endpoint
  `GET /cluster/queues/{id}/metrics/history`, and hourly pruning task.
- Job Matrix View servlet and JSP (`JobMatrixViewController`).
- Email change rate limiting: 5-minute cooldown enforced via DB constraint.
- CPU core pinning for `LocalBackend` via `taskset` and `STAREXEC_LOCAL_CORE_LIST`.
- Graceful SMTP thread pool shutdown via `EmailExecutorContextListener`.
- Benchmark dependency resolution: `ResolveBenchmarkDependenciesBatch` and
  `InsertResolvedDependencies` stored procedures; `AddBenchDependency` is now
  idempotent via `ON CONFLICT ... DO UPDATE`.

### Bug Fixes
- `V0026`: Removed `CONCURRENTLY` and `flyway:executeInTransaction=false`
  from index creation (fixes Flyway advisory lock deadlock on fresh installs).
- `V0103`: `RerunJobPairsBatch` rewritten as pure set-based SQL, eliminating
  `SubtransControlLock` contention.
- `functions.bash`: Added `set -euo pipefail`; fixed `(( N = expr ))` vs
  `N=$(( expr ))` arithmetic under `set -e`.
- `jobscript`: Fixed `trap 'exitJobscript $?' EXIT`, uninitialized array,
  and arithmetic expressions.
- `LocalBackend`: Safety net for missing `status.json` on exit-0 prevents
  pairs stuck in ENQUEUED state.
- JSON monitors: Replaced fragile regex parsing of `status.json` with Gson.
- PostgreSQL type resolution: Added explicit `::TEXT` casts in
  `GetJobAttributesTable*` and `analytics_historical` views.
- `UpdatePairStatusPrecise`: Added missing stored procedure that atomically
  sets pair and stage statuses; its absence caused `PSQLException` on every
  job-pair completion event in container and local backends.

### Security
- OWASP dependency-check `failBuildOnCVSS` raised to 11 (report-only mode).
  Security findings now appear in CI reports without blocking builds.
- NVD database caching added to `security-scan.yml`.

### Internal / Cleanup
- 23 `.backup` test files deleted (~3,500 lines).
- Generated `.css` files de-tracked from git (now build artifacts).
- IDE config files (`.idea/`) removed.

## [2.2.0] - 2026-03-03

### Security
- Fixed critical authorization bug: `GeneralSecurity.canUserSuspendOrReinstateUser()` always returned `false`, making user suspension impossible for all administrators.
- Implemented centralized `CsrfFilter` covering all 27 state-modifying servlets and the public registration endpoint. Previously only the password reset flow was protected.
- Corrected CSRF API client exemption: replaced User-Agent pattern matching (spoofable via `Apache-HttpClient/` wildcard) with verification of the `StarExecCommand` custom header that the CLI sends on every request and that browsers cannot include in cross-site requests without a CORS preflight.
- Removed hardcoded credentials from `R.java` (`ADMIN_USER_PASSWORD`, `PUBLIC_USER_PASSWORD`) and `addUser.jsp` (hardcoded default admin password).
- Deleted publicly accessible debug page `/public/test_hash.jsp` that printed default admin credentials and SHA-512 hash without authentication.
- Added automatic `X-CSRF-Token` header injection to all jQuery AJAX POST requests and hidden `csrfToken` field injection to all HTML form submissions (including multipart) via `master.js`.
- Added CSRF token meta tags (`csrf-token`, `csrf-header`) to the global `head.tag` included on every page.

### Added
- New Flyway migration `V0025`: PL/pgSQL function `RerunJobPairsBatch(int[])` that resets an arbitrary array of job pairs to `PENDING_SUBMIT` in a single database round-trip, replacing the previous N+1 loop (~10 queries per pair). Includes per-item poison-pill isolation via `BEGIN...EXCEPTION WHEN OTHERS THEN...END` so a single corrupt pair cannot abort the entire batch.
- New Flyway migration `V0026`: 13 missing performance indexes on `job_pairs`, `jobs`, `solvers`, `configurations`, `benchmarks`, `logins`, `job_spaces`, and `jobpair_stage_data`. Applied with `CREATE INDEX CONCURRENTLY` to avoid table locks during deployment. Runs outside a transaction (`-- flyway:executeInTransaction=false`) to satisfy PostgreSQL's constraint on concurrent index builds.

### Fixed
- `Statistics.java`: replaced non-thread-safe static `HashMap` with `ConcurrentHashMap` for `queueGraphDataHashMap`, eliminating potential `ConcurrentModificationException` and infinite loop under concurrent Tomcat threads.
- `JobManager.java`: replaced non-thread-safe `HashMap` with `ConcurrentHashMap` for `queueToMonitor`; eliminated TOCTOU race condition in `getLoadRepresentationForQueue` between `containsKey()` and `get()`.
- `JobManager.initMainTemplateIf()` and `ClearCacheManager.initScriptTemplateIf()`: added `return` in `catch (IOException)` block to prevent guaranteed `NullPointerException` when the template file cannot be read.
- `Common.doRollback()`: rollback is now conditional on `!con.getAutoCommit()`, preventing spurious rollback attempts and misleading "Database transaction rollback" log entries after every successful commit.
- `Common.beginTransaction()`: propagates `SQLException` instead of silencing it, ensuring callers detect connection failures rather than proceeding with auto-commit active.
- Resource leaks fixed with `try-with-resources` in `PartWrapper.java` (`FileOutputStream`), `ClearCacheManager.java` (`FileWriter`), and `Connection.java` (two `FileOutputStream` locations).

### Changed
- `generate-render-yaml.sh`: fail-fast with `exit 1` when `STAREXEC_DB_PASSWORD` is unset in non-`dev`/`local` environments. Previously only emitted a `WARNING` to stderr that automated pipelines routinely ignore.
- Deployment scripts (`deploy-podman.sh`, `generate-render-yaml.sh`): dev environments emit an explicit `WARNING` when the default database password is used; non-dev environments abort immediately with an `ERROR`.

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

[Unreleased]: https://github.com/StarExecMiami/StarExec/compare/v2.6.0...HEAD
[2.6.0]: https://github.com/StarExecMiami/StarExec/compare/v2.4.0...v2.6.0
[2.5.1]: https://github.com/StarExecMiami/StarExec/commit/443f935565501ea350488f4ef79ddcdd56cacfa3
[2.5.0]: https://github.com/StarExecMiami/StarExec/commit/467c5558afb770c8022521363f0b8b40b4890437
[2.4.0]: https://github.com/StarExecMiami/StarExec/compare/v2.3.0...v2.4.0
[2.3.0]: https://github.com/StarExecMiami/StarExec/compare/v2.2.0...v2.3.0
[2.2.0]: https://github.com/StarExecMiami/StarExec/compare/v2.1.0...v2.2.0
[2.1.0]: https://github.com/StarExecMiami/StarExec/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/StarExecMiami/StarExec/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/StarExecMiami/StarExec/releases/tag/v1.0.0
