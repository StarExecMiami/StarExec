# Architecture Overview

Complete guide to StarExec's system design, components, and data flow.

## System Overview

StarExec is a web-based platform for running solver benchmarks and competitions. It manages the complete lifecycle of solver execution: upload, configuration, job submission, execution, and result collection.

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              User Interface                                  │
│                     (Web UI / StarExecCommand CLI / API)                    │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           StarExec Application                               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐│
│  │  Servlets   │  │  Services   │  │  Data Layer │  │  Backend Interface  ││
│  │  (Web/API)  │  │  (Business) │  │  (Database) │  │  (Job Execution)    ││
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                   ┌──────────────────┼──────────────────┐
                   ▼                  ▼                  ▼
            ┌───────────┐      ┌───────────┐      ┌───────────┐
            │   Local   │      │  Podman   │      │Kubernetes │
            │  Backend  │      │  Backend  │      │  Backend  │
            └───────────┘      └───────────┘      └───────────┘
                   │                  │                  │
                   ▼                  ▼                  ▼
            ┌───────────┐      ┌───────────┐      ┌───────────┐
            │ Processes │      │Containers │      │   Pods    │
            └───────────┘      └───────────┘      └───────────┘
```

---

## Technology Stack

### Core Technologies

| Layer | Technology | Purpose |
|-------|------------|---------|
| **Runtime** | Java 17+ | Application runtime |
| **Web Framework** | Spring MVC / Servlets | HTTP request handling |
| **Application Server** | Embedded Tomcat | Web container |
| **Database** | PostgreSQL 15+ | Persistent storage |
| **Migrations** | Flyway | Database schema management |
| **Frontend** | JSP, jQuery, DataTables | User interface |
| **Styling** | SCSS (Dart Sass) | Stylesheet preprocessing |
| **Containerization** | Podman / Docker | Container runtime |
| **Orchestration** | Kubernetes + Helm | Container orchestration |
| **Build** | Maven | Java build system |

### Supporting Technologies

| Component | Technology |
|-----------|------------|
| Job wrapper | runsolver |
| Process monitoring | cgroups v2 |
| Network (rootless) | pasta / slirp4netns |
| Template rendering | Helm |
| CI/CD | GitHub Actions |

---

## Component Architecture

### Application Layer

```
src/main/java/org/starexec/
├── app/                    # Application bootstrap
│   └── StarExec.java       # Main application class
├── command/                # CLI (StarExecCommand)
├── constants/              # Application constants
│   ├── DB.java             # Database constants
│   ├── R.java              # Resource paths
│   └── Web.java            # Web constants
├── data/                   # Data access layer
│   ├── Database.java       # Connection management
│   ├── Benchmarks.java     # Benchmark operations
│   ├── Jobs.java           # Job operations
│   ├── Solvers.java        # Solver operations
│   ├── Spaces.java         # Space operations
│   └── Users.java          # User operations
├── backend/                # Job execution backends
│   ├── Backend.java        # Backend interface
│   ├── LocalBackend.java   # Process-based execution
│   ├── PodmanBackend.java  # Container-based execution
│   └── ...                 # Other backends
├── jobs/                   # Job management
│   ├── JobManager.java     # Job lifecycle
│   └── ProcessJob.java     # Job processing
├── servlets/               # HTTP endpoints
│   ├── Upload*.java        # Upload handlers
│   ├── Add*.java           # Creation handlers
│   └── ...                 # Other servlets
├── services/               # REST services
├── util/                   # Utilities
└── exceptions/             # Custom exceptions
```

### Web Layer

```
src/main/webapp/
├── WEB-INF/
│   ├── web.xml             # Servlet configuration
│   └── classes/
│       └── logback.xml     # Logging configuration
├── css/                    # Stylesheets
│   ├── global.scss         # Main stylesheet
│   └── components/         # Component styles
├── js/                     # JavaScript
│   ├── common/             # Shared utilities
│   ├── lib/                # Third-party libraries
│   └── [page].js           # Page-specific scripts
├── images/                 # Static images
└── secure/                 # Protected pages (JSP)
    ├── details/            # Detail views
    ├── edit/               # Edit forms
    └── explore/            # List/browse views
