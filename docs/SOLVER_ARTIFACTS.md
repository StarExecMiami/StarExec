# Solver build artifacts

`solver_build_artifacts` records a build's original solver, owner, pair, attempt,
source path and generation. Live foreign keys may become null when an execution
or solver is deleted; historical identities and cleanup paths remain available.

The lifecycle is `PLANNED → STAGED → VALIDATED → PUBLISHED → RETIRED`. Rejected
artifacts retain cleanup records. Only retired or rejected artifacts can be marked
deleted. Reserved paths and validated payload bytes cannot be replaced. The digest
covers the exact UTF-8 manifest text, including whitespace, rather than JSONB output.

`publish_solver_build_artifact` locks the job, pair, current attempt, solver and
artifact in that order. It checks ownership, source, build generation, successful
completion, deletion state and newer build jobs before switching the solver path
and publication state in one database transaction. A retry of the current
publication succeeds without replacing its timestamp.

Rebuilding resolves the original source independently of the published artifact
path and advances the build generation before the replacement job exists. Failed
solvers keep their existing failure status until the replacement result is recorded.

Database metadata cannot make runner-writable files immutable. Before validation,
the application must copy a completed execution's output into storage the runner
cannot modify, validate types, links, byte limits and content, then publish that
distinct artifact. Catalog transactions do not provide filesystem isolation or
automatically protect the existing path-based build helpers.

`SolverArtifactTree` creates an exclusive destination, enforces byte and entry
limits, copies regular files into distinct inodes and preserves only relative
links that resolve inside the tree. It refuses hard links, special files and
unsafe ancestors. It normalizes permissions, forces copied files and directories
to storage, and reads the result back against its exact manifest. A failed copy
cleans only the destination it created.

All input writers must have stopped and both trees' ancestors must be protected
before calling it. File permissions alone cannot isolate another process with the
same UID. The caller must reserve the artifact path durably before copying and
complete ownership, quota and cleanup coordination before publication.

Container-mode build helpers require an execution-specific
`STAREXEC_BUILD_OUTPUT_DIR`; generated scripts encode it with the other paths.
They stage solver files under `solver/` and logs under `logs/`, and leave helper
benchmark inputs for application cleanup. Missing staging is an explicit failure;
database-connected legacy jobs retain their existing copy and cleanup behavior.

The producer, script template and trusted recorder must be upgraded together.
Persistent helper scripts must also be refreshed through the deployment process;
updating a WAR alone does not guarantee the helper volume changed. Drain old
generated build jobs or refuse them before dispatch. Never restore compatibility
by allowing a container build to write directly into a published solver tree.

`prepare_solver_build_storage` binds one execution token and staging directory to
the current pair attempt, source and generation. Engine adapters bind the resource
identity they created and record its observed termination. These observations are
separate from solver-written status files. A duplicate callback keeps the original
timestamp; deletion preserves enough identity to finish tracking an already
prepared execution.

Runtime callers use `publish_solver_build_output`, which requires that execution
binding, a recorded stop and an immutable storage root before the metadata
transaction can publish. The root contains `solver/` and `logs/`; only its solver
directory becomes the solver's execution path. The lower-level metadata function
does not establish stopped writers, validate files or enforce filesystem quotas.

`SolverBuildArtifacts` exposes parameterized JDBC operations for dispatch and
engine adapters. Missing records and mismatched ownership return refusal; database
errors propagate for infrastructure retry. It does not allocate directories or
observe processes, and callers must supply an absolute staging directory.

Use a disposable PostgreSQL database configured through the documented
`STAREXEC_DB_*` variables and apply the migrations before running:

```sh
mvn -B -pl starexec-app -Dtest=SolverArtifactPublicationSqlTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

These database tests cover ownership changes, stale jobs and attempts, rollback,
exact manifest bytes, deletion retention and a forced concurrent build insertion.
They do not establish deployed runner isolation.

The file-copy tests run without a database:

```sh
mvn -B -pl starexec-app -Dtest=SolverArtifactTreeTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```
