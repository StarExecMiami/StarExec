# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Kubernetes health endpoints**: Added `/starexec/public/health/liveness` and `/starexec/public/health/readiness` so orchestrators can distinguish a live process from one able to serve, and pointed the chart's probes at them.
- **SMT-aware CPU partitioning**: Added sibling-aware CPU partitioning that keeps SMT siblings within a single partition and rejects explicit partition layouts that split them.
- **Kubernetes execution observability**: Added pod-state observation alongside job state, so a pair whose pod has never started is no longer reported as running, and added the pod read permission the backend needs for it.
- **Recurring Kubernetes reconciliation**: Added a periodic sweep that settles ambiguous submissions, deletes orphaned jobs, revisits unverified executions, and inventories pods without jobs, configurable through `STAREXEC_K8S_ORPHAN_SWEEP_INTERVAL_MS` (`kubernetes.orphanSweepIntervalMs`, default `300000`).
- **Operational audits**: Added read-only SQL audits for disk quota, space hierarchy, and limit misclassification, with a two-phase disk quota repair.

### Changed
- **The security scan checks the Java dependencies again**: From 2026-10-14 the scan stopped at `npm audit`, which failed on GHSA-vfj7-8cjw-p6xm in `braces`, an advisory with no patched release, and so skipped the OWASP scan of the Java dependencies on every run. The Node audit now runs through a gate with documented, expiring exceptions, and no longer stops the Java scan; the job fails if either does. See docs/SECURITY.md.
- **Kubernetes job concurrency matches the workers**: `values-prod.yaml` caps job pods at 6, the number Quokka's workers can run at once (each pod requests 31 CPUs and 250Gi on 32-CPU workers), instead of 50. Jobs above capacity waited as Pending pods, which the backend cannot tell apart from pods that will never start; deferred pairs now wait in StarExec's queue. The job pods' `requests` block, which no template read, is removed, and the docs say a submission above the cap is deferred, not rejected.
- **Kubernetes execution safety**: Execution is now treated as stopped only when both the controller and its pods are confirmed stopped. Unknown or unobservable cluster state fails closed, so StarExec defers replacement work and retains the accounting rather than risk two executions writing the same measurement.
- **Admission and dispatch**: A managed pod that cannot be accounted for defers new admission for its queue until it is resolved, instead of allowing unrelated pairs to be scheduled beside it.
- **Rerun semantics**: Manual reruns now require the previous execution to be confirmed stopped and report how many pairs were actually reset. Automatic reruns reset the pair and consume the automatic-rerun allowance in one atomic step, and execution identity is revalidated under the database row lock so a reset cannot land on a newer execution.
- **Measurement fidelity**: Timeout and memory-limit classification now uses runsolver's own `TIMEOUT=` and `MEMOUT=` verdicts rather than substring-matching its English output, all three runsolver output sources are read, and a run whose measurements cannot be read is no longer recorded as zero.
- **Job status accuracy**: Pairs are recorded against the node they actually ran on, queues with no nodes report why dispatch stopped, and a pod that never starts no longer holds its pair indefinitely.

### Removed
- **Dead stress-test endpoint**: Removed the `/secure/add/stressTest` mapping and the admin UI that reached it. The mapping pointed at a class in `src/test/java`, so it was never packaged into the WAR and every request returned HTTP 500. The stress-test capability itself is unchanged; it is driven by `TestManager` in the test harness.