```

### Database Schema

```
┌──────────────────┐       ┌──────────────────┐
│      users       │       │     spaces       │
├──────────────────┤       ├──────────────────┤
│ id               │───┐   │ id               │
│ email            │   │   │ name             │
│ first_name       │   │   │ parent_id        │───┐
│ last_name        │   │   │ created          │   │
│ password_hash    │   │   └──────────────────┘   │
│ role             │   │            │             │
└──────────────────┘   │            │ (self-ref)  │
         │             │            └─────────────┘
         │             │
         │   ┌─────────┴─────────────────────────────┐
         │   │                                       │
         ▼   ▼                                       ▼
┌──────────────────┐       ┌──────────────────┐  ┌──────────────────┐
│     solvers      │       │   benchmarks     │  │      jobs        │
├──────────────────┤       ├──────────────────┤  ├──────────────────┤
│ id               │       │ id               │  │ id               │
│ name             │       │ name             │  │ name             │
│ user_id          │       │ space_id         │  │ user_id          │
│ space_id         │       │ user_id          │  │ primary_space    │
│ upload_path      │       │ upload_path      │  │ status_code      │
│ created          │       │ created          │  │ created          │
└──────────────────┘       └──────────────────┘  │ completed        │
         │                          │            └──────────────────┘
         │                          │                     │
         ▼                          │                     │
┌──────────────────┐                │                     │
│ configurations   │                │                     │
├──────────────────┤                │                     │
│ id               │                │                     │
│ solver_id        │                │                     │
│ name             │                │                     │
│ description      │                │                     │
└──────────────────┘                │                     │
         │                          │                     │
         └──────────────┬───────────┘                     │
                        │                                 │
                        ▼                                 ▼
                ┌──────────────────────────────────────────┐
                │              job_pairs                    │
                ├──────────────────────────────────────────┤
                │ id                                       │
                │ job_id (FK → jobs)                       │
                │ config_id (FK → configurations)          │
                │ bench_id (FK → benchmarks)               │
                │ status_code                              │
                │ wallclock_time                           │
                │ cpu_time                                 │
                │ result                                   │
                └──────────────────────────────────────────┘
```

---

## Data Flow

### Job Submission Flow

```
┌─────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  User   │───▶│ Web/API     │───▶│ JobManager  │───▶│  Database   │
│         │    │ Servlet     │    │             │    │  (persist)  │
└─────────┘    └─────────────┘    └─────────────┘    └─────────────┘
                                         │
                                         ▼
                                  ┌─────────────┐
                                  │   Backend   │
                                  │  Interface  │
                                  └─────────────┘
                                         │
                    ┌────────────────────┼────────────────────┐
                    ▼                    ▼                    ▼
              ┌───────────┐       ┌───────────┐       ┌───────────┐
              │   Local   │       │  Podman   │       │Kubernetes │
              │  Backend  │       │  Backend  │       │  Backend  │
              └───────────┘       └───────────┘       └───────────┘
```

### Job Execution Flow (Podman Backend)

```
1. Job Submission
   ┌─────────────────────────────────────────────────────────────┐
   │ JobManager.submitJobToBackend(jobPair)                      │
   │   → PodmanBackend.submitScript(pairId, script, resources)   │
   └─────────────────────────────────────────────────────────────┘
                              │
                              ▼
2. Container Creation
   ┌─────────────────────────────────────────────────────────────┐
   │ PodmanBackend creates container:                            │
   │   - Mount solver at /starexec/solver                        │
   │   - Mount benchmark at /starexec/benchmark                  │
   │   - Mount output at /starexec/output                        │
   │   - Set CONTAINER_MODE=true                                 │
   │   - Apply resource limits (memory, CPU, wallclock)          │
   └─────────────────────────────────────────────────────────────┘
                              │
                              ▼
3. Job Execution (in container)
   ┌─────────────────────────────────────────────────────────────┐
   │ jobscript.sh (functions.bash):                              │
   │   - Run solver with runsolver wrapper                       │
   │   - Write results to /starexec/output/                      │
   │     • status.json (job status)                              │
   │     • stats.json (runtime statistics)                       │
   │     • attributes.txt (solver output attributes)             │
   │     • var.out, watcher.out (runsolver output)               │
   └─────────────────────────────────────────────────────────────┘
                              │
                              ▼
