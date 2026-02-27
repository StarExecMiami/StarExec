# StarExec Release Notes

## v2.1.0 - Reliability & Refinement
**Release Date:** February 27, 2026

StarExec v2.1.0 is a stability and user-experience focused release that builds upon the containerized foundation of v2.0.0. This release addresses critical observability gaps and implements a more robust architecture for large-scale data management.

### Key Highlights

#### 📤 Asynchronous Upload Pipeline
The benchmark upload system has been completely re-engineered to support background processing. 
- **User Experience:** Large uploads no longer require the browser window to remain open.
- **Observability:** A new **System Pulse (Heartbeat)** indicator provides real-time visual confirmation of background worker activity.
- **Transparency:** The "silent failure" anti-pattern has been eliminated. Validation and shell execution errors (such as script globbing issues) are now captured and surfaced directly in the status UI.

#### 🎨 Component Library Modernization (Phase 5)
Following the mobile optimization of Phase 4, the entire UI styling architecture has been refactored:
- **Modular SCSS:** Styles are now organized into singular, reusable partials.
- **Design Tokens:** Application-wide consistency is enforced via a centralized CSS variable system for typography, spacing, and colors.
- **Flexbox Layouts:** Modern CSS layouts ensure perfect alignment and justification for action buttons and utility components.

#### 🐛 Critical Stability Fixes
- **Job Engine:** Resolved a deadlock bug in the database layer that caused job pairs to remain stuck in "Enqueued" status.
- **Shell Compatibility:** Implemented a platform-level compatibility fix for legacy `tcsh` benchmark processors, ensuring they function correctly within the new Alpine-based container environment.
- **Data Accuracy:** Fixed an off-by-one counting error in archive extraction that previously caused discrepancies in file reports.

---

## v2.0.0 - The Cloud-Native Era
**Release Date:** February 12, 2026

StarExec v2.0.0 represents the single largest architectural shift in the project's 15-year history. This release modernizes every layer of the stack to support container-first deployment and modern security standards.

### Major Changes

#### 🏗️ Maven & Modular Architecture
We have officially retired the legacy Ant build system. The project now follows a standard multi-module Maven structure, significantly improving dependency management, IDE integration, and CI/CD reliability.

#### 🗄️ PostgreSQL Migration
To support modern cloud environments, StarExec has migrated from MySQL to PostgreSQL 15. This involved a complete rewrite of the database logic layer, including hundreds of stored procedures and views now implemented in PL/pgSQL.

#### 🔒 Security Overhaul
- **BCrypt Authentication:** Replaced legacy SHA-512 hashing with industry-standard BCrypt.
- **Security Hardening:** Implemented CSRF protection, secure cookie handling (RFC 6265), and non-root container execution contexts.
- **Identity Migration:** All users are now required to perform a secure password reset upon their first login to the new system.

#### 🚀 Containerization & Orchestration
- **Podman & Kubernetes:** Native support for containerized execution on both single-node Podman environments and scalable Kubernetes clusters.
- **Optimized Footprint:** Through multi-stage builds and Alpine Linux, we reduced the job runner image size from 115MB to 19MB, enabling faster scaling and deployment.

#### 📈 Enhanced Monitoring
The introduction of `jobpair_stage_data` allows for granular tracking of every phase of execution (pre-processing, execution, and post-processing), including hostname recording for better auditability in distributed clusters.

---

### v1.0.0 Baseline (2022)
The baseline for the modernization initiative, focusing on stability, UTF-8 enforcement, and the initial introduction of the user trash bin system.

---

**Happy Computing!** 🚀
*The StarExec Team*