### Fixed
- **Jobs run on a Podman deployment made without Helm**: When Helm is not installed, `make start` builds the pod from `render.yaml.template` with the Podman backend, but the template gave StarExec no container engine socket, no job image and no path of the data volume on the host. The backend could not connect, so every pair stayed pending submission while its queue showed ACTIVE. The template now mounts the socket, adds the socket's group to the pod and passes the job image and the data volume's path, as the Helm path does. `scripts/generate-render-yaml.sh` fills them in, creates the data volume on a first deploy so that its path is known, and stops with a message when the socket is missing.
- **The Helm-less Podman pod leaves the app room beyond its heap**: `render.yaml.template` limited the app container to 1Gi of memory, while the image lets the JVM heap grow to 2 GiB (`-Xmx2048m`). `podman kube play` sets no swap limit, so on a host with swap about half of a full heap went to swap: under 1Gi a test program holding 1920 MiB of heap used 1023 MiB of swap, under 3Gi none. On a host without swap the kernel kills the JVM at the limit instead. The app's limit is now 3Gi, the limit #454 gives the chart's profiles (#453), and a test fails when the heap the pod allows exceeds 75% of that limit. `CONFIGURATION.md` said the container is killed when its memory use reaches the limit, and the check in `TROUBLESHOOTING.md` looked only for kills; both now explain the swap case, and the check also shows the container's swap use and how often it reached its limit.
- **Troubleshooting advice that changed nothing**: `TROUBLESHOOTING.md` told operators to `export` a `STAREXEC_*` variable and run `make stop && make start`, but `make start` builds the pod from a values file, so the variable never reached StarExec. It also checked job capacity by grepping `render.yaml` for a Local-backend setting, enabled DEBUG logs that `org.starexec` already writes through `LOGGING_LEVEL_ORG_STAREXEC`, which nothing reads, and answered a database password error by printing the password and exporting a new one, which cannot change the password PostgreSQL stores. A new section says where each deployment reads its settings; the stuck-job diagnosis names the dispatcher's log line for each cause, by pair status and backend; and the password fix sets the stored password with `\password` from `make db-shell`.
- **Copying several items no longer reports an error after the copy**: Copying two or more benchmarks, solvers or spaces, from the explore page or with StarexecCommand, and uploading a job XML that defines several jobs, answered with an error although the copies or jobs had been created: HTTP 500 for benchmarks, "internal database error" for solvers, and "The job could not be created" for job XML, which invited a retry that duplicated the jobs. The reply's New_ID cookie listed the new ids separated by commas; RFC 6265 does not allow a comma in a cookie value, and Tomcat refused the cookie when it was added. The list is now URL-encoded like every other cookie value, and StarexecCommand decodes cookie values, so its error messages also print as plain text instead of URL-encoded.
- **Account page dialogs show their whole text**: The delete-user, delete-website and create-settings-profile dialogs on the account page opened at fixed heights that their content outgrew. The delete-user dialog cut off "Are you sure you want to delete this user?", and the website dialog cut its question in half. They now size to their content.
- **The Kubernetes setup preview is a preview**: KUBERNETES_AUTO_DEPLOYMENT.md previewed `make k8s-setup` with `DRY_RUN=true make k8s-setup`, but `scripts/k8s-node-setup.sh` sets `DRY_RUN=false` itself and the target passes no `--dry-run`, so the command labeled every worker node, created the namespace and, given a storage class, created the data PVC. The page now gives `make k8s-setup-dry-run` and says that `DRY_RUN` does not reach `make k8s-setup`.
- **A recorded limit breach is no longer replaced by ERROR_RUNSCRIPT**: After a pair's result was recorded — a wallclock, CPU, memory or disk-quota breach, or the last stage's completion — the job script went on to clean up under `set -e`. A cleanup command that failed, such as an `rm -rf` racing the solver a breach had just killed, ended the script, and the EXIT trap's fail-closed fallback then overwrote the recorded result with `ERROR_RUNSCRIPT`: a local end-to-end run recorded a wallclock breach as status 11. The result is now marked as recorded once it is written, so only a pair with no recorded result falls back to `ERROR_RUNSCRIPT`.
- **A solver stopped at the Podman memory ceiling is a memory breach, not a completed pair**: The Podman backend caps each pair's container at `STAREXEC_CONTAINER_DEFAULT_MEMORY_MB` (2048 MiB by default), while runsolver enforces the job's own memory limit, which is 16 GiB for a job created without one. A solver that reached the container's limit first was stopped by the kernel's OOM killer; runsolver reported a solver ended by a signal and no limit of its own exceeded, and the pair was recorded as complete, with whatever output the solver had written. The job script now reads the container's count of OOM kills before and after the solver runs, and records a kill as a memory breach (`EXCEED_MEM`) of the stage that was running, with a pair-log line giving the container's limit and the stage's. A limit runsolver enforced itself still takes precedence. Other backends are unchanged: local-backend pairs share the application's cgroup, so a kill there cannot be attributed to one pair.
- **A deadlock watchdog that ends without firing no longer makes the stage a wall-clock timeout**: The job script decided that a stage's deadlock watchdog had fired when it found the watchdog no longer running. A watchdog can end without firing: the kernel can kill it when the pair's container runs out of memory, and a stray signal can stop it. The stage was then recorded as a wall-clock timeout (`EXCEED_RUNTIME`) even when its solver finished in time, and that verdict comes before the memory checks, so a breach of the container's memory limit that also took the watchdog was recorded as a timeout. The watchdog now leaves a record in the pair's workspace before it kills anything, and the check reads that record, which also catches a watchdog still killing when the check runs. A watchdog that ended without firing is logged, and the stage is judged on its own output.
- **StarexecCommand can upload benchmarks again**: Since 2020, `pushbenchmarks` sent the upload's file source as `localOrUrlOrGit`, while the server reads `localOrURLOrGit`, so the server rejected every upload with "Please select a file source (local, URL, or Git)". The client also took only a redirect with a `New_ID` cookie as success, while the asynchronous upload answers HTTP 202 with the upload job's id, and `viewuploadstatus` asked an endpoint that knows nothing of upload jobs. The client now sends the name the server reads, returns the upload job's id, and `viewuploadstatus` reports the job's status, benchmarks processed and error; against an older server both keep working as before.
- **The documented Podman production deploy starts**: `make deploy-podman ENV=prod` rendered the Kubernetes profile `values-prod.yaml`, whose pod reads a database Secret that the Podman deployment never creates, and the cleanup before each deploy removes any Podman secret named after the release, so the documented production deploy always failed at `podman play kube`. That profile also mounts other volumes than the `starexec-prod-*` volumes that job containers and `volumes-backup ENV=prod` use. `make deploy-podman` now refuses to render that profile, before it pulls, creates or stops anything. DEPLOYMENT.md, CONFIGURATION.md, QUICKSTART.md and README.md deploy production with `VALS=charts/starexec/values-podman-prod.yaml`, copied from `values-podman.yaml.example`; the docs copied `values-podman.yaml`, which is not in the repository. `values-podman-prod.yaml` is gitignored.
- **Queue management works on Kubernetes again**: Creating or deleting a queue, moving nodes and clearing node errors all failed with HTTP 500 ("Backend failed to create queue"). fabric8's `edit()` clones the live Node through Jackson, and with Jackson 2.21 that clone throws on the node's managedFields. The backend now changes nodes with a JSON merge patch that carries only the fields it sets.
- **The incident response plan keeps its evidence**: SECURITY.md told responders to run `make stop` before copying the logs, which removes the app container and with it the only copy of `/var/log/starexec`, and to append `iptables` DROP rules that also cut the responder's own SSH session. It now copies the logs out first (Kubernetes and Podman commands), warns that `make volumes-backup` with the wrong `ENV` archives empty placeholders, and isolates the system without a host-wide DROP.
- **The upload form's permissions reach the spaces an upload creates**: An upload that converts an archive's directories into spaces gave every created space the same default permissions, the five "add" ones and no "remove" ones, whatever was checked on the form, although the form says the new spaces get "these default permissions". The worker hard-coded them; the multipart upload never passed the form's choice on; and the resumable upload, which the page uses for every local file, never sent it. StarexecCommand's withheld permissions (`addUser=false`) also counted as granted. The choice is now stored with the upload (V0120, `space_permissions` on `upload_jobs` and `upload_sessions`) and applied; jobs queued before V0120 keep the old default. An upload with nothing checked now creates spaces whose new members get no permissions, as the form says.
- **Kubernetes pods that never start are retried again**: A pair whose pod waited past `STAREXEC_K8S_PENDING_TIMEOUT_MINUTES` without starting is queued again once, spending the same `pairs_rerun` allowance as `RERUN_FAILED_PAIRS`, and is filed `ERROR_SUBMIT_FAIL` when that allowance is already spent. It was left `ENQUEUED` under a false "INGESTION INTERVENTION REQUIRED" until `FIND_BROKEN_JOB_PAIRS` filed it `ERROR_SUBMIT_FAIL` up to three hours later, a status the automatic rerun never selects, so the retry `docs/BACKENDS.md` promised never happened. No solver result or stage is written for a pod that never ran.
- **The Kubernetes backend starts on clusters that enforce RBAC**: The chart let the application's service account manage Jobs and read pods, but the backend also reads its own namespace at startup and lists the worker nodes before it dispatches. Where RBAC is enforced both reads were refused, so the backend never initialized and no pair was submitted; the admin "pause all" was also refused its single request to delete every managed Job. The chart's Role now grants `get` on the release's own namespace and `deletecollection` on Jobs, and a new ClusterRole grants `get` and `list` on nodes. Clusters that do not enforce RBAC, such as MicroK8s without its `rbac` add-on, were not affected. Changing node labels from StarExec still needs `kubernetes.nodeManagement.enabled`.
- **Four more StarexecCommand commands match the server**: `getjobpairs` sent its pair ids as an unencoded `id[]`, which Tomcat refuses (HTTP 400) before StarExec sees it, so the client reported that the archive did not exist. `pushspacexml` read the server's answer to an accepted upload, a redirect to its status page with no `New_ID` cookie, as an internal error, inviting a retry that uploads the spaces twice. `setspacepublic` and `setspaceprivate` took the server's HTTP 200 as success, although the server answers 200 with `success: false` when it refuses. `pushsolver set=` parsed the parameter's name instead of its value, failing every such upload, and sent the profile as `setting` where the server reads `settingId`. The client now encodes its query parameters, reports a space XML upload's request id, reports a refusal as an error, and sends the chosen settings profile.
- **The production and development resource settings take effect**: `values-prod.yaml` and `values-dev.yaml` set `resources.requests` and `resources.limits`, keys no template reads (the chart reads `resources.app`, `resources.postgres` and `resources.migrations`), so both ran the application container at the chart defaults: a 2Gi memory limit for a JVM whose heap may grow to 2048 MiB (`-Xmx2048m`, set by the image), leaving nothing for the memory the JVM uses outside the heap. The settings now sit under `resources.app`, so production gets the 2Gi request and the 8Gi and 4-CPU limits its values file declares, and development the 4Gi limit. The chart check now refuses a `resources` key that the chart's `values.yaml` does not declare, and the documentation examples that showed the same unread shape are corrected.
- **Pair-level results**: A pair that fails outside any stage — before the stage loop starts, or between two stages — is now recorded against the pair. The job script reports such a failure on the pair-level channel, with stage number 0, and every monitor refused that record because the precise write requires a stage number of 1 or greater: the pair kept its last status (RUNNING) for as long as the application ran, while the monitor re-detected the same finished execution on every poll. A new `UpdatePairStatusPairLevel` routine records the result against the pair, marks the stages that had not finished as not reached, and preserves a stage that did finish and was already snapshotted. A pair-level RUNNING record, which the job script writes before it knows the stage, is still not written.
- **The app's memory limit leaves room beyond its heap in every profile**: The image starts the JVM with `-Xmx2048m`, but the chart's defaults gave the app a 2Gi memory limit (which `values-podman.yaml.example`, setting no resources, inherited), `values-local-dev.yaml` 1Gi and `values-ci.yaml` 512Mi. The JVM keeps an explicit `-Xmx` whatever the limit is, so 2Gi left nothing for the memory the JVM uses outside its heap, and under 1Gi and 512Mi the heap outgrows the limit long before it is full. Without swap, the kernel then kills the JVM (OOMKilled) with no `OutOfMemoryError`: under 1Gi, a JVM started with the image's options was killed holding 896 MiB of heap. The pods `make start` plays have no swap limit, so on a host with swap what does not fit goes to swap instead: under 1Gi, 1023 MiB of a 1920 MiB heap. Those profiles now set a 3Gi limit, and `scripts/check-chart-app-heap.py` fails the Helm validation workflow when a profile's heap is above 75% of the app's limit.
- **Kubernetes secret examples have the keys the chart reads**: The database secret examples in CONFIGURATION.md and SECURITY.md had only a `password` key, but the chart reads `user`, `password` and `database`, so the pod could not start; one also called a `generate-password` command that does not exist. The rotation steps set the database password to the literal `new-password` and replaced the secret with that single key. They now set the role from the generated password, patch only the password key, and say that on Quokka the Jenkins credential must change too.
- **The live pair log shows the whole log, once**: For a finished pair, the stream sent one chunk of at most `STAREXEC_PAIR_LOG_STREAM_READ_CHUNK_BYTES` (8192 by default) and then `complete`, so a longer log was cut off. A running pair's stream sent one chunk per poll and fell behind any log that grew faster. Each poll now sends up to 32 chunks of at most that size, which is what the setting documents; a stream still behind its log polls again at once, and a finished pair's stream sends `complete` only after the last byte. A character split between two chunks showed as two to four replacement characters. Chunks now end on whole UTF-8 characters: while a pair runs, a character only partly written to its log is sent once it is whole, and only a finished log that stops partway through a character ends with those bytes as they are. The pair page also appended the stream to the log it had already rendered, showing up to the first 8 KB twice. The first chunk that starts at byte 0 now replaces the rendered copy.
- **Processor failure attribution**: A failing pre- or post-processor is now recorded as `ERROR_PRE_PROCESSOR` or `ERROR_POST_PROCESSOR` instead of `ERROR_BENCHMARK`. Two faults combined to hide it: the branch that sent the specific status tested `$?` on the line after the command, which `set -e` never reaches, and the paths that did run sent their status without claiming it, so the fail-closed EXIT trap overwrote it. A file-write limit breach was misreported the same way. The status now also names the stage that was running instead of taking the pair-level default of 0, which a precise stage write applies to no stage at all while marking every stage of the pair as not reached. A runscript error was misattributed the same way in the opposite direction: the stage number was decremented before being written, so the first stage's failure was recorded against stage 0.
- **A stage without a post-processor no longer adds to the error log report**: A stage records attributes only from a post-processor that succeeded, so a stage that ran none never has a per-stage attributes file. Each monitor logged that normal outcome at WARN (`Pair <id> stage <n> finished without a per-stage attributes file`), and StarLogger stores every WARN as an `error_logs` row, so every finished stage that ran no post-processor added a row to the error log report: 85 in one end-to-end run, on each of Local, Podman and Kubernetes. It is logged at DEBUG now: it stays in the application log, but is no longer stored or mailed. A finished stage without its measurements has lost them, and that is still a WARN.
- **Kubernetes queue placement is documented as it works**: `BACKENDS.md` and `CONFIGURATION.md` said that queue labels did not yet affect where a pair's pod runs. They do: a named queue's pods select nodes carrying its label, the default queue's run on workers without one, and a pair whose queue no worker node carries ends with `ERROR_SGE_REJECT`. The labelling example now marks both nodes as workers; it left `worker-2` without the worker label, so pairs on its queue would have been refused. The section also lists when pairs wait instead, including a `STAREXEC_K8S_CPU_LIMIT` that no schedulable node of the queue can allocate.
- **A rerun job is incomplete again**: Rerunning any of a job's pairs, by hand or automatically, now clears the job's completion time, and the rerun's last pair stamps it afresh. It was stamped once and never cleared, so a rerun job kept showing its first completion time while its pairs ran again and after they finished.
- **Reruns and deletions work again on the Podman backend**: Every manual rerun and rerun-all was refused, deleting a job whose pairs were running stopped their containers but left the pairs recorded as running and the job undeleted, retried every five minutes without end, and deleting a user who owned such a job was refused. Each of these proceeds only once the backend confirms that the pair's previous execution can no longer run or write a result, and the Podman backend never confirmed it, not even for a pair that had completed. It now stops the pair's container and then asks the container engine: the execution is confirmed stopped once no container labelled with its execution id is running, paused or restarting, and none that exited can still have its result recorded without the attempt fence. A container the backend is not tracking is reported and never stopped, because execution ids start again with each application start.
- **Pair-level status validation**: The Local and Kubernetes backends now check a pair's status against `isTerminalExecutionResult()` before recording it, as the container monitor already did. `status.json` is written by the job into a directory the job controls, and `STATUS_PROCESSING` in particular is promoted to `STATUS_COMPLETE` by the periodic post-processing task, so a job could otherwise record a status that turned its own timeout into a clean completion.
- **Kubernetes upgrade and rollback steps say what Helm keeps**: DEPLOYMENT.md showed `helm upgrade -f my-values.yaml`, which drops every earlier value the command does not repeat, the image digest included; pinned a chart `--version 1.2.0` that was never published; and did not say that `helm rollback` leaves the database migrated. Quokka upgrades go through Jenkins, manual upgrades pass the full values to the checkout's chart, and going back past a migration means restoring the dump taken before the upgrade.
- **A refused pair log reads like any other refusal**: `GET /services/jobs/pairs/{id}/log` answered a user who may not see the pair with "user <id> does not have access to see job <pair id>", which named the pair as a job, in text a client could not tell apart from a log. It now answers "not available", the REST layer's answer to every refusal, which the endpoint already gave for a missing log.
- **Concurrent execution isolation**: Each job pair now executes in its own workspace under `$STAREXEC_BACKEND_WORKING_DIR/sandbox`, keyed by pair and backend execution id. Every execution artifact is derived from that directory -- solver, benchmark, tmp, and the whole output directory including `stdout.txt`, `var.out`, `watcher.out` and `attributes.txt` -- and it was previously one directory shared by every pair running on the Local backend, where pairs are processes in a single container rather than one container each. Concurrent pairs overwrote each other's solver output, and each pair then copied whatever was in the shared directory into its own results. The shared solver cache stays shared, because it sits beside the sandbox rather than inside it; the separate hazard that a pair's own failure can evict a cache entry another pair is copying from is unchanged and tracked separately.
- **Operations docs no longer recommend commands that delete data**: TROUBLESHOOTING.md retried a registry timeout with `make stop nuke build start status ENV=dev FORCE=1`, which deletes every volume of the environment and the image. The database-only reset and restore used `VOLUMES=` and `BACKUP=`, which the Makefile does not read, so the reset deleted all five volumes and the restore ignored the archive it named. DATABASE.md cancelled and pruned jobs with `UPDATE` and `DELETE FROM job_pairs`, overwriting or dropping their results. VOLUMES.md recommended `podman volume prune` and `rsync --delete`, ran `pg_resetwal -f` before taking a copy, and tested recovery beside production, which stops it. DEPLOYMENT.md said `helm uninstall` keeps the PVCs.
- **`helm uninstall` no longer deletes the volumes the chart created**: The chart's PersistentVolumeClaims carried no `helm.sh/resource-policy`, so `helm uninstall`, `make undeploy-k8s`, and an upgrade or rollback that stopped rendering a claim deleted it, and with the usual `Delete` reclaim policy its volume: every uploaded solver, benchmark and job result, and the database when the chart creates its claim. Every values profile except `values-prod.yaml` lets the chart create its claims. The chart now marks them `helm.sh/resource-policy: keep` (`persistence.resourcePolicy`; `""` restores the old behaviour), and a later install with the same release name in the same namespace adopts them again. A release's existing claims are marked on its next `helm upgrade`. CI checks the annotation with `scripts/check-chart-pvc-retention.py`.
- **Result ingestion**: Reading a pair's per-stage snapshots no longer validates the stage still running. The stage a pair is currently executing legitimately reads `STATUS_RUNNING`, and rejecting it marked the pair permanently un-ingestible, so whether a completed run was recorded at all depended on whether a poll arrived before or after the stage finished. Stages the pair has already passed are still validated unchanged, and the running stage's record is now excluded from the result rather than returned unchecked, so it cannot be ingested.
- **Old job graphs are deleted again**: The weekly task that removes job graphs older than seven days looked for them in `/jobgraphs` at the filesystem root, which does not exist, instead of in the web application's `jobgraphs` directory where `Statistics` writes them. It never deleted any, so every graph a user opened (two images per view) stayed on the server's disk.
- **Local-backend pairs record when they started**: In container mode the job script cannot reach the database, so it leaves a pair's `start_time` to the host. PodmanBackend and KubernetesNativeBackend set it; LocalBackend did not, so every pair it ran in a container finished with no start time, and anything derived from it, such as the stuck-job check's last-activity time, read nothing. LocalBackend now sets `start_time` when it starts the pair's process, filling only an empty value, which a rerun clears.
- **Zombie detection**: The check for whether a job script has already reported a result now parses `status.json` with Gson and asks `isTerminalExecutionResult()`, instead of matching a regex and testing `status >= 7`. A true answer kills the process, and the numeric test is also true of the three statuses that mean work is still owed. No status the job script currently emits is affected.
- **The Podman database restore can run**: DATABASE.md stopped the whole pod with `make stop` and then ran `psql` in the PostgreSQL container it had just removed, restored a plain dump over the live database, and used a `postgres` role that does not exist. It now stops only the app container, restores into a recreated database with `ON_ERROR_STOP`, and connects as `STAREXEC_DB_USER`.
- **Local-backend pair logs are written once**: In container mode the job script's `log` also appends each line to the pair log in `STAREXEC_OUTPUT_DIR`, for Kubernetes and Podman, where stdout goes to the container runtime. The local backend makes stdout that same file, opened without append, so the appended copy landed where the next output overwrote it. A finished log ended with fragments and repeats of earlier lines after `Jobscript ending.`, and the live pair log showed some lines twice and skipped others. `log` now appends only when stdout is not already the pair log.
- **Deleting or killing a job during a pair's submission no longer strands the pair**: `JobManager` claims a pair, has the backend start it, and only then records the execution's id, which it refuses to do once the job has been killed, deleted or paused. The execution was then stopped with the legacy `killPair`, which proves nothing, and nothing else was written, so the pair stayed ENQUEUED with no execution id. `Jobs.kill` cannot prove that an execution it cannot name stopped, so deleting the job was refused every five minutes until FIND_BROKEN_JOB_PAIRS filed the pair ERROR_SUBMIT_FAIL, up to three hours later, and a killed job kept showing the pair as enqueued. The execution is now stopped through `killPairConfirmed` and its id is recorded. After a confirmed stop the pair is KILLED when its job was killed or deleted, and returns to PENDING_SUBMIT when the job was paused; after an unconfirmed one it stays enqueued under its execution id, which the kill path can then confirm.
- **Upload errors name the missing dependency**: A refused upload said only that dependencies "could not be resolved". It now names the benchmark, the include that was not found and the dependency root space searched, for example "DAT013_1.p includes Axioms/DAT001_0.ax, which was not found under the dependency root space (id 38)". A batch rejected over one file no longer says that every file in it failed. The upload status pages now escape the error message and the names of failed files.
- **Upload dependency resolution applies the uploader's permissions**: An upload's dependency root space must be one the uploader can see, and includes resolve only through subspaces the uploader belongs to, as they did before March 2026.
- **Cancelling a `make` target or previewing it with `DRY_RUN=1` no longer deletes anything**: Each recipe line runs in its own shell, so the `exit 0` that was meant to stop a target ended only its own line and the commands after it ran anyway. Answering anything but the environment's name to `make clean-hard` or `make reset` still removed the deployment, every volume of the environment and the image, then printed "Hard reset complete"; `DRY_RUN=1 FORCE=1` did the same while printing the preview; `make volumes-delete DRY_RUN=1` went on to the real deletion, stopped only by the volume script's own `DELETE` prompt. Declining `make clean-cache` still pruned every unused image and the build cache, declining `make migrate-podman` still ran the migration, and declining the running-pod warning of `make volumes-backup` still took the backup. A cancelled target now stops with an error and changes nothing, `DRY_RUN=1` only prints, and `make reset` no longer stops the deployment before asking. The preview also names the legacy volumes and, with `CLEAN_HOSTPATH=1`, the host directories the deletion removes.
- **Stuck-job warnings**: The periodic stuck-job check reports a job only when no pair of it has started or finished for longer than its own wallclock limit plus ten minutes (at least thirty), skips paused, killed and deleted jobs, names the likely cause (for example an inactive queue) in one line, and repeats it every six hours instead of every five minutes. It used to report every job older than ten minutes with an unfinished pair, with LocalBackend-only advice, as five warnings per job every five minutes.
- **Podman upgrade, rollback and rebuild steps deploy the image they build**: DEPLOYMENT.md's upgrade, rollback and fresh-build restart, and DEVELOPER.md's build-and-run steps, ran `make start` without `LOCAL=1`, so `make start` replaced the image just built with the registry's `latest` whenever the registry was reachable; DEVELOPER.md also said `make start` rebuilds the image. The upgrade and rollback omitted `ENV=prod`, so they stopped production and started the dev environment on the `starexec-dev-*` volumes, and the rollback restored with `BACKUP=`, which the Makefile ignores. The upgrade now takes a backup first, with the stack stopped.
- **Email length validation**: `isValidEmail` now applies the length bound its contract already promised. `users.email` is `VARCHAR(64)`, so an over-long address previously passed every client- and server-side check and then failed at the INSERT.
- **Email validation**: Addresses on top-level domains longer than four characters — `.technology`, `.university`, `.education`, `.engineering` — are now accepted. The bound is the 63-octet DNS label limit rather than an arbitrary smaller number. The client already accepted these, so the address was refused only after the form was submitted.
- **The chart keeps the embedded database by default**: `postgres.persistence.enabled` defaulted to false, so a release installed from the base values ran its PostgreSQL sidecar on an emptyDir, and every pod recreation (an upgrade, a node drain, an eviction) started the database empty, losing every account, solver, benchmark and job result while the data volumes kept the files. The default is now true: the database gets its own PersistentVolumeClaim from `postgres.persistence.storageClass` or the cluster's default StorageClass. Profiles that set the value keep it; `values-ci.yaml` and `values-dev.yaml` still run the database on an emptyDir. `scripts/check-chart-db-persistence.py`, run by Helm Validation, fails if the base values stop persisting the database. DEPLOYMENT.md's install from the Helm repository now creates the credentials secret and passes `postgres.existingSecret`, since the chart refuses the two `--set` passwords it gave, and the examples drop `persistence.postgresDataSize`, which no template reads.
- **A failed email is no longer reported as sent**: When the mail server could not be reached, the password reset page still said "An email has been sent", and a new registration still said to expect an activation email. `Mail` swallowed every failure, so the registration's existing `email_failed` outcome was never reached. Both pages now say the email could not be sent and name the contact address. An admin who creates a user is warned when the email with the login details fails. "Sent" means the mail server accepted the message: a message it accepts can still bounce later, which no page can see (#82, #125).
- **A request to join a community reaches every leader whose address works**: StarExec emailed the request to all of the community's leaders as one message. One address that could not be used, such as the seeded admin account's `admin`, which commons-email refuses, or a recipient the mail server rejects, failed the message for every leader, and the log still said the request had been sent. Each leader now gets a message of their own, and the log says how many of the leaders the request reached. A community without leaders, or leaders that cannot be read, is now logged as a warning, where it used to be logged as sent or to throw `NullPointerException`. The daily error log report is sent the same way, one message per recipient.
- **The error log report no longer deletes what no one received**: Once a day StarExec emails the errors and warnings it logged to the admins and developers subscribed to its error log report, then deleted all of them, even when the mail server accepted the report for no one, so they were lost unread. It also deleted the lines logged while it was sending, such as the warning that sending failed. The report now deletes exactly the lines it contained, once the mail server has accepted it for at least one recipient. Lines no one has received wait for the next report, for up to 5 days, about as long as mail servers retry an undeliverable message. A subscriber who is not an admin or developer is named in the log by user id instead of by email address.
- **Benchmark upload by URL works again (#139)**: Choosing URL on the benchmark upload form returned HTTP 500, because the asynchronous upload path only handled uploaded files. The request now checks the address (http or https, naming an archive) and returns at once; the upload worker downloads the archive on the head node, then processes it as any other upload, and a failed download shows as the job's error. Only one upload by URL runs at a time, so slow downloads never hold every upload worker. Upload by Git still returns an error.
- **Failed solver uploads and copies leave nothing behind (#275)**: An upload refused for its description file or for its owner's disk quota, or whose database insert failed, left its directory, and usually the solver's files, on disk. An upload whose owner was deleted while the archive was still arriving recreated that user's solver directory, which nothing would ever remove, and answered with a `NullPointerException` message. `Solvers.add` also committed its statements one at a time, so when the space association or a configuration failed, for example because the space was removed during the upload, the solver row and the disk usage charged to its owner stayed committed, with the solver in no space. The insert is now one transaction. An upload or copy that does not add its solver deletes the directories it created and, when the owner no longer exists, the empty directories above them. Each upload or copy now claims a directory of its own: two of one name by one user in the same millisecond used to share one, so cleaning up after one could have deleted the other's files. An upload whose owner is gone is refused with "Could not find the user uploading this solver".
- **Failed benchmark uploads, copies and adds leave nothing behind (#275)**: A benchmark upload whose owner was deleted while it ran left files on disk for good. An upload by URL wrote the downloaded archive into the user's directory after the deletion had removed it, and the job, whose row went with the user, was never retried or cleaned up. An upload, copy, single add or upload session that did not add its benchmarks also left the directory it had created, with its files. Each now deletes what it wrote when it adds nothing and, when the owner no longer exists, the empty directories above it; the upload worker does the same for a job whose user is gone. A failed lookup of the user is never taken for a deleted user, so a live user's failed upload still keeps its archive for a retry. Each upload, copy, add or session now claims a directory of its own, so cleaning up after one cannot delete another's files, and a chunk sent to an upload session no longer recreates the session's directory after it was deleted. An upload whose owner is deleted before it is queued is refused with "Could not find the user uploading these benchmarks".
- **Directory names are safe under concurrent requests**: Solver, processor, job XML and space XML uploads, and solver copies, named the directories they create with a `SimpleDateFormat` that every request shared. `SimpleDateFormat` is not thread-safe, so two requests formatting at once could get each other's time, or fail with an `ArrayIndexOutOfBoundsException`. They now share one immutable `DateTimeFormatter`, as benchmark uploads already did. The names keep the same format.
- **Space and job XML uploads delete their files when done**: Each upload extracted its archive into a directory under `batchSpace/uploads` and never removed it, so the data volume kept every space and job XML ever uploaded. An upload now deletes its directory once the spaces or jobs are created, whether or not that worked, and also when it fails before processing starts. Each upload also claims a directory of its own: two uploads by one user in the same millisecond used to share one, and one of them could create spaces or jobs from the other's files.
- **A space or job XML archive that cannot be extracted says so**: An archive that could not be extracted, because it was damaged or was refused for safety, was taken for one that holds no XML. A space XML upload finished as complete, with 0 spaces and the message "no error", and a job XML upload was refused with "Failed to upload Job XML:" and no reason. Both now say that StarExec failed to extract the file, and what may have caused it.
- **`make db-dump` works on both Podman layouts and leaves no empty backup**: The dump ran `pg_dump` in a container named `starexec-postgres` as the role `postgres`. `make start` names the database container `starexec-pod-postgres`, and both layouts create only the role `starexec` (`POSTGRES_USER`), so the dump failed on both, each time leaving an empty `postgres-dump-<env>-<time>.sql.gz` that looked like a backup. `make db-dump` now resolves the container as `make db-shell` does, the role defaults to `starexec`, and the dump is written under a temporary name and renamed only once `pg_dump` has succeeded.
- **Disk accounting**: Recording runsolver statistics is now idempotent. Repeated calls previously added the reported figure to user and job totals each time while overwriting the stage row, leaving a surplus that no refund could reclaim.
- **No false interventions after a restart**: A Kubernetes Job that finished before the application restarted, whose pair already holds its result, is no longer ingested again. The cluster keeps such Jobs for `ttlSecondsAfterFinished`, and the restarted process tracks none of them, so it found no status.json and reported an ingestion intervention for each; one deploy during a rerun on Quokka produced 122. An untracked Job whose pair is still unresolved is reported as before.
- **`latest` and the branch tag follow the newest push**: Container Publish runs for pushes made close together finish in any order. On 2026-10-19 four merges landed within 10 s, the run for the branch head finished first, and a run for an older commit then moved `latest` and `containerised` to its own image. A run now moves `latest` and the branch tag only if its commit is still the head of the branch when it publishes; otherwise it leaves them alone and says so in its summary. Every commit still gets its `sha-<commit>` and `<branch>-<short sha>` images, and Jenkins, which deploys `sha-<commit>`, is unaffected.
- **Space hierarchy**: Moving a space into itself or into one of its own descendants is now rejected, preventing a space from becoming its own ancestor.
- **`APP_PORT` changes the Podman port again**: `make deploy-podman APP_PORT=8080` printed `http://localhost:8080/starexec`, but with Helm installed the app was still published on 7827: the chart hard-coded the host port and `deploy-podman-helm` never passed `APP_PORT`. The chart now reads it from `podman.hostPort` (default 7827), and the target sets that from `APP_PORT`, as the Helm-less path already did.
- **Benchmark uploads**: The asynchronous upload path now satisfies the same invariants as the synchronous one, creating the space association and charging disk usage. Uploaded benchmarks were previously invisible in their space, and deleting them could drive recorded disk usage negative.
- **Pair status codes and the stuck-job diagnosis in the observability guide**: `OBSERVABILITY.md` listed eight status codes, most of them wrong (it gave 8 as "killed"; 8 is a rejected submission and killed is 21), and its stuck-job query read a `job_pairs.created` column that does not exist and the wrong status. The table now lists every code in `Status.StatusCode`, and the diagnosis starts from the "has made no progress" warning, which names the cause, such as an inactive queue.
- **Results of pairs in flight across a restart are recorded**: Tracking rebuilt after a restart looked for a Kubernetes execution's status.json under the pair's stdout directory, but the pod writes it under the pair's log directory, so every pair in flight across a restart was reported as an ingestion intervention and left unrecorded (48 after one deploy on Quokka). Jobs now record their output directory in the `starexec.org/output-dir` annotation, which the rebuild already read but nothing wrote, and a Job without it is rebuilt with the log directory submission used.
- **`make deploy-k8s` deploys the values file it is given**: The target compared `HELM_VALUES` (default `values.yaml`, a file that does not exist) with `charts/starexec/values.yaml`, never matched, and replaced any values file it could not find with `values-kubernetes.yaml`, whose PostgreSQL host is a service nothing deploys. `make deploy-k8s ENV=prod`, as KUBERNETES_AUTO_DEPLOYMENT.md gives it after `make k8s-generate-values`, therefore deployed that profile instead of the generated values. `HELM_VALUES` now defaults to `charts/starexec/values-auto-detected.yaml`, and a values file that does not exist stops the deploy.
- **Job graphs say why they cannot be drawn**: With no configuration selected, the space overview graph answered HTTP 500, and the completed-pairs-over-time graph did the same whenever drawing failed. The solver comparison graph reported an internal database error when one of the two configurations had no pair to plot; it plots only completed pairs whose result matches the benchmark's expected result. Each case now answers with a message, and a comparison of a configuration with itself asks for two different ones. The job page treated every reply as a chart and kept the previous selection's chart; it now shows its placeholder image, and the message when the graph was requested with Update. Only the latest request for a graph draws, clears or reports it: pressed again before a graph arrived, Update let the earlier answer replace or clear the newer chart. Its own comparison draw after the statistics load stays silent, because the page hides its graphs panel (#370).
- **A Podman backend that failed to start runs nothing**: When `PodmanBackend.initialize()` failed — the container engine unreachable, or an explicit `STAREXEC_CPU_PARTITIONS` that splits hyperthread siblings — the application started anyway and JobManager kept dispatching to the backend. A pair then failed with `ERROR_SGE_REJECT`, or, had the engine client been set, ran in an unpinned container with no monitor to record its result or free its slot. The backend now reports its queues as not dispatchable until `initialize()` completes, so pairs stay queued, and refuses any submission that skips that check. An unsafe partition configuration is logged as `Podman backend not started:` instead of as a failure to connect to the container engine, and cpu-partition-scheduling.md no longer says StarExec as a whole refuses to start.
- **The Podman start-up log says whether the backend started**: The Podman backend logged "PodmanBackend initialized successfully" as soon as the container engine answered, before its image check, CPU partitions, queues, reconciliation and job monitor, and reported a failure in any of those steps as "Could not connect to the container engine", with advice about the socket. It now logs the connection as "Connected to the container engine", logs "initialized successfully" only once it is ready, and names the failing step's error when start-up fails after the engine answered.
- **The dispatcher reports a queue that cannot dispatch when that starts, not every 20 seconds**: It logged a WARN on every pass for a queue whose backend could not accept work, and a WARN or an ERROR on every pass for an active queue with no nodes, each one a row in `error_logs`: a Podman backend that failed to start wrote three a minute, 4,320 a day, all of them sent in the daily error report. Each condition is now logged when it starts, again every six hours while it lasts, and its end once at INFO (`Queue <q> has nodes again`, `Queue <q> accepts work again`); a restart reports a lasting one again. The stuck-job report also asks the backend: for pending pairs on an ACTIVE queue whose backend refuses work, it names the backend, where it used to say to check the queue's nodes.
- **The Kubernetes backend reports a dispatch hold when it starts, not on every pass**: The dispatcher asks the backend on every pass whether each queue with pairs waiting can take work, and the backend logged its reason for holding a queue every time: all of its nodes cordoned or NotReady, a CPU request that no node can fit, CPU capacity it cannot establish, admission held by a pod it cannot account for, or a node view that never loaded. Each was a row in `error_logs` every 20 seconds per queue. A node listing that kept failing logged a WARN with its stack trace on every check. Each is now logged when it starts or its reason changes, again every six hours while it lasts, and its end once at INFO (`Kubernetes no longer holds dispatch for queue '<q>'`, `Refreshed the Kubernetes queue view again`). `CPU capacity gate cleared for queue '<q>'` is gone: with two queues, one of them held, it was logged on every pass for the other.
- **Result recording**: A write refused because another writer already recorded a result is now distinguished from a write that failed, so a finished pair releases its container and an unfinished one stays discoverable.
- **Job pair locking**: Normalized job pair lock ordering and repaired stored procedures that returned only the first matching row.
- **A job whose pairs are all dispatched can be paused**: Pausing a job with no pair left to dispatch failed with "an internal database error", and its enqueued and running pairs ran on. That is the usual state of a small job and of the end of a large one. `PauseJob` raised "has no pending pairs to pause", which rolled back the pause before `Jobs.pause` stopped anything; `KillJob` and `ResumeJob` had already dropped the same check. The scheduler's pause of an over-quota user's jobs and the pause of jobs on a removed queue failed the same way. For an over-quota user, one failed pause also skipped every later job; each job is now attempted.
- **A paused pair stays paused on the local backend**: A pair that was running when its job was paused was stopped and marked paused, and on its next poll the local job monitor read the RUNNING status the stopped run had left behind and wrote it back. The pair then had no process, resume passed it over because it was not paused, and the broken-pair sweep (at startup and every three hours) recorded it as a submit failure the solver never had. This happened whenever a job was paused with a pair running, not only in the case above. Pause now retires the backend's tracking of each pair it stops before marking it paused, as kill already did.
- **Job page links behind a proxy**: The job page no longer builds its links from `X-Forwarded-Host` / `X-Forwarded-Proto`. Each proxy appends to those headers, so behind two of them the matrix view, attributes, solver comparison, panel view, downloads and every pair link pointed at `https://host, host/...`. The live page now links relative to the context root, as every other page does; the offline copy uses the configured server address.
- **BACKENDS.md describes what a Kubernetes restart does to running pairs**: It said the startup reconciliation marks a RUNNING pair whose Job is gone as failed. The backend leaves such a pair RUNNING and logs `RECONCILIATION INTERVENTION REQUIRED`; the periodic broken-pair check sets it to `ERROR_SUBMIT_FAIL` within three hours, and the automatic rerun does not retry that status. The page now says so, and that a Job finishing during an outage longer than `kubernetes.ttlSecondsAfterFinished` (3600 seconds by default) is deleted before StarExec can record its result.
- **Strict CPU mode is no longer documented as isolating physical cores**: `STAREXEC_K8S_STRICT_ONE_PAIR_PER_CPU` was described as enforcing one job pair per physical CPU core, but it only refuses a fractional CPU limit. Exclusive CPUs need the kubelet's static CPU manager policy, and whole cores also its `full-pcpus-only` option, which StarExec does not check. The configuration reference, the chart comments and the startup log now say so, and the Kubernetes and MicroK8s guides describe how to check a node and a run (#286).
- **Login table no longer documented as a permanent history**: `DATABASE_STANDARDS.md` said login rows survive user deletion, but `DeleteUser` deletes them, and every Thursday the weekly reports task empties the `logins` table after storing the report. The standards and the `addLoginRecord` Javadoc now say so; behavior is unchanged.
- **Job creation without traversal or pause**: A create-job request that omits `traversal` or `pause` now uses the documented defaults (depth-first, not paused). Before, a request without `traversal` failed with HTTP 500. One without `pause` also returned HTTP 500, but only after the job had been created and started, so a client that retried on the error ran the job twice. A blank value now counts as absent; a blank `traversal` used to build the job round-robin. Any other value than `depth`/`robin` or `yes`/`no` is now rejected with HTTP 400 before a job is created.
- **Job creation without a description or processors**: The API guide lists `desc`, `preProcess` and `postProcess` as optional, but a create-job request that omitted either processor failed with HTTP 500, and one that omitted `desc` was refused with HTTP 400. An omitted or blank processor now means none, and an omitted description is stored as an empty one is (`no description`), which is what StarexecCommand's defaults produce. A value that is sent is still validated.

### Security
- **Servlet authorization**: Corrected authorization checks that reported a failure without returning, so the privileged work ran anyway. The most serious allowed any authenticated user to rewrite the queue-to-community access list.
- **StarDev transfer**: A failed StarDev login now stops the transfer instead of continuing on a connection that never authenticated.
- **Build provenance**: The runsolver binary is now compiled from the source vendored in this repository. It was previously downloaded from an external host at image build time with no checksum or pinned digest, so the instrument every recorded measurement comes from is now reproducible from the tree it was audited against.


## [2.5.1] - 2026-08-03

### Fixed
- **Description validation**: Primitive descriptions now consistently accept `+` and `-` across server, client, XML schema, and import paths, including URL-like scientific metadata.
- **Description editing**: Job descriptions are submitted as form data so values containing `/` round-trip correctly.

### Security
- **Description hardening**: Added pre-write import validation, contextual output encoding, bounded no-follow filesystem reads, and raw-description log redaction while retaining the legacy safety blacklist.

## [2.5.0] - 2026-07-29

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

[Unreleased]: https://github.com/StarExecMiami/StarExec/compare/v2.5.1...HEAD
[2.5.1]: https://github.com/StarExecMiami/StarExec/compare/v2.5.0...v2.5.1
[2.5.0]: https://github.com/StarExecMiami/StarExec/compare/v2.4.0...v2.5.0
[2.4.0]: https://github.com/StarExecMiami/StarExec/compare/v2.3.0...v2.4.0
[2.3.0]: https://github.com/StarExecMiami/StarExec/compare/v2.2.0...v2.3.0
[2.2.0]: https://github.com/StarExecMiami/StarExec/compare/v2.1.0...v2.2.0
[2.1.0]: https://github.com/StarExecMiami/StarExec/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/StarExecMiami/StarExec/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/StarExecMiami/StarExec/releases/tag/v1.0.0