4. Result Collection
   ┌─────────────────────────────────────────────────────────────┐
   │ ContainerJobMonitor (polling every 5s):                     │
   │   - Check for completed containers                          │
   │   - Read output files from mounted volume                   │
   │   - Parse stats.json, status.json, attributes.txt           │
   │   - Update database via JDBC                                │
   │   - Remove processed container                              │
   └─────────────────────────────────────────────────────────────┘
```

### File Upload Flow

```
┌─────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  User   │───▶│ Upload      │───▶│ Validation  │───▶│  Storage    │
│ Browser │    │ Servlet     │    │ Service     │    │  (files)    │
└─────────┘    └─────────────┘    └─────────────┘    └─────────────┘
                                         │
                                         ▼
                                  ┌─────────────┐
                                  │  Database   │
                                  │  (metadata) │
                                  └─────────────┘
```

---

## Backend Architecture

### Backend Interface

All backends implement a common interface:

```java
public interface Backend {
    // Submit a job for execution
    void submitScript(int pairId, String script, ResourceLimits limits);
    
    // Cancel a running job
    void killPair(int pairId);
    
    // Get job status
    JobStatus getStatus(int pairId);
    
    // Get backend statistics
    Map<String, Object> getStats();
    
    // Lifecycle methods
    void initialize();
    void shutdown();
}
```

### LocalBackend Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      LocalBackend                           │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐   │
│   │            ThreadPoolExecutor                        │   │
│   │   ┌─────────────────────────────────────────────┐   │   │
│   │   │ Core pool: min(4, CPU_cores)                │   │   │
│   │   │ Max pool: configurable                      │   │   │
│   │   │ Queue: bounded (10,000)                     │   │   │
│   │   │ Policy: CallerRunsPolicy (backpressure)     │   │   │
│   │   └─────────────────────────────────────────────┘   │   │
│   └─────────────────────────────────────────────────────┘   │
│                              │                              │
│   ┌──────────────────────────┼──────────────────────────┐   │
│   │                          ▼                          │   │
│   │   ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐   │   │
│   │   │ Worker  │ │ Worker  │ │ Worker  │ │ Worker  │   │   │
│   │   │ Thread  │ │ Thread  │ │ Thread  │ │ Thread  │   │   │
│   │   └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘   │   │
│   │        │           │           │           │        │   │
│   └────────┼───────────┼───────────┼───────────┼────────┘   │
│            ▼           ▼           ▼           ▼            │
│       ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐       │
│       │ Process │ │ Process │ │ Process │ │ Process │       │
│       │ (job 1) │ │ (job 2) │ │ (job 3) │ │ (job 4) │       │
│       └─────────┘ └─────────┘ └─────────┘ └─────────┘       │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐   │
│   │              In-Memory Job Tracking                 │   │
│   │   ConcurrentHashMap<pairId, JobInfo>                │   │
│   └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### PodmanBackend Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     PodmanBackend                           │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐   │
│   │                  Job Submitter                       │   │
│   │                                                      │   │
│   │   - Create container with Podman API                │   │
│   │   - Apply resource limits (cgroups)                 │   │
│   │   - Mount volumes (solver, benchmark, output)       │   │
│   │   - Set container labels for tracking               │   │
│   └─────────────────────────────────────────────────────┘   │
│                              │                              │
│                              ▼                              │
│   ┌─────────────────────────────────────────────────────┐   │
│   │               Container Fleet                        │   │
│   │   ┌─────────┐ ┌─────────┐ ┌─────────┐              │   │
│   │   │Container│ │Container│ │Container│  ...          │   │
│   │   │ Job 1   │ │ Job 2   │ │ Job 3   │              │   │
│   │   └─────────┘ └─────────┘ └─────────┘              │   │
│   └─────────────────────────────────────────────────────┘   │
│                              │                              │
│   ┌─────────────────────────────────────────────────────┐   │
│   │            ContainerJobMonitor (Thread)              │   │
│   │                                                      │   │
│   │   - Poll every POLL_INTERVAL_MS (default: 5s)       │   │
│   │   - List completed containers                       │   │
│   │   - Read output files from volumes                  │   │
│   │   - Update database                                 │   │
│   │   - Clean up processed containers                   │   │
│   └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## Deployment Architecture

### Single-Node (Podman)

```
┌─────────────────────────────────────────────────────────────┐
│                         Host System                         │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐   │
│   │                Podman Pod (starexec-pod)            │   │
│   │                                                      │   │
│   │   ┌─────────────────┐   ┌─────────────────┐        │   │
│   │   │  starexec-app   │   │starexec-postgres│        │   │
│   │   │  (Java/Tomcat)  │   │ (PostgreSQL 15) │        │   │
│   │   │                 │   │                 │        │   │
│   │   │  Port: 7827     │   │  Port: 5432     │        │   │
│   │   └────────┬────────┘   └────────┬────────┘        │   │
│   │            │                     │                  │   │
│   └────────────┼─────────────────────┼──────────────────┘   │
│                │                     │                      │
│   ┌────────────┼─────────────────────┼──────────────────┐   │
│   │            ▼                     ▼                  │   │
│   │   ┌─────────────────┐   ┌─────────────────┐        │   │
│   │   │ starexec-data   │   │starexec-postgres│        │   │
│   │   │ (volume)        │   │ (volume)        │        │   │
│   │   └─────────────────┘   └─────────────────┘        │   │
│   │                    Podman Volumes                   │   │
│   └─────────────────────────────────────────────────────┘   │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐   │
│   │                 Job Containers                       │   │
│   │   ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐   │   │
│   │   │ job-001 │ │ job-002 │ │ job-003 │ │   ...   │   │   │
│   │   └─────────┘ └─────────┘ └─────────┘ └─────────┘   │   │
│   └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Kubernetes Cluster

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Kubernetes Cluster                              │
│                                                                         │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │                        Namespace: starexec                       │   │
│   │                                                                  │   │
│   │   ┌─────────────────────────────────────────────────────────┐   │   │
│   │   │              Deployment: starexec                        │   │   │
│   │   │   ┌─────────────┐  ┌─────────────┐                      │   │   │
│   │   │   │   Pod       │  │   Pod       │  (replicas)          │   │   │
│   │   │   │ starexec-0  │  │ starexec-1  │                      │   │   │
│   │   │   └─────────────┘  └─────────────┘                      │   │   │
│   │   └─────────────────────────────────────────────────────────┘   │   │
│   │                              │                                   │   │
│   │   ┌─────────────────────────┼───────────────────────────────┐   │   │
│   │   │                         ▼                               │   │   │
│   │   │              StatefulSet: postgres                      │   │   │
│   │   │   ┌─────────────────────────────────────────────────┐   │   │   │
│   │   │   │                 postgres-0                       │   │   │   │
│   │   │   │   ┌─────────────────┐   ┌─────────────────┐     │   │   │   │
│   │   │   │   │   PostgreSQL    │   │      PVC        │     │   │   │   │
│   │   │   │   └─────────────────┘   └─────────────────┘     │   │   │   │
│   │   │   └─────────────────────────────────────────────────┘   │   │   │
│   │   └─────────────────────────────────────────────────────────┘   │   │
│   │                                                                  │   │
│   │   ┌─────────────────────────────────────────────────────────┐   │   │
│   │   │                    Job Pods                              │   │   │
│   │   │   ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐          │   │   │
│   │   │   │ job-01 │ │ job-02 │ │ job-03 │ │  ...   │          │   │   │
│   │   │   │ Node 1 │ │ Node 2 │ │ Node 3 │ │        │          │   │   │
│   │   │   └────────┘ └────────┘ └────────┘ └────────┘          │   │   │
│   │   └─────────────────────────────────────────────────────────┘   │   │
│   │                                                                  │   │
│   │   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐       │   │
│   │   │   Service    │   │   Ingress    │   │   Secrets    │       │   │
│   │   │  (ClusterIP) │   │  (external)  │   │  (db creds)  │       │   │
│   │   └──────────────┘   └──────────────┘   └──────────────┘       │   │
│   │                                                                  │   │
│   └──────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │                    Storage (PersistentVolumes)                   │   │
│   │   ┌─────────────────┐   ┌─────────────────┐                     │   │
│   │   │  starexec-data  │   │ starexec-postgres│                    │   │
│   │   │      PVC        │   │       PVC        │                    │   │
│   │   └─────────────────┘   └─────────────────┘                     │   │
│   └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Security Architecture

### Authentication Flow

```
┌─────────┐    ┌─────────────────┐    ┌─────────────────┐
│  User   │───▶│  Login Servlet  │───▶│ SecurityFilter  │
│         │    │                 │    │                 │
└─────────┘    └─────────────────┘    └─────────────────┘
                       │                       │
                       ▼                       ▼
               ┌─────────────────┐    ┌─────────────────┐
               │  Password Hash  │    │ Session Manager │
               │  Verification   │    │ (JSESSIONID)    │
               └─────────────────┘    └─────────────────┘
                       │
                       ▼
               ┌─────────────────┐
               │    Database     │
               │ (users table)   │
               └─────────────────┘
```

### Container Isolation (Podman)

```
┌─────────────────────────────────────────────────────────────┐
│                    Host System                              │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐   │
│   │              Rootless Podman (user namespace)       │   │
│   │                                                      │   │
│   │   ┌───────────────────────────────────────────────┐  │   │
│   │   │              Job Container                     │  │   │
│   │   │                                                │  │   │
│   │   │   - Isolated user namespace                   │  │   │
│   │   │   - Isolated network namespace                │  │   │
│   │   │   - Isolated PID namespace                    │  │   │
│   │   │   - cgroups v2 resource limits                │  │   │
│   │   │   - No host filesystem access                 │  │   │
│   │   │   - No privileged capabilities                │  │   │
│   │   │                                                │  │   │
│   │   │   Mounted volumes (read-only where possible): │  │   │
│   │   │   - /starexec/solver (RO)                     │  │   │
│   │   │   - /starexec/benchmark (RO)                  │  │   │
│   │   │   - /starexec/output (RW)                     │  │   │
│   │   │                                                │  │   │
│   │   └───────────────────────────────────────────────┘  │   │
│   │                                                      │   │
│   └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## Configuration Architecture

### Configuration Layers

```
┌─────────────────────────────────────────────────────────────┐
│  Priority 1: Environment Variables (Runtime)                │
│  STAREXEC_DB_PASSWORD, STAREXEC_BACKEND_TYPE, etc.         │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Priority 2: Kubernetes Secrets (Mounted)                   │
│  /run/secrets/db-password, etc.                            │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Priority 3: Helm Values (values-ENV.yaml)                  │
│  values-local-dev.yaml, values-dev.yaml, values-prod.yaml  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Priority 4: Default Values (values.yaml)                   │
│  Base Helm chart defaults                                   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Priority 5: Java Defaults (EnvironmentConfig.java)         │
│  Hardcoded fallback values                                  │
└─────────────────────────────────────────────────────────────┘
```

---

## Performance Considerations

### Bottlenecks

| Component | Bottleneck | Mitigation |
|-----------|------------|------------|
| Database | Connection pool | Configure pool size |
| Job submission | Sequential processing | Batch submissions |
| Result collection | Polling interval | Tune poll frequency |
| File I/O | Disk throughput | Use SSD storage |
| Container startup | Image pull | Pre-cache images |

### Scaling Limits

| Deployment | Max Jobs/Hour | Limiting Factor |
|------------|---------------|-----------------|
| Local (4 cores) | 24 | CPU cores |
| Local (16 cores) | 96 | CPU cores |
| Podman (single node) | 600 | Memory, I/O |
| Kubernetes (10 nodes) | 6,000+ | Node count |

---

## Integration Points

### External APIs

| Endpoint | Purpose |
|----------|---------|
| `/starexec/services/session/logged-in` | Check login status |
| `/starexec/services/session/logout` | Logout |
| `/starexec/secure/add/job` | Submit job |
| `/starexec/secure/upload/jobXML` | Upload job XML |

See [API Guide](API_GUIDE.md) for complete API documentation.

### Database Connections

- JDBC connection pool to PostgreSQL
- Flyway for schema migrations
- Connection string: `jdbc:postgresql://${host}:${port}/${database}`

### File System

| Path | Purpose |
|------|---------|
| `/starexec/data/solvers/` | Uploaded solvers |
| `/starexec/data/benchmarks/` | Uploaded benchmarks |
| `/starexec/data/outputs/` | Job outputs |
| `/starexec/data/uploads/` | Pending uploads |

---

## Related Documentation

- **[Backend Configuration](BACKENDS.md)** - Backend details
- **[Configuration Reference](CONFIGURATION.md)** - Configuration options
- **[Performance Tuning](PERFORMANCE.md)** - Optimization
- **[Deployment Guide](DEPLOYMENT.md)** - Deployment procedures
