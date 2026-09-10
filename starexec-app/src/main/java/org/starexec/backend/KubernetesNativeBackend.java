/*
 * KubernetesNativeBackend - Next-generation Kubernetes-native job execution
 *
 * ============================================================================
 * SCAFFOLDING FOR FUTURE DEVELOPMENT - December 2025
 * ============================================================================
 *
 * This backend is designed for true Kubernetes-native job execution where each
 * StarExec job pair runs as a Kubernetes Job resource. Unlike the legacy
 * KubernetesBackend (which runs scripts locally and uses kubectl for management),
 * this backend:
 *
 * 1. Creates Kubernetes Job resources for each job pair
 * 2. Uses the Kubernetes Java client (fabric8io/kubernetes-client) for API access
 * 3. Monitors job completion via Kubernetes polling today, with informer-based
 *    monitoring still planned
 * 4. Enables Kubernetes-native execution experiments across multiple nodes
 * 5. Leverages Kubernetes features: resource limits, node selectors, tolerations
 *
 * Architecture:
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │                       KubernetesNativeBackend                                │
 * │  ┌───────────────────────────────────────────────────────────────────────┐  │
 * │  │  KubernetesClient (fabric8io)                                         │  │
 * │  │    - In-cluster config (ServiceAccount) or kubeconfig                 │  │
 * │  │    - Connection pooling handled by client                             │  │
 * │  └───────────────────────────────────────────────────────────────────────┘  │
 * │                                    ↓                                         │
 * │  ┌───────────────────────────────────────────────────────────────────────┐  │
 * │  │  Job Creation                                                         │  │
 * │  │    - Creates K8s Job with starexec/job-runner image                   │  │
 * │  │    - Mounts PVC for data directory                                    │  │
 * │  │    - Sets resource limits (memory, CPU)                               │  │
 * │  │    - Uses worker-node selectors for current job placement            │  │
 * │  └───────────────────────────────────────────────────────────────────────┘  │
 * │                                    ↓                                         │
 * │  ┌───────────────────────────────────────────────────────────────────────┐  │
 * │  │  KubernetesJobMonitor (polling mode today)                            │  │
 * │  │    - Polls for Job completion                                         │  │
 * │  │    - Parses output files from PVC                                     │  │
 * │  │    - Updates database via JobPairs API                                │  │
 * │  │    - Cleans up completed Job resources                                │  │
 * │  └───────────────────────────────────────────────────────────────────────┘  │
 * │                                    ↓                                         │
 * │  ┌───────────────────────────────────────────────────────────────────────┐  │
 * │  │  Queue Abstraction via Labels                                         │  │
 * │  │    - Queue = Node label (starexec/queue=<name>)                       │  │
 * │  │    - Queue labels support node grouping and queue administration       │  │
 * │  │    - Job placement currently uses worker-node selectors only           │  │
 * │  └───────────────────────────────────────────────────────────────────────┘  │
 * └─────────────────────────────────────────────────────────────────────────────┘
 *
 * Dependency:
 *   <dependency>
 *     <groupId>io.fabric8</groupId>
 *     <artifactId>kubernetes-client</artifactId>
 *     <version>6.10.0</version>
 *   </dependency>
 *
 * Configuration (environment variables):
 *   STAREXEC_K8S_NAMESPACE       - Kubernetes namespace (default: starexec).
 *                                   Must contain the shared data PVC and the
 *                                   job ServiceAccount used by Kubernetes jobs.
 *   STAREXEC_K8S_JOB_IMAGE       - Job runner image (default: starexec/job-runner:latest)
 *   STAREXEC_K8S_DATA_PVC        - PVC name for data volume (default: starexec-data)
 *   STAREXEC_K8S_DATA_PVC_ACCESS_MODE
 *                                 - Access mode for the shared data PVC.
 *                                   ReadWriteOnce-style modes pin jobs to the
 *                                   StarExec app node.
 *   STAREXEC_K8S_SERVICE_ACCOUNT - ServiceAccount for jobs (default: starexec-job)
 *   STAREXEC_K8S_APP_NODE_NAME    - Node currently hosting the StarExec app pod
 *   STAREXEC_K8S_QUEUE_LABEL     - Label key for queue discovery and node grouping
 *                                   (default: starexec/queue)
 *
 * Future TODOs:
 *   - [ ] Implement KubernetesJobMonitor with Watch pattern
 *   - [ ] Add Helm chart templates for RBAC (ServiceAccount, Role, RoleBinding)
 *   - [ ] Add PVC templates for shared data volume
 *   - [ ] Implement job priority and preemption support
 *   - [ ] Add metrics export for Prometheus
 *   - [ ] Support for GPU jobs (nvidia.com/gpu resources)
 *
 * @author StarExec Team
 * @since 2.0.0
 */

package org.starexec.backend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.NodeSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.starexec.backend.exception.SubmissionDeferredException;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.PairStatusResult;
import org.starexec.data.database.StageStatusBatchResult;
import org.starexec.data.to.Status.StatusCode;
import org.starexec.logger.StarLogger;

/**
 * Kubernetes-native backend for StarExec job execution.
 *
 * <p>This backend creates Kubernetes Job resources for each StarExec job pair.
 * It is still an experimental path and should not be described as production-ready
 * without cluster validation.</p>
 *
 * <p><b>Status: EXPERIMENTAL</b> - Core submission flow exists; monitoring still polls.</p>
 */
public class KubernetesNativeBackend implements Backend {

    private static final StarLogger log = StarLogger.getLogger(
        KubernetesNativeBackend.class
    );

    // =========================================================================
    // Configuration Constants
    // =========================================================================

    /** Default Kubernetes namespace for StarExec jobs */
    private static final String DEFAULT_NAMESPACE = "starexec";

    /** Label key used to identify StarExec queues on nodes */
    private static final String DEFAULT_QUEUE_LABEL = "starexec/queue";

    /** Label prefix for StarExec-managed resources */
    private static final String LABEL_PREFIX = "starexec.org/";

    /** Label key indicating a Kubernetes Job is managed by StarExec */
    private static final String MANAGED_LABEL = LABEL_PREFIX + "managed";

    /** Label key for execution ID */
    private static final String EXEC_ID_LABEL = LABEL_PREFIX + "exec-id";

    /** Label key for job pair ID */
    private static final String PAIR_ID_LABEL = LABEL_PREFIX + "pair-id";

    /** Label key for StarExec label schema version */
    private static final String LABEL_VERSION = LABEL_PREFIX + "label-version";

    /** Current label schema version */
    private static final String CURRENT_LABEL_VERSION = "2";

    /** Label key distinguishing job-pair resources from future maintenance jobs */
    private static final String KIND_LABEL = LABEL_PREFIX + "kind";

    /** Label value for regular job-pair execution resources */
    private static final String KIND_JOB_PAIR = "job-pair";

    /** Annotation key for the output directory on the shared PVC */
    private static final String OUTPUT_DIR_ANNOTATION = LABEL_PREFIX + "output-dir";

    /** Label key for worker nodes */
    private static final String WORKER_LABEL = LABEL_PREFIX + "worker";

    /**
     * Default queue name for nodes without a queue label.
     *
     * <p>Taken from {@link R#DEFAULT_QUEUE_NAME} rather than invented. This used to be
     * the literal {@code "default"}, which matched nothing else in the system: the
     * canonical name is {@code all.q}, and it is also the name of the seeded row that
     * {@code R.DEFAULT_QUEUE_ID} points at. On a fresh cluster {@code getQueues()}
     * therefore reported a queue called {@code default}, {@code Cluster.loadQueueDetails}
     * created a new database row for it, and the real {@code all.q} was marked INACTIVE
     * for having no nodes.
     */
    private static final String DEFAULT_QUEUE_NAME = R.DEFAULT_QUEUE_NAME;

    /**
     * The SGE short form of the default queue, which is a host-group name rather than a
     * queue name.
     *
     * <p>{@link org.starexec.data.database.Queues#getDefaultQueueName()} returns
     * {@code "all"} because SGE host groups are named {@code @allhosts}; that is an SGE
     * spelling, not a third queue. {@code Queues.removeQueue} passes it straight to
     * {@code moveNode}, so without translating it here a node being returned to the
     * default queue was labelled {@code starexec/queue=all} — minting a spurious third
     * queue alongside {@code all.q} and {@code default}.
     */
    private static final String SGE_DEFAULT_QUEUE_SHORT_NAME = "all";

    /**
     * The queue label value existing clusters were told to apply.
     *
     * <p>Both runbooks instruct operators to run
     * {@code kubectl label node <n> starexec/queue=default}
     * ({@code docs/MICROK8S_SINGLE_NODE.md:52}, {@code docs/KUBERNETES_AUTO_DEPLOYMENT.md:60}),
     * so every already-deployed worker carries it. Renaming the default queue to
     * {@code all.q} without reading that spelling back would strand exactly those nodes:
     * they would be associated with a queue called {@code default} while pairs were
     * submitted to {@code all.q}, which would have no nodes and reject every pair.
     *
     * <p>Read on input, never written on output — {@code moveNodes} labels with the
     * canonical name, so a cluster converges as its nodes are re-labelled.
     */
    private static final String LEGACY_DEFAULT_QUEUE_LABEL = "default";

    /**
     * Every node-label value that means "the default queue".
     *
     * <p>One list, read by both {@link #normalizeQueueLabel} and
     * {@link #defaultQueueAffinity}, so the queue a node is reported to belong to and the
     * nodes the scheduler will actually place a default-queue pod on cannot disagree. When
     * they disagreed, the backend reported a queue schedulable while every pod for it
     * stayed unschedulable — a stall with no error anywhere.
     *
     * <p>Matched exactly, never case-insensitively: Kubernetes label values are
     * case-sensitive, so {@code ALL.Q} is a different label from {@code all.q} and
     * treating them as one would reintroduce exactly that disagreement. The empty value is
     * included because Kubernetes permits it and {@code kubectl label node n1 key=}
     * produces it.
     */
    private static final List<String> DEFAULT_QUEUE_LABEL_VALUES = Collections
        .unmodifiableList(
            Arrays.asList(
                R.DEFAULT_QUEUE_NAME,
                "all",
                "default",
                ""
            )
        );

    /**
     * True if {@code queueName} denotes the default queue under any of its spellings.
     *
     * <p>Case-SENSITIVE, and that is the whole point. This used to compare with
     * {@code equalsIgnoreCase}, which disagreed with every other queue comparison in the
     * system: {@link #DEFAULT_QUEUE_LABEL_VALUES} is matched exactly (see its comment --
     * Kubernetes label values are case-sensitive), and {@code starexec.GetIdByName} is an
     * exact Postgres text comparison, so a queue named {@code ALL.q} is a genuinely
     * distinct row that an admin can create alongside {@code all.q}.
     *
     * <p>The two callers ({@code buildKubernetesJob}, {@code moveNodes}) use this to pick
     * a scheduling constraint. Under the old case-insensitive comparison, a distinct
     * queue named {@code ALL.q} was handed {@link #defaultQueueAffinity()} -- whose value
     * list does not contain {@code ALL.q} -- so its pairs were scheduled onto the default
     * queue's hardware instead of the nodes provisioned for them. Queues separate
     * competitions and hardware classes, so that is a correctness failure, not a
     * scheduling inefficiency.
     *
     * <p>Whitespace is still trimmed: that is a data-entry artifact, and a Kubernetes
     * label value cannot carry leading or trailing spaces anyway.
     */
    private static boolean isDefaultQueueName(String queueName) {
        if (queueName == null) {
            return false;
        }
        String name = queueName.trim();
        return DEFAULT_QUEUE_NAME.equals(name)
            || SGE_DEFAULT_QUEUE_SHORT_NAME.equals(name);
    }

    /**
     * The canonical queue name for a raw node label value.
     *
     * <p>One place, used by every reader of the label, so the queue a node reports and the
     * queue a pair is submitted to cannot disagree.
     *
     * <p>Deliberately wider than {@link #isDefaultQueueName}, and the difference is the
     * point. That method answers "is this a name StarExec uses for the default queue",
     * where {@code "default"} is emphatically not one — it was an invented name that
     * minted a spurious database queue, which is why it was removed. This method answers
     * "what does this label on a node mean", and there {@code "default"} is simply what
     * the runbooks told operators to write. Reading a legacy label is not the same as
     * accepting a legacy name, and only the first is safe.
     */
    private static String normalizeQueueLabel(String rawLabel) {
        if (rawLabel == null) {
            return DEFAULT_QUEUE_NAME;
        }
        String name = rawLabel.trim();
        return DEFAULT_QUEUE_LABEL_VALUES.contains(name) ? DEFAULT_QUEUE_NAME : name;
    }

    // =========================================================================
    // Runtime State
    // =========================================================================

    /** Maps StarExec execution IDs to Kubernetes Job names */
    private final Map<Integer, String> execIdToJobName =
        new ConcurrentHashMap<>();

    /**
     * Maps StarExec execution IDs to the UID of the Kubernetes Job they were submitted as.
     *
     * <p>Written wherever {@code execIdToJobName} is, and read only to decide whether an
     * incoming event belongs to the execution currently occupying that id. Kubernetes assigns
     * the UID at creation and never reuses it, so it is the only component of the identity
     * that separates two Jobs -- the name does not: {@code generateJobName} repeats every
     * hundred seconds for a given execution id.
     *
     * <p>An entry can be absent while the name entry is present, for tracking rebuilt from a
     * Job that carried no UID. That reads as "identity unknown", never as "matches".
     */
    private final Map<Integer, String> execIdToJobUid =
        new ConcurrentHashMap<>();

    /** Maps StarExec execution IDs to StarExec pair IDs */
    private final Map<Integer, Integer> execIdToPairId =
        new ConcurrentHashMap<>();

    /** Maps StarExec execution IDs to output directories */
    private final Map<Integer, Path> execIdToOutputDir =
        new ConcurrentHashMap<>();

    /** Execution ID generator */
    private int nextExecId = 1;

    /** Lock for ID generation */
    private final Object idLock = new Object();

    /** Kubernetes client instance */
    private KubernetesClient kubernetesClient;

    /** Job monitor */
    private KubernetesJobMonitor jobMonitor;

    /** Flag indicating if backend is initialized */
    private volatile boolean initialized = false;

    /** Flag set during graceful shutdown to reject new submissions */
    private volatile boolean shuttingDown = false;

    /**
     * How long the schedulable-queue view may be reused before it is refetched.
     *
     * <p>Short, because it gates dispatch: a queue that has just come back should not wait
     * long to be used. But not zero, because the check is consulted once per queue per
     * scheduling pass and per pair on submission, and an uncached lookup would put a node
     * listing on the API server for every one of those.
     */
    private static final long QUEUE_VIEW_TTL_MS = 10_000L;

    /** Queue label values that currently have at least one schedulable node. */
    private volatile Set<String> schedulableQueues = Collections.emptySet();

    /** Queue label values present on any worker node, schedulable or not. */
    private volatile Set<String> labelledQueues = Collections.emptySet();

    /**
     * Largest allocatable CPU, in millicores, among the schedulable nodes of each queue.
     *
     * <p>Recomputed by the same node listing that fills {@link #schedulableQueues}, so the
     * capacity gate costs no extra API call. A queue absent from this map has no
     * schedulable node and is handled by the existing queue-view branches.
     */
    private volatile Map<String, Long> queueMaxAllocatableCpuMillis = Collections.emptyMap();

    /** Human-readable reason the CPU capacity gate is holding dispatch, or "" when clear. */
    private volatile String cpuCapacityBlockDetail = "";

    private volatile long queueViewRefreshedAt = 0L;

    /**
     * Whether a node listing has ever succeeded.
     *
     * <p>Without it, "no node carries this queue" and "we have not managed to look yet"
     * are the same empty set. The first is a permanent misconfiguration and is answered
     * with a terminal rejection; the second is a transient failure, and answering it that
     * way fails every pair dispatched in the window. The refresh keeps a stale view on
     * error, which covers every case except the one where there is no previous view --
     * startup.
     */
    private volatile boolean queueViewLoaded = false;

    private final Object queueViewLock = new Object();

    /** Hard concurrency cap to prevent unbounded K8s Job creation */
    private int maxConcurrentJobs = 50;

    /** Periodic orphan sweep interval in milliseconds */
    private int orphanSweepIntervalMs = 300000;

    /**
     * Source of "now", replaceable by a test rather than slept through. Mirrors
     * {@code KubernetesJobMonitor.clock}; production never replaces it.
     */
    private volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    /** Tracks active K8s Jobs against the concurrency cap */
    private final AtomicInteger activeJobCount = new AtomicInteger(0);

    /** Exec IDs currently holding a concurrency slot */
    private final Set<Integer> jobsHoldingSlot = ConcurrentHashMap.newKeySet();

    /**
     * Executions explicitly stopped, so their own late callbacks can be discarded.
     *
     * <p>Keyed on the concrete Kubernetes object, not on the execution id. Keyed on the id
     * this set suppressed a live Job on 2026-09-05: the restart safety gate stopped
     * historical execution 2 at 05:20:39, the counter came back round to 2 at 05:55:48, and
     * the running and completion callbacks of the Job created then were both discarded
     * against the earlier execution's marker. The pair ran to completion and its result was
     * never ingested.
     */
    private final Set<ExecutionRef> killedExecutions = ConcurrentHashMap.newKeySet();

    /**
     * Execution ids stopped without a Kubernetes object identity to record it against.
     *
     * <p>Only {@link #killPairConfirmed} reaches this, and only in the branch where nothing
     * about the execution is tracked any more -- the state a restart leaves behind. What was
     * established there is that no controller and no pod for that <em>label</em> survives; it
     * is not evidence about a Job created afterwards, and this set is deliberately unable to
     * express that it is.
     *
     * <p>So a tombstone here never suppresses anything. It is kept because it is the only
     * record that a stop was requested for an execution nobody could identify, and it is
     * logged when a later execution inherits the number, which is the operator's cue that the
     * two are being told apart rather than conflated.
     */
    private final Set<Integer> legacyKilledExecIds = ConcurrentHashMap.newKeySet();

    /**
     * Guards the compound (jobsHoldingSlot, activeJobCount) reservation so the set and
     * the counter can never disagree.
     */
    private final Object slotLock = new Object();

    // inventoryHeldExecIds used to sit here and has been removed rather than kept: a Set
    // that was only ever REMOVED from. Its javadoc described an orphan inventory that owned
    // the slots it reserved, but nothing ever added to it, so it distinguished nothing.
    // Ownership is now expressed by unverifiedExecutions below, which is genuinely written
    // and genuinely read.
    //
    // A field whose javadoc asserts a behaviour it does not have is worse than no field: it
    // reads as a safety mechanism during review and provides nothing at runtime.

    /**
     * Whether admission must defer because a managed pod exists that StarExec cannot account
     * for.
     *
     * <p>Reinstated deliberately, and this time with the behaviour its predecessor's javadoc
     * only claimed. That field was removed on the reasoning that an unaccounted-for pod
     * becomes a held slot through {@link #restoreSubmissionSlot(int, boolean)}, so ordinary
     * capacity accounting already defers admission. That holds only for a pod whose
     * {@code exec-id} label parses. A managed pod whose identity cannot be recovered has no
     * execution id to hold a slot with, and {@link PodPhaseView#of} dropped it from the view
     * altogether — so it occupied hardware while being invisible to both accounting and
     * admission.
     *
     * <p>Set only by a listing that <em>succeeded</em> and found such a pod; cleared only by
     * a listing that succeeded and found none. An unreadable listing leaves it exactly as it
     * was, so a transient API error neither stops dispatch nor clears a real condition —
     * which was the specific objection that retired the original field.
     */
    private final AtomicBoolean admissionDegraded = new AtomicBoolean(false);

    /** What is currently holding admission, for the operator-visible message. */
    private volatile String admissionDegradedDetail = "";

    /**
     * Executions whose safety could not be established, and which therefore still hold
     * accounting that something must eventually release.
     *
     * <p>This exists because the field above never had anything added to it. Its javadoc
     * named an inventory as the retry owner for held slots, but no such inventory was ever
     * built, so every {@code UNPROVEN} hold survived until the JVM restarted — an accounting
     * leak that shrinks admission capacity for the lifetime of the process.
     *
     * <p>The owner is {@link #sweepOrphanedKubernetesJobs}, which already runs on a timer and
     * already lists managed Jobs. It re-evaluates each entry with
     * {@link #observeExecutionSafety(int)} and releases only on {@code CONFIRMED_SAFE}. No new
     * scheduler is introduced.
     *
     * <p>In-memory, so it does not survive a restart. That gap belongs to restart
     * reconciliation, which reconstructs obligations from durable state plus cluster
     * observation; a second recovery path here would diverge from it.
     */
    private final Map<Integer, UnverifiedExecution> unverifiedExecutions =
        new ConcurrentHashMap<>();

    /** Why an execution is unverified, and since when. */
    private static final class UnverifiedExecution {
        private final long firstSeenMillis;
        private final String reason;
        /**
         * Whether some other retry owner is still waiting to finish an operation on this
         * execution — today, the monitor's cleanup-pending record for a stuck-Pending pair
         * whose callback returned false.
         *
         * <p>The safety sweep must not touch such an entry. "This execution is now safe"
         * and "the operation that was in flight completed" are different facts, and only
         * the continuation can establish the second.
         */
        private final boolean continuationOutstanding;

        private UnverifiedExecution(
            long firstSeenMillis,
            String reason,
            boolean continuationOutstanding
        ) {
            this.firstSeenMillis = firstSeenMillis;
            this.reason = reason;
            this.continuationOutstanding = continuationOutstanding;
        }

        @Override
        public String toString() {
            return reason + " (unverified for " +
                ((System.currentTimeMillis() - firstSeenMillis) / 1000) + "s" +
                (continuationOutstanding ? ", continuation outstanding" : "") + ")";
        }
    }

    /**
     * Registers an execution whose safety is unproven, preserving the earliest observation.
     *
     * <p>The first sighting is kept rather than overwritten so the age in the log is how long
     * the obligation has been outstanding, not how long since it was last re-checked — which
     * would reset on every sweep and never look old.
     */
    private void recordUnverified(int execId, String reason) {
        recordUnverified(execId, reason, false);
    }

    /**
     * As above, but records whether an existing retry owner still has work to finish for this
     * execution.
     *
     * <p>Uses {@code compute} rather than {@code putIfAbsent} so that a later sighting can
     * raise the flag on an entry recorded earlier without it, while still keeping the
     * original {@code firstSeenMillis}. The flag is only ever raised here, never lowered:
     * the continuation itself clears the whole record when it completes.
     */
    private void recordUnverified(int execId, String reason, boolean continuationOutstanding) {
        final boolean[] firstSighting = { false };
        unverifiedExecutions.compute(execId, (id, existing) -> {
            if (existing == null) {
                firstSighting[0] = true;
                return new UnverifiedExecution(
                    System.currentTimeMillis(), reason, continuationOutstanding
                );
            }
            if (continuationOutstanding && !existing.continuationOutstanding) {
                return new UnverifiedExecution(
                    existing.firstSeenMillis, existing.reason, true
                );
            }
            return existing;
        });
        if (firstSighting[0]) {
            log.info("Recorded unverified execution " + execId + ": " + reason);
        }
    }

    /** Periodic cleanup task that deletes orphaned managed K8s Jobs */
    private ScheduledExecutorService orphanSweepExecutor;

    // =========================================================================
    // Configuration (loaded from environment)
    // =========================================================================

    private String namespace;
    private String jobImage;
    /**
     * Empty unless an operator sets one, and empty means "say nothing", which is not the
     * same as choosing a policy: Kubernetes then applies its own default, and that default
     * is derived from the image reference -- {@code Always} for a {@code :latest} tag,
     * {@code IfNotPresent} for anything else, a digest reference included.
     *
     * <p>That derivation is why the workers' offline contract rests on the reference form.
     * Compute nodes are isolated from the public registry, so a floating {@code :latest}
     * asks them to re-resolve an image they cannot reach, while a digest both pins the
     * bytes and selects {@code IfNotPresent}. Setting this explicitly does not change that
     * outcome; it makes the intended contract visible and survives a future change of the
     * image reference.
     */
    private String jobImagePullPolicy;
    private String dataPvcName;
    private String dataPvcAccessMode;
    private String serviceAccountName;
    private String appNodeName;
    private String queueLabelKey;
    private String memoryLimit;
    private String cpuLimit;
    private int ttlSecondsAfterFinished;
    private int backoffLimit;
    private boolean strictOnePairPerCpu;
    private int pendingWarnMinutes;
    private int pendingTimeoutMinutes;

    /** Optional node selector key for worker nodes */
    private String workerNodeSelectorKey;

    /** Optional node selector value for worker nodes */
    private String workerNodeSelectorValue;

    // =========================================================================
    // Constructor
    // =========================================================================

    public KubernetesNativeBackend() {
        log.info("KubernetesNativeBackend instantiated");
    }

    // =========================================================================
    // Backend Interface Implementation
    // =========================================================================

    /**
     * Initialize the Kubernetes backend.
     *
     * <p>This method:</p>
     * <ul>
     *   <li>Loads configuration from environment variables</li>
     *   <li>Creates the Kubernetes client (in-cluster or kubeconfig)</li>
     *   <li>Validates cluster connectivity</li>
     *   <li>Starts the job monitor</li>
     * </ul>
     *
     * @param BACKEND_ROOT Ignored for Kubernetes backend
     */
    @Override
    public void initialize(String BACKEND_ROOT) {
        log.info("Initializing KubernetesNativeBackend...");

        // Load configuration from environment
        loadConfiguration();

        try {
            kubernetesClient = new KubernetesClientBuilder().build();
            log.info(
                "Connected to Kubernetes API at " +
                kubernetesClient.getConfiguration().getMasterUrl()
            );

            ensureNamespaceAccessible();

            reconcileOrphanedPairs();

            jobMonitor = new KubernetesJobMonitor(
                kubernetesClient,
                namespace,
                new KubernetesJobCompletionCallback(),
                pendingWarnMinutes,
                pendingTimeoutMinutes
            );
            jobMonitor.start();

            startOrphanSweepScheduler();

            initialized = true;
            log.info("KubernetesNativeBackend initialized successfully");
        } catch (Exception e) {
            initialized = false;
            log.error("Failed to initialize KubernetesNativeBackend", e);
            throw new RuntimeException("KubernetesNativeBackend initialization failed", e);
        }
    }

    /**
     * Load configuration from environment variables.
     */
    private void loadConfiguration() {
        namespace = getEnv("STAREXEC_K8S_NAMESPACE", DEFAULT_NAMESPACE);
        jobImage = getEnv(
            "STAREXEC_K8S_JOB_IMAGE",
            EnvironmentConfig.getContainerJobImage()
        );
        jobImagePullPolicy = getEnv("STAREXEC_K8S_JOB_IMAGE_PULL_POLICY", "");
        dataPvcName = getEnv("STAREXEC_K8S_DATA_PVC", "starexec-data");
        dataPvcAccessMode = getEnv("STAREXEC_K8S_DATA_PVC_ACCESS_MODE", "ReadWriteMany");
        serviceAccountName = getEnv(
            "STAREXEC_K8S_SERVICE_ACCOUNT",
            "starexec-job"
        );
        appNodeName = getEnv("STAREXEC_K8S_APP_NODE_NAME", "");
        queueLabelKey = getEnv("STAREXEC_K8S_QUEUE_LABEL", DEFAULT_QUEUE_LABEL);
        memoryLimit = getEnv("STAREXEC_K8S_MEMORY_LIMIT", "2Gi");
        cpuLimit = getEnv("STAREXEC_K8S_CPU_LIMIT", "1");
        ttlSecondsAfterFinished = getEnvInt("STAREXEC_K8S_JOB_TTL_SECONDS", 3600);
        backoffLimit = getEnvInt("STAREXEC_K8S_JOB_BACKOFF_LIMIT", 0);
        maxConcurrentJobs = getEnvInt("STAREXEC_K8S_MAX_CONCURRENT_JOBS", 50);
        if (maxConcurrentJobs < 1) {
            log.warn(
                "Invalid STAREXEC_K8S_MAX_CONCURRENT_JOBS value: " +
                maxConcurrentJobs +
                ". Falling back to 1."
            );
            maxConcurrentJobs = 1;
        }
        orphanSweepIntervalMs = getEnvInt("STAREXEC_K8S_ORPHAN_SWEEP_INTERVAL_MS", 300000);
        if (orphanSweepIntervalMs < 0) {
            log.warn(
                "Invalid STAREXEC_K8S_ORPHAN_SWEEP_INTERVAL_MS value: " +
                orphanSweepIntervalMs +
                ". Falling back to 0 (disabled)."
            );
            orphanSweepIntervalMs = 0;
        }
        strictOnePairPerCpu = getEnvBoolean(
            "STAREXEC_K8S_STRICT_ONE_PAIR_PER_CPU",
            true
        );
        // How long a pod may wait to start before the pair is reported, and then failed
        // for rerun. Setting the timeout to 0 leaves the monitor reporting only.
        pendingWarnMinutes = getEnvInt(
            "STAREXEC_K8S_PENDING_WARN_MINUTES",
            KubernetesJobMonitor.DEFAULT_PENDING_WARN_MINUTES
        );
        pendingTimeoutMinutes = getEnvInt(
            "STAREXEC_K8S_PENDING_TIMEOUT_MINUTES",
            KubernetesJobMonitor.DEFAULT_PENDING_TIMEOUT_MINUTES
        );

        workerNodeSelectorKey = getEnv("STAREXEC_K8S_WORKER_SELECTOR_KEY", WORKER_LABEL);
        workerNodeSelectorValue = getEnv("STAREXEC_K8S_WORKER_SELECTOR_VALUE", "true");

        // Academic reproducibility policy: a job pair must get whole physical cores that
        // nothing else runs on, so its measurements are not perturbed by a neighbour.
        //
        // This used to force cpuLimit to "1" whenever the flag was set, discarding the
        // operator's configured value with only a log line. Production sets
        // STAREXEC_K8S_CPU_LIMIT=32 (a whole compute node) together with this flag, so
        // the intent -- one pair per node -- was silently replaced by a one-CPU request,
        // and what actually kept pairs off each other was the 250Gi memory request
        // exhausting the node. Isolation by accident, and it disappears the moment the
        // memory limit is lowered.
        //
        // The flag now means what its name says: assert that the request can yield
        // exclusive cores, rather than shrink it to one. Kubernetes grants exclusive CPUs
        // only for a Guaranteed pod whose cpu request is a whole number, and only when
        // the kubelet runs --cpu-manager-policy=static. Requests already equal limits in
        // buildKubernetesJob, so the remaining requirement is the integer.
        if (strictOnePairPerCpu) {
            cpuLimit = resolveCpuLimitForStrictMode(cpuLimit);
            // Not detectable from the API -- kubelet configuration is not exposed on the
            // Node object -- so it is stated rather than checked. Without it the request
            // below is a quota and the pinning is a fiction.
            log.info(
                "STAREXEC_K8S_STRICT_ONE_PAIR_PER_CPU=true with cpu=" + cpuLimit +
                ". This grants exclusive cores ONLY if worker nodes run kubelet with" +
                " --cpu-manager-policy=static. Without it Kubernetes applies a CFS" +
                " bandwidth quota instead, solver threads float across all cores, and" +
                " recorded timings are perturbed by co-scheduled work."
            );
        }

        log.info(
            "K8s Configuration: namespace=" +
                namespace +
                ", image=" +
                jobImage +
                ", pvc=" +
                dataPvcName +
                " (" +
                dataPvcAccessMode +
                ")" +
                ", cpu=" +
                cpuLimit +
                ", memory=" +
                memoryLimit +
                ", appNode=" +
                appNodeName +
                ", ttlSeconds=" +
                ttlSecondsAfterFinished +
                ", maxConcurrentJobs=" +
                maxConcurrentJobs +
                ", orphanSweepIntervalMs=" +
                orphanSweepIntervalMs +
                ", strictOnePairPerCpu=" +
                strictOnePairPerCpu
        );
    }

    /**
     * Helper to get environment variable with default.
     */
    private String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    /**
     * The pull policy to stamp on the job container, or {@code null} to leave the field
     * absent so Kubernetes applies its own image-reference-derived default.
     *
     * <p>Returning {@code null} rather than a fabricated default is deliberate: an
     * unconfigured deployment must keep exactly the behaviour it had before this setting
     * existed.
     */
    private String configuredImagePullPolicy() {
        return (jobImagePullPolicy == null || jobImagePullPolicy.isEmpty())
            ? null
            : jobImagePullPolicy;
    }

    private int getEnvInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid integer for env " + key + ": '" + value + "'. Using default " + defaultValue);
            return defaultValue;
        }
    }

    private boolean getEnvBoolean(String key, boolean defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * Returns the CPU quantity strict mode should use — which is the configured one,
     * unchanged.
     *
     * <p>The return value is the point of this method. Strict mode previously *replaced*
     * the operator's value with {@code "1"}, so a deployment asking for a whole 32-CPU
     * node silently got one CPU. That the value passes through untouched is now an
     * asserted contract rather than merely the absence of an assignment, which is
     * something a test can hold on to: a test that only inspects the generated pod spec
     * cannot see an assignment made during configuration loading.
     *
     * @throws IllegalStateException if the quantity could never yield exclusive cores
     */
    static String resolveCpuLimitForStrictMode(String configuredCpuLimit) {
        if (!isWholeNumberCpuQuantity(configuredCpuLimit)) {
            throw new IllegalStateException(
                "STAREXEC_K8S_STRICT_ONE_PAIR_PER_CPU=true requires" +
                " STAREXEC_K8S_CPU_LIMIT to be a whole number of CPUs, but it is '" +
                configuredCpuLimit + "'. Kubernetes only assigns exclusive cores to a" +
                " Guaranteed pod requesting integer CPUs; a fractional request is a" +
                " bandwidth quota and the solver would float across the node's cores," +
                " perturbing its own measurements and its neighbours'."
            );
        }
        return configuredCpuLimit;
    }

    /**
     * True if a Kubernetes CPU quantity denotes a whole number of CPUs.
     *
     * <p>This is the condition Kubernetes requires before it will assign exclusive cores:
     * the CPU Manager's static policy only pins a Guaranteed pod whose cpu request is an
     * integer. {@code "2"} qualifies; {@code "1500m"}, {@code "0.5"} and {@code "2.5"} do
     * not, and such a pod receives a bandwidth quota instead, floating across the node.
     *
     * <p>{@code "2000m"} is accepted because milli-CPU is exact here — 2000m is two whole
     * CPUs — and rejecting a legitimate spelling would be a trap rather than a guard.
     *
     * @param quantity the raw value of STAREXEC_K8S_CPU_LIMIT
     */
    static boolean isWholeNumberCpuQuantity(String quantity) {
        if (quantity == null) {
            return false;
        }
        String value = quantity.trim();
        if (value.isEmpty()) {
            return false;
        }
        try {
            if (value.endsWith("m")) {
                long milli = Long.parseLong(value.substring(0, value.length() - 1));
                return milli > 0 && milli % 1000 == 0;
            }
            // Reject "2.0" as well as "2.5": a decimal point in a cpu quantity is a
            // signal that someone is thinking in fractions, and the next edit is likely
            // to make it fractional. Integers only.
            if (value.indexOf('.') >= 0) {
                return false;
            }
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Parses a Kubernetes CPU quantity into millicores, or {@code -1} if it cannot be read.
     *
     * <p>Comparing CPU quantities as strings is wrong in both directions — {@code "2"} is
     * larger than {@code "1500m"} but sorts before it, and {@code "32"} sorts before
     * {@code "4"}. Everything that compares capacity goes through this method so the
     * comparison is numeric.
     *
     * <p>{@code -1} means "unreadable", never "zero": callers must treat it as unknown and
     * fail closed rather than concluding the node is small.
     */
    static long cpuQuantityToMillis(String quantity) {
        if (quantity == null || quantity.trim().isEmpty()) {
            return -1L;
        }
        try {
            java.math.BigDecimal cores = Quantity.getAmountInBytes(new Quantity(quantity.trim()));
            if (cores == null) {
                return -1L;
            }
            return cores.multiply(java.math.BigDecimal.valueOf(1000L)).longValue();
        } catch (Exception e) {
            return -1L;
        }
    }

    private void ensureNamespaceAccessible() {
        if (kubernetesClient.namespaces().withName(namespace).get() == null) {
            throw new IllegalStateException(
                "Kubernetes namespace does not exist or is inaccessible: " + namespace
            );
        }
    }

    /**
     * Clean up resources when shutting down.
     *
     * <p>Shutdown ordering follows the same pattern as PodmanBackend:</p>
     * <ol>
     *   <li>Stop accepting new submissions</li>
     *   <li>Run one final poll for terminal K8s Jobs</li>
     *   <li>Stop the job monitor</li>
     *   <li>Close the Kubernetes client</li>
     *   <li>Clear in-memory tracking maps</li>
     *   <li><b>Do not</b> delete managed K8s Jobs —
     *       startup reconciliation will recover them</li>
     * </ol>
     */
    @Override
    public void destroyIf() {
        log.info("Shutting down KubernetesNativeBackend...");

        // 1. Stop accepting new submissions so no K8s Jobs are created during
        //    shutdown. Any submitScript() that sees shuttingDown after this
        //    point returns -1 immediately.
        shuttingDown = true;

        // 2. Run one final poll for terminal K8s Jobs so any Job that
        //    completed just before shutdown gets its DB update.
        if (jobMonitor != null && kubernetesClient != null) {
            try {
                log.info("Running final completed-K8s-Job scan before shutdown...");
                drainTerminalJobs();
            } catch (Exception e) {
                log.warn("Error during final K8s job scan before shutdown", e);
            }
        }

        // 3. Stop the monitor.
        if (jobMonitor != null) {
            try {
                jobMonitor.stop();
                log.info("KubernetesJobMonitor stopped");
            } catch (Exception e) {
                log.warn("Error stopping KubernetesJobMonitor", e);
            }
        }

        if (orphanSweepExecutor != null) {
            orphanSweepExecutor.shutdownNow();
            orphanSweepExecutor = null;
        }

        // 4. Close the Kubernetes client.
        //    K8s Jobs are left in place so startup reconciliation can find
        //    and recover them on the next boot.
        if (kubernetesClient != null) {
            try {
                kubernetesClient.close();
                kubernetesClient = null;
                log.info("KubernetesNativeBackend destroyed successfully (K8s Jobs preserved for recovery).");
            } catch (Exception e) {
                log.warn("Error while closing Kubernetes client", e);
            }
        }

        // 5. Clear tracking maps and release concurrency slots.
        releaseAllSlots();
        execIdToJobName.clear();
        execIdToJobUid.clear();
        execIdToPairId.clear();
        execIdToOutputDir.clear();
        initialized = false;
        log.info("KubernetesNativeBackend shut down");
    }

    /**
     * Starts the periodic orphan sweep that deletes managed K8s Jobs whose
     * DB pair rows have been removed while the backend is running.
     */
    private void startOrphanSweepScheduler() {
        if (orphanSweepIntervalMs <= 0) {
            log.info("Kubernetes orphan sweep disabled");
            return;
        }

        orphanSweepExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "k8s-orphan-sweep");
            t.setDaemon(true);
            return t;
        });

        orphanSweepExecutor.scheduleAtFixedRate(
            this::sweepOrphanedKubernetesJobs,
            orphanSweepIntervalMs,
            orphanSweepIntervalMs,
            TimeUnit.MILLISECONDS
        );
        log.info(
            "Scheduled Kubernetes orphan sweep every " +
            orphanSweepIntervalMs +
            " ms"
        );
    }

    /**
     * Deletes managed K8s Jobs whose DB pair rows have disappeared.
     * This keeps the cluster from accumulating orphaned Jobs when pairs are
     * deleted while the application remains running.
     */
    private void sweepOrphanedKubernetesJobs() {
        if (!initialized || kubernetesClient == null || shuttingDown) {
            return;
        }

        // Settle any submission whose create() outcome was never established. Hosted here
        // rather than in a scheduler of its own: this sweep already runs periodically on
        // the Kubernetes side and already holds the client. Run first, so a resolution
        // that releases a reservation is reflected in the rest of this pass.
        try {
            resolveAmbiguousSubmissions();
        } catch (Exception e) {
            log.error("Failed to resolve ambiguous submissions", e);
        }

        try {
            JobList jobList = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(MANAGED_LABEL, "true")
                .list();

            int deleted = 0;
            for (Job job : jobList.getItems()) {
                Integer pairId = extractPairId(job);
                Integer execId = extractExecId(job);
                if (pairId == null || execId == null) {
                    continue;
                }

                JobPairs.PairStatusLookupResult lookup =
                    JobPairs.getPairStatusLookup(pairId);
                if (!lookup.isMissing()) {
                    continue;
                }

                deleteKubernetesJob(job);

                // deleteKubernetesJob returns void, so the request having been issued says
                // nothing about whether anything stopped. This site used to release the slot
                // and clear tracking on the strength of that call alone, taking no census and
                // consulting no controller -- the weakest release path in the class, on
                // objects that by definition nobody is watching.
                if (observeExecutionSafety(execId) != KillOutcome.CONFIRMED_SAFE) {
                    log.warn(
                        "Orphan sweep deleted Job " + getJobName(job) + " for execId " + execId +
                            " but cannot establish that it stopped; accounting retained."
                    );
                    recordUnverified(execId, "orphan sweep of " + getJobName(job));
                    continue;
                }

                recordExecutionStopped(
                    ExecutionRef.fromJob(execId, job),
                    execId,
                    "orphan sweep of " + getJobName(job)
                );
                execIdToJobName.remove(execId);
                execIdToJobUid.remove(execId);
                execIdToPairId.remove(execId);
                execIdToOutputDir.remove(execId);
                releaseSubmissionSlot(execId);
                unverifiedExecutions.remove(execId);
                deleted++;
            }

            if (deleted > 0) {
                log.info("Kubernetes orphan sweep deleted " + deleted + " orphaned Job(s)");
            }
        } catch (Exception e) {
            log.warn("Kubernetes orphan sweep failed", e);
        }

        // Give every outstanding unverified execution another chance to be resolved. Without
        // this, an UNPROVEN hold is immortal: the field it used to be recorded in was never
        // written, so nothing ever revisited it and the slot stayed reserved until restart.
        try {
            revisitUnverifiedExecutions();
        } catch (Exception e) {
            log.warn("Failed to revisit unverified executions", e);
        }

        try {
            inventoryPodsWithoutJobs();
        } catch (Exception e) {
            log.warn("Failed to inventory pods without Jobs", e);
        }
    }

    /**
     * Finds managed pods whose Job no longer exists, and brings them into the accounting.
     *
     * <p>The sweep above lists Jobs, so a pod that outlived its owner is invisible to it —
     * and that pod is the one most likely to be forgotten, because nothing else enumerates
     * it either. Deleting a Job with Background propagation removes the owner first and reaps
     * dependents afterwards, so a crash in that window leaves exactly this state; startup
     * reconciliation now refuses to transition such a pair, which makes discovering it here
     * the thing that eventually unblocks it.
     *
     * <p>Only accounting is touched. No DB transition is performed and no pod is deleted —
     * this backend deliberately holds no pod-delete privilege, and the pair's status is not
     * this pass's to decide.
     */
    private void inventoryPodsWithoutJobs() {
        if (kubernetesClient == null) {
            return;
        }
        PodPhaseView pods = PodPhaseView.list(
            kubernetesClient, namespace, MANAGED_LABEL, EXEC_ID_LABEL
        );
        if (pods == null || !pods.isAvailable()) {
            // An unreadable listing is not an empty one. Saying nothing is the only honest
            // outcome; the next pass tries again.
            return;
        }
        for (int execId : pods.execIds()) {
            if (jobsHoldingSlot.contains(execId) || unverifiedExecutions.containsKey(execId)) {
                continue;   // already accounted for
            }
            if (observeExecutionSafety(execId) == KillOutcome.CONFIRMED_SAFE) {
                continue;   // nothing of it can run; nothing owed
            }
            log.warn(
                "Inventory found execution " + execId + " with a live pod that nothing was" +
                    " accounting for; reserving a slot for it so unrelated pairs are not" +
                    " scheduled beside it."
            );
            restoreSubmissionSlot(execId, true);
            recordUnverified(execId, "pod discovered without matching accounting");
        }

        // Managed pods whose execution identity could not be recovered. No execution id may
        // be invented for them and none may be deleted automatically, so the only correct
        // response is to stop admitting new work until the object is understood or gone.
        List<PodPhaseView.UnidentifiedPod> unaccountable = pods.unsafeUnidentifiedPods();
        if (!unaccountable.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            for (PodPhaseView.UnidentifiedPod pod : unaccountable) {
                if (detail.length() > 0) {
                    detail.append("; ");
                }
                detail.append(pod);
            }
            admissionDegradedDetail = detail.toString();
            if (admissionDegraded.compareAndSet(false, true)) {
                log.error(
                    "Admission deferred: " + unaccountable.size() + " managed pod(s) may" +
                        " still be running but carry no usable " + EXEC_ID_LABEL +
                        " label, so no slot can be reserved for them. OPERATOR ACTION:" +
                        " inspect and resolve " + detail
                );
            } else {
                log.warn("Admission still deferred: " + detail);
            }
            return;
        }
        // Only a listing that succeeded gets here, so this clears the condition on evidence
        // rather than on silence.
        if (admissionDegraded.compareAndSet(true, false)) {
            admissionDegradedDetail = "";
            log.info(
                "Admission restored: a successful pod inventory found no unaccountable" +
                    " managed pods."
            );
        }
    }

    /**
     * Re-evaluates every execution whose safety was previously unproven.
     *
     * <p>Releases accounting only on {@code CONFIRMED_SAFE}. This is the transition a
     * pod-only design could not make: a pod disappearing is not by itself the end of the
     * obligation, because the Job that owned it may simply be between attempts. Both halves
     * are re-checked every pass.
     *
     * <p>Releasing the hold is <em>all</em> this does. Whatever operation was originally in
     * flight — a rerun, an administrative kill — is not resumed here. "The execution is now
     * safe" and "the requested operation completed" are separate facts, and this pass can
     * only ever establish the first.
     */
    private void revisitUnverifiedExecutions() {
        if (unverifiedExecutions.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, UnverifiedExecution> entry : unverifiedExecutions.entrySet()) {
            int execId = entry.getKey();
            UnverifiedExecution record = entry.getValue();
            if (observeExecutionSafety(execId) != KillOutcome.CONFIRMED_SAFE) {
                log.info("Execution " + execId + " is still unverified: " + record);
                continue;
            }
            if (record.continuationOutstanding) {
                // Safety is established, but an operation is still in flight and its retry
                // owner needs this tracking to finish. Releasing here strands the pair: the
                // continuation resolves its pair id from execIdToPairId, and the Job it
                // would otherwise fall back to has already been deleted. Leave everything
                // in place — the continuation calls releaseAccountingIfSafe when it
                // completes, and that now succeeds because safety is established.
                log.info(
                    "Execution " + execId + " is now confirmed safe (" + record + ") but a" +
                        " continuation still owns its completion; retaining its tracking."
                );
                continue;
            }
            log.info(
                "Execution " + execId + " is now confirmed safe (" + record +
                    "); releasing its retained accounting."
            );
            execIdToJobName.remove(execId);
            execIdToJobUid.remove(execId);
            execIdToPairId.remove(execId);
            execIdToOutputDir.remove(execId);
            ambiguousSubmissions.remove(execId);
            // Deliberately NOT recordExecutionStopped. That set means "explicitly
            // killed, so ignore any callback for it", and every terminal callback
            // short-circuits on it and returns true. Marking an execution killed merely
            // because it became safe cancelled whatever callback was still outstanding:
            // the monitor treated the work as handled, dropped its cleanup-pending record,
            // and the pair's terminal DB transition was never written.
            releaseSubmissionSlot(execId);
            unverifiedExecutions.remove(execId);
        }
    }

    /**
     * Runs one final poll for terminal K8s Jobs before shutdown, so any
     * Job that completed just before teardown gets its DB update processed.
     */
    private void drainTerminalJobs() {
        try {
            List<Job> jobs = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(MANAGED_LABEL, "true")
                .list()
                .getItems();

            int processed = 0;
            KubernetesJobCompletionCallback callback =
                new KubernetesJobCompletionCallback();

            for (Job job : jobs) {
                Integer execId = extractExecId(job);
                ExecutionRef execution =
                    (execId == null) ? null : ExecutionRef.fromJob(execId, job);
                if (execution == null) {
                    continue;
                }

                // Only process jobs whose controller is spent — anything still able to
                // create a Pod is left for reconciliation on next startup.
                //
                // This used to dispatch on isSucceededJob/isFailedJob, which accept the Pod
                // counters. A Job at failed == 1 with backoffLimit > 0 would have had a
                // terminal pair status and an end_time published for it here, during
                // shutdown, while the controller was still due to start the next attempt.
                if (!isControllerSpent(job)) {
                    continue;
                }
                if (hasTrueCondition(job, "Complete")) {
                    if (callback.onJobComplete(execution)) {
                        processed++;
                    }
                } else {
                    if (callback.onJobFailed(execution, summarizeJobFailure(job))) {
                        processed++;
                    }
                }
            }

            if (processed > 0) {
                log.info("Final K8s job scan processed " + processed +
                         " terminal job(s) before shutdown");
            }
        } catch (Exception e) {
            log.error("Failed final K8s job scan before shutdown", e);
        }
    }

    /**
     * Check if an execution code indicates an error.
     *
     * @param execCode The execution code to check
     * @return true if the code indicates an error (negative value)
     */
    @Override
    public boolean isError(int execCode) {
        return execCode <= 0;
    }

    /**
     * Submit a job script for execution as a Kubernetes Job.
     *
     * <p>This method creates a Kubernetes Job resource that:</p>
     * <ul>
     *   <li>Runs the job-runner container image</li>
     *   <li>Mounts the data PVC at the appropriate path</li>
     *   <li>Passes the script path and working directory as arguments</li>
     *   <li>Sets resource limits based on configuration</li>
     * </ul>
     *
     * @param scriptPath Path to the job script (relative to data volume)
     * @param workingDirectoryPath Working directory for the job
     * @param logPath Path for job output logs
     * @return Execution ID (positive) or -1 on error
     */
    /**
     * True if a node can actually receive a pod: uncordoned <em>and</em> Ready.
     *
     * <p>Both conditions are needed. A node that is merely uncordoned but {@code NotReady}
     * — kubelet stopped, disk pressure, a network partition — still accepts no pods, so
     * treating it as available would leave the pod pending exactly as before.
     */
    static boolean isNodeSchedulable(Node node) {
        if (node == null || node.getSpec() == null || node.getStatus() == null) {
            return false;
        }
        if (Boolean.TRUE.equals(node.getSpec().getUnschedulable())) {
            return false;
        }
        List<NodeCondition> conditions = node.getStatus().getConditions();
        if (conditions == null) {
            return false;
        }
        for (NodeCondition condition : conditions) {
            if ("Ready".equals(condition.getType())) {
                return "True".equals(condition.getStatus());
            }
        }
        return false;
    }

    /**
     * A node's allocatable CPU in millicores, or {@code -1} when it cannot be determined.
     *
     * <p>Allocatable, not capacity: capacity is the hardware, allocatable is what the
     * kubelet will actually hand out after its own reservations. Scheduling is decided
     * against allocatable, so that is what the gate compares.
     */
    static long nodeAllocatableCpuMillis(Node node) {
        if (node == null || node.getStatus() == null || node.getStatus().getAllocatable() == null) {
            return -1L;
        }
        Quantity cpu = node.getStatus().getAllocatable().get("cpu");
        if (cpu == null) {
            return -1L;
        }
        return cpuQuantityToMillis(cpu.toString());
    }

    /**
     * Refreshes the queue view if it has aged past {@link #QUEUE_VIEW_TTL_MS}.
     *
     * <p>One node listing serves both questions the routing gate asks — which queues exist
     * at all, and which can take work now — so the two never cost separate API calls.
     */
    private void refreshQueueViewIfStale() {
        if (System.currentTimeMillis() - queueViewRefreshedAt < QUEUE_VIEW_TTL_MS) {
            return;
        }
        synchronized (queueViewLock) {
            // Re-checked inside the lock: several dispatch threads can arrive together
            // and only the first should pay for the refresh.
            if (System.currentTimeMillis() - queueViewRefreshedAt < QUEUE_VIEW_TTL_MS) {
                return;
            }
            try {
                Set<String> labelled = new HashSet<>();
                Set<String> schedulable = new HashSet<>();
                Map<String, Long> maxCpu = new HashMap<>();
                List<Node> nodes = kubernetesClient
                    .nodes()
                    .withLabel(workerNodeSelectorKey, workerNodeSelectorValue)
                    .list()
                    .getItems();
                for (Node node : nodes) {
                    String queue = node.getMetadata() != null && node.getMetadata().getLabels() != null
                        ? node.getMetadata().getLabels().get(queueLabelKey)
                        : null;
                    // A worker with no queue label belongs to the default queue, and so
                    // does one carrying a legacy spelling of it.
                    String queueName = normalizeQueueLabel(queue);
                    labelled.add(queueName);
                    if (isNodeSchedulable(node)) {
                        schedulable.add(queueName);
                        // Only a schedulable node contributes capacity: a cordoned or
                        // NotReady node accepts nothing, so counting its CPUs would let the
                        // gate pass on capacity that cannot be used.
                        long allocatable = nodeAllocatableCpuMillis(node);
                        if (allocatable > 0) {
                            Long previous = maxCpu.get(queueName);
                            if (previous == null || allocatable > previous) {
                                maxCpu.put(queueName, allocatable);
                            }
                        }
                    }
                }
                labelledQueues = Collections.unmodifiableSet(labelled);
                schedulableQueues = Collections.unmodifiableSet(schedulable);
                queueMaxAllocatableCpuMillis = Collections.unmodifiableMap(maxCpu);
                queueViewRefreshedAt = System.currentTimeMillis();
                queueViewLoaded = true;
            } catch (Exception e) {
                // Leave the previous view in place rather than emptying it. Treating an
                // API blip as "no queue can run anything" would stall all dispatch, and
                // treating it as "every queue is fine" would resume the pending-pod
                // black hole. The stale view is the least wrong of the three.
                log.warn("Could not refresh the Kubernetes queue view; reusing the previous one", e);
            }
        }
    }

    @Override
    public boolean isQueueDispatchable(String queueName) {
        if (!initialized || shuttingDown) {
            return false;
        }
        // A managed pod exists that StarExec cannot account for, so it may be occupying a
        // node that no capacity check knows about. Deferring through this channel is
        // deliberate: pairs stay enqueued and dispatch resumes on its own once a successful
        // inventory shows the object is gone. It must never surface as ERROR_SGE_REJECT or
        // ERROR_SUBMIT_FAIL — this is a transient safety hold, not a rejected submission.
        if (admissionDegraded.get()) {
            log.warn(
                "Deferring dispatch for queue '" + queueName + "': admission is degraded" +
                    " because a managed pod cannot be accounted for (" +
                    admissionDegradedDetail + ")"
            );
            return false;
        }
        refreshQueueViewIfStale();
        String name = (queueName == null || queueName.trim().isEmpty())
            ? DEFAULT_QUEUE_NAME
            : queueName.trim();

        // Never seen the cluster. An empty view is not evidence of anything, and the
        // branch below reads it as permanent misconfiguration, so defer instead: the
        // pairs stay enqueued and dispatch resumes as soon as a listing succeeds.
        if (!queueViewLoaded) {
            log.warn(
                "The Kubernetes queue view has never loaded, so whether queue '" + name +
                "' has nodes is unknown; deferring its pairs rather than failing them"
            );
            return false;
        }

        if (schedulableQueues.contains(name)) {
            // Capacity gate. A pod whose cpu request is not strictly smaller than the
            // largest allocatable CPU on any node this queue can use is unschedulable for
            // as long as the configuration stands: no amount of waiting frees capacity that
            // the node never had. Left ungated it produced the incident this guard exists
            // for -- pods Pending for ever, the monitor failing each pair for rerun at its
            // timeout, and the rerun requesting the same impossible size again.
            //
            // Strictly smaller, not equal: the node agents and DaemonSets that every node
            // runs also consume scheduler-visible CPU, so a request exactly equal to
            // allocatable never fits either. This is a static impossibility check, not a
            // capacity reservation -- it deliberately does not model what is free right now.
            long requestMillis = cpuQuantityToMillis(cpuLimit);
            Long maxAllocatable = queueMaxAllocatableCpuMillis.get(name);
            if (requestMillis < 0 || maxAllocatable == null || maxAllocatable <= 0) {
                // Unreadable quantities are not evidence of room. Defer rather than dispatch
                // into a cluster whose capacity could not be established.
                log.warn(
                    "Cannot establish CPU capacity for queue '" + name + "' (request=" +
                        cpuLimit + ", parsed=" + requestMillis + "m, maxAllocatable=" +
                        maxAllocatable + "); deferring dispatch rather than assuming it fits"
                );
                return false;
            }
            if (requestMillis >= maxAllocatable) {
                cpuCapacityBlockDetail =
                    "queue '" + name + "' requests " + cpuLimit + " (" + requestMillis +
                    "m) but its largest schedulable node allocates only " + maxAllocatable + "m";
                log.error(
                    "Impossible CPU configuration: STAREXEC_K8S_CPU_LIMIT=" + cpuLimit +
                        " (" + requestMillis + "m) is not smaller than the largest" +
                        " allocatable CPU on any schedulable node of queue '" + name +
                        "' (" + maxAllocatable + "m). Every pod would stay Pending for ever," +
                        " so dispatch is being held and pairs remain enqueued rather than" +
                        " failed. Remediation: lower STAREXEC_K8S_CPU_LIMIT below " +
                        maxAllocatable + "m (chart key kubernetes.resources.limits.cpu)," +
                        " leaving room for node DaemonSets, or add a node with more CPU."
                );
                return false;
            }
            if (!cpuCapacityBlockDetail.isEmpty()) {
                log.info("CPU capacity gate cleared for queue '" + name + "'");
                cpuCapacityBlockDetail = "";
            }
            return true;
        }
        if (labelledQueues.contains(name)) {
            // Nodes carry this queue but none can take work: a drain, or nodes that have
            // gone NotReady. Transient, so defer rather than fail the pairs.
            log.warn(
                "Queue '" + name + "' has nodes but none are schedulable right now" +
                " (cordoned or NotReady); deferring dispatch"
            );
            return false;
        }
        // No node carries this queue at all. Permanent until someone fixes it, so let the
        // pairs reach submitScript and be rejected there where the failure is visible,
        // rather than silently deferring for ever.
        return true;
    }

    @Override
    public int submitScript(
        int pairId,
        String scriptPath,
        String workingDirectoryPath,
        String logPath,
        String queueName
    ) {
        String name = (queueName == null || queueName.trim().isEmpty())
            ? DEFAULT_QUEUE_NAME
            : queueName.trim();

        // Static misconfiguration: no worker node carries this queue. It will not resolve
        // on its own, so reject the pair rather than let its pod pend for ever with
        // nothing to say why.
        // queueViewLoaded, not just the set contents: an empty view because the listing
        // has never succeeded is not proof that nothing is labelled, and rejecting on it
        // turns a transient API failure at startup into a terminal status on every pair
        // dispatched in that window.
        if (initialized && !shuttingDown) {
            refreshQueueViewIfStale();
            if (queueViewLoaded && !labelledQueues.contains(name)) {
                log.error(
                    "Refusing to submit pair " + pairId + ": no Kubernetes worker node is" +
                    " labelled " + queueLabelKey + "=" + name + ", so a pod for queue '" +
                    name + "' could never be scheduled. Label a node for this queue."
                );
                return -1;
            }
        }

        pendingQueueName.set(name);
        try {
            return submitScript(pairId, scriptPath, workingDirectoryPath, logPath);
        } finally {
            pendingQueueName.remove();
        }
    }

    /**
     * Carries the queue from the five-argument entry point to {@code buildKubernetesJob}.
     *
     * <p>A thread-local rather than a field because dispatch is not guaranteed to be
     * single-threaded, and a field would let one submission's queue leak into another's
     * pod spec — which would be the original routing bug wearing a different hat.
     */
    private final ThreadLocal<String> pendingQueueName = new ThreadLocal<>();

    @Override
    public int submitScript(
        int pairId,
        String scriptPath,
        String workingDirectoryPath,
        String logPath
    ) {
        if (!initialized) {
            log.error("Backend not initialized");
            return -1;
        }

        // Reject new submissions during graceful shutdown so no K8s Jobs
        // are created after the final drain-and-stop sequence.
        if (shuttingDown) {
            log.warn("Rejecting submission for pair " + pairId +
                     " — backend is shutting down");
            return -1;
        }

        int execId = generateExecId();
        String jobName = generateJobName(execId);

        // The AUTHORITATIVE capacity reservation, taken before the Job exists.
        //
        // isQueueDispatchable performs the same check earlier, but it is advisory: two
        // callers can both pass it and only one can have the last slot. This used to be a
        // check-then-act -- activeJobCount.get() here, incrementAndGet() after create() --
        // so the loser created a Job anyway and the cap was a suggestion.
        //
        // A losing caller must DEFER, not fail. Returning -1 makes isError(-1) true and
        // JobManager records a terminal ERROR_SGE_REJECT, which RERUN_FAILED_PAIRS does
        // not even select -- so a full backend would permanently kill blameless pairs, one
        // per pair per 20-second scheduling pass.
        if (!tryAcquireSubmissionSlot(execId)) {
            throw new SubmissionDeferredException(
                "at concurrency cap (" + activeSlotCount() + "/" + maxConcurrentJobs +
                "); pair " + pairId + " stays queued"
            );
        }

        log.info(
            "Submitting K8s Job: execId=" +
                execId +
                ", jobName=" +
                jobName +
                ", script=" +
                scriptPath
        );

        try {
            Job job = buildKubernetesJob(
                pairId,
                execId,
                jobName,
                scriptPath,
                workingDirectoryPath,
                logPath
            );

            // Clear the previous attempt's result artifacts before this Job can write new
            // ones. resolveOutputDirectory derives the directory from the pair's stdout
            // path, so it is keyed by pairId and reused verbatim by every rerun of that
            // pair, and nothing else ever removes these files.
            //
            // This is load-bearing for the runsolver verdict: readTerminalStatus now
            // trusts var.out's TIMEOUT=/MEMOUT= over status.json, so a var.out left by an
            // earlier attempt would outrank the current attempt's freshly written
            // status.json and report EXCEED_CPU for a run that never reached runsolver. A
            // stale status.json or stats.json was already the same hazard before that
            // change, so all four go. Done before create() so the pod cannot race it.
            // Fail CLOSED. If any stale artifact survives, no Job is created.
            //
            // This used to log a warning and submit anyway, which contradicted the very
            // risk the comment above states: the surviving file would be read as this
            // attempt's result and could record a limit breach for a run that never
            // reached runsolver. For a platform whose output is competition results,
            // dispatching and possibly misclassifying is the wrong failure direction.
            //
            // The cost is explicit and accepted: there is no defer channel here --
            // JobManager turns -1 into ERROR_SGE_REJECT and a thrown exception into
            // ERROR_SUBMIT_FAIL, both terminal -- so a genuinely transient filesystem
            // fault terminally rejects the pair instead of retrying it. That is loud and
            // visible to an operator, where a wrong measurement is neither. The usual
            // cause is a persistently unwritable output volume, which is a static
            // misconfiguration and terminal is the right answer for it.
            if (!clearStaleAttemptArtifacts(resolveOutputDirectory(logPath), pairId)) {
                log.error(
                    "Refusing to submit pair " + pairId + " (execId " + execId + "):" +
                    " a previous attempt's classification artifacts could not be removed"
                );
                // No Job was created, so the reservation taken above must go back.
                releaseSubmissionSlot(execId);
                return -1;
            }

            // The created object, not a discarded return value. Its metadata.uid is the
            // only durable way to tell this Job from the next one to be given the same
            // execution id, and it is available nowhere later: by the time a callback or a
            // kill needs it, the Job may already be gone.
            Job created = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .resource(job)
                .create();

            ExecutionRef submitted = ExecutionRef.fromJob(execId, created);
            if (submitted == null) {
                // The API server stamps a UID on everything it accepts, so a response
                // without one is not the created object. Whether a Job exists is exactly
                // what the ambiguous-submission path is for; it is reached by throwing,
                // which is also what keeps the reservation and tracking honest.
                throw new IllegalStateException(
                    "Kubernetes returned no object identity for job " + jobName +
                    " (pair " + pairId + ", execId " + execId + ")"
                );
            }

            try {
                if (!JobPairs.setStartTime(pairId)) {
                    log.warn(
                        "setStartTime found no row for pair " + pairId +
                        " after Kubernetes Job submission"
                    );
                }
            } catch (Exception e) {
                log.warn(
                    "Failed to set start_time for pair " + pairId +
                    " after Kubernetes Job submission",
                    e
                );
            }

            // Order matters. The identity is what grants ownership of everything else keyed
            // on this id, so the pair and the output directory are published FIRST: a
            // callback that arrives between these writes would otherwise own the id while
            // still reading the previous execution's pair.
            execIdToPairId.put(execId, pairId);
            execIdToOutputDir.put(execId, resolveOutputDirectory(logPath));
            execIdToJobName.put(execId, submitted.jobName());
            execIdToJobUid.put(execId, submitted.jobUid());
            // The slot was reserved before create(); nothing to acquire here.
            if (legacyKilledExecIds.remove(execId)) {
                log.info(
                    "Execution id " + execId + " was reused for " + submitted + "; an" +
                    " unidentifiable earlier execution had been stopped under the same" +
                    " number and cannot affect this one."
                );
            }
            log.info("K8s Job submitted successfully: " + jobName);
            return execId;
        } catch (SubmissionDeferredException e) {
            // MUST come first. A deferral means no create was attempted, so it must never
            // be resolved as an ambiguous create outcome. Today the reservation throws
            // above this try, so this cannot fire -- it is here so that a future edit which
            // moves the reservation inside the try fails loudly rather than silently
            // converting a pure capacity race into an invented "established" execution.
            // That would be worst when the API is also down: kubernetesJobExists fails
            // closed to "present", so StarExec would account for a Job it never asked for.
            throw e;
        } catch (Exception e) {
            log.error("Failed to submit Kubernetes Job: " + jobName, e);
            return resolveAmbiguousSubmission(execId, pairId, jobName, logPath);
        }
    }

    /**
     * Decides what a failed {@code create()} actually means, immediately.
     *
     * <p>A Kubernetes create has an uncertain outcome: the client can see an exception
     * after the API server has already persisted the Job. Releasing the reservation and
     * clearing tracking on that basis would leave a real Job and pod running with nothing
     * counting them, and would let the next scheduling pass admit more work beside it.
     * Waiting for the five-minute orphan sweep to repair that is not good enough — the
     * window is the whole point of the invariant.
     *
     * <p>So the outcome is resolved here, fail-closed:
     *
     * <pre>
     *   Job present                  -> the submission DID happen; keep the reservation and
     *                                   tracking and report success, so the monitor owns it
     *   Job absent AND census safe    -> positively nothing exists; release and defer, which
     *                                   is safe precisely because there is no first attempt
     *   anything else (UNDETERMINED,
     *   MAY_RUN, or an unreadable API) -> may exist; keep everything and report success, so
     *                                   this pair cannot be submitted a second time while
     *                                   the first attempt is unresolved
     * </pre>
     *
     * <p>Reporting success for an execution that may not exist is deliberate and is the
     * lesser risk: the pair becomes ENQUEUED against this execution id, so no duplicate can
     * be dispatched, and if the Job truly never existed the gated startup reconciliation
     * resolves it — that path resets an ENQUEUED pair to PENDING_SUBMIT only when a census
     * positively establishes that no pod for it can run.
     */
    private int resolveAmbiguousSubmission(
        int execId,
        int pairId,
        String jobName,
        String logPath
    ) {
        // Tri-state, NOT kubernetesJobExists. That helper reports true when it cannot tell,
        // which is the correct fail-closed answer to "may I release this execution" but the
        // wrong one here: it would report an unreachable API as "the Job is present" and so
        // let StarExec claim an execution it never created. The distinction matters for
        // what gets logged and for whether a retry owner is registered.
        JobPresence presence = observeJob(jobName);
        PodPhaseView.Census census =
            presence == JobPresence.PRESENT ? null : censusFor(execId);

        if (presence == JobPresence.ABSENT && census != null && census.isSafe()) {
            execIdToJobName.remove(execId);
            execIdToJobUid.remove(execId);
            execIdToPairId.remove(execId);
            execIdToOutputDir.remove(execId);
            releaseSubmissionSlot(execId);
            log.warn(
                "Submission of pair " + pairId + " failed and the cluster confirms nothing" +
                " was created (job " + jobName + " absent, " + census.safety() +
                "); the reservation is released and the pair stays queued for retry"
            );
            throw new SubmissionDeferredException(
                "create() failed for pair " + pairId + " with no cluster side effect"
            );
        }

        // Either the Job exists, or we cannot prove it does not. Keep the reservation and
        // the tracking maps so the execution is accounted and the monitor can resolve it.
        execIdToJobName.put(execId, jobName);
        // Explicitly no UID. This branch is reached precisely because the create outcome is
        // unknown, so there is no object identity to record; leaving the entry absent is
        // what makes ownsTracking fall back to the name rather than compare against
        // something that was never established.
        execIdToJobUid.remove(execId);
        execIdToPairId.put(execId, pairId);
        try {
            execIdToOutputDir.put(execId, resolveOutputDirectory(logPath));
        } catch (Exception ignored) {
            // The output dir is recoverable later from the pair's stdout path; not having
            // it must not cost us the accounting.
        }
        restoreSubmissionSlot(execId);
        // Register a retry owner. Neither the job monitor nor the orphan inventory can
        // resolve this on its own: the monitor enumerates Jobs and the inventory
        // enumerates pods, so a create that produced NEITHER leaves both with nothing to
        // find, and the slot would be held until the JVM restarted.
        ambiguousSubmissions.put(
            execId, new AmbiguousSubmission(pairId, jobName, clock.getAsLong())
        );
        log.error(
            "Submission of pair " + pairId + " (execId " + execId + ", job " + jobName +
            ") failed with an UNRESOLVED outcome: Job " + presence +
            (census == null ? "" : ", pods report " + census) +
            ". Treating the submission as established so no duplicate is dispatched, and" +
            " retaining its slot and tracking so nothing is scheduled on top of it." +
            " resolveAmbiguousSubmissions will settle it on the next sweep."
        );
        return execId;
    }

    private Job buildKubernetesJob(
        int pairId,
        int execId,
        String jobName,
        String scriptPath,
        String workingDirectoryPath,
        String logPath
    ) {
        Map<String, String> labels = new HashMap<>();
        labels.put(MANAGED_LABEL, "true");
        labels.put(EXEC_ID_LABEL, String.valueOf(execId));
        labels.put(PAIR_ID_LABEL, String.valueOf(pairId));

        Path outputDir = resolveOutputDirectory(logPath);

        ResourceRequirementsBuilder resourcesBuilder = new ResourceRequirementsBuilder()
            .addToRequests("memory", new Quantity(memoryLimit))
            .addToRequests("cpu", new Quantity(cpuLimit))
            .addToLimits("memory", new Quantity(memoryLimit))
            .addToLimits("cpu", new Quantity(cpuLimit));

        Map<String, String> nodeSelector = new HashMap<>();
        if (workerNodeSelectorKey != null && !workerNodeSelectorKey.trim().isEmpty()) {
            nodeSelector.put(workerNodeSelectorKey, workerNodeSelectorValue);
        }

        // Route to the queue's nodes. Without this the selector carried only the worker
        // label, so a pair submitted to one queue could execute on any worker node in any
        // other -- the queue was recorded in the database and shown in the UI while
        // meaning nothing to the scheduler. Queues separate competitions and hardware
        // classes, so that is a correctness failure, not a scheduling inefficiency.
        //
        // Membership of the default queue is "not claimed by any other queue", which a
        // nodeSelector cannot express -- it matches label values, and this is the absence
        // of a label. That is why the default queue used to carry no queue constraint at
        // all, which left it selecting on the worker label alone: on a cluster where some
        // workers are labelled for another queue, a default-queue pair could be scheduled
        // onto that queue's hardware. It is the same defect the queue selector was added
        // to fix, surviving in the one case the selector could not state.
        //
        // nodeAffinity can state it. DoesNotExist is a required match on the absence of
        // the key, so a default-queue pod is confined to nodes no other queue claims.
        // Applied alongside the nodeSelector, which Kubernetes ANDs with it.
        String queueName = pendingQueueName.get();
        boolean queueLabelUsable = queueLabelKey != null && !queueLabelKey.trim().isEmpty();
        Affinity queueAffinity = null;
        if (queueName != null && queueLabelUsable) {
            if (isDefaultQueueName(queueName)) {
                queueAffinity = defaultQueueAffinity();
            } else {
                nodeSelector.put(queueLabelKey, queueName);
            }
        }

        boolean pinToAppNode = requiresSameNodeDataPvc();
        String pinnedNodeName = null;
        if (pinToAppNode && appNodeName != null && !appNodeName.trim().isEmpty()) {
            pinnedNodeName = appNodeName;
        }
        if (pinToAppNode && (appNodeName == null || appNodeName.trim().isEmpty())) {
            log.warn(
                "Shared data PVC uses " +
                dataPvcAccessMode +
                " but STAREXEC_K8S_APP_NODE_NAME is unavailable; job scheduling may fail"
            );
        }

        return new JobBuilder()
            .withNewMetadata()
                .withName(jobName)
                .withNamespace(namespace)
                .addToLabels(labels)
            .endMetadata()
            .withNewSpec()
                .withBackoffLimit(backoffLimit)
                .withTtlSecondsAfterFinished(ttlSecondsAfterFinished)
                .withNewTemplate()
                    .withNewMetadata()
                        .addToLabels(labels)
                    .endMetadata()
                    .withNewSpec()
                        .withServiceAccountName(serviceAccountName)
                        .withRestartPolicy("Never")
                        .withNodeName(pinnedNodeName)
                        .withNodeSelector(nodeSelector)
                        .withAffinity(queueAffinity)
                        .addNewContainer()
                            .withName("job-runner")
                            .withImage(jobImage)
                            .withImagePullPolicy(configuredImagePullPolicy())
                            .withCommand("/bin/bash")
                            .withArgs(scriptPath)
                            .withWorkingDir(workingDirectoryPath)
                            .addNewEnv()
                                .withName("STAREXEC_PAIR_ID")
                                .withValue(String.valueOf(pairId))
                            .endEnv()
                            .addNewEnv()
                                .withName("CONTAINER_MODE")
                                .withValue("true")
                            .endEnv()
                            .addNewEnv()
                                .withName("STAREXEC_OUTPUT_DIR")
                                .withValue(outputDir.toString())
                            .endEnv()
                            // The node this pair actually ran on, from the downward API.
                            //
                            // Without it every Kubernetes pair lost its measurements.
                            // containerWriteStats records "hostname": "$(hostname)", and
                            // in a pod that is the POD name -- nothing here sets
                            // spec.hostname or hostNetwork, so the default applies.
                            // resolveStatsNodeName then hands that pod name to
                            // UpdatePairRunSolverStats, which resolves the node by name
                            // and raises P0002 when it is absent, aborting the whole
                            // write: wallclock, cpu, user and system time, max_vmem,
                            // max_res_set, disk_size and the quota accounting with it.
                            // The pair still looked successful.
                            //
                            // It cannot be a literal: the scheduler picks the node after
                            // this Job is created, so spec.hostname would have to be
                            // guessed. fieldRef reads it at pod start, and the value is
                            // the Node's metadata.name -- the same string
                            // Cluster.loadWorkerNodes stores in nodes.name, verified
                            // against the live cluster.
                            //
                            // PodmanBackend solves the same problem by setting the
                            // container hostname to the worker node name; this is the
                            // Kubernetes equivalent.
                            .addNewEnv()
                                .withName("STAREXEC_NODE_NAME")
                                .withNewValueFrom()
                                    .withNewFieldRef()
                                        .withFieldPath("spec.nodeName")
                                    .endFieldRef()
                                .endValueFrom()
                            .endEnv()
                            .addNewVolumeMount()
                                .withName("starexec-data")
                                .withMountPath("/app/data")
                            .endVolumeMount()
                            .withResources(resourcesBuilder.build())
                        .endContainer()
                        .addNewVolume()
                            .withName("starexec-data")
                            .withNewPersistentVolumeClaim()
                                .withClaimName(dataPvcName)
                            .endPersistentVolumeClaim()
                        .endVolume()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build();
    }

    /**
     * Confines a default-queue pod to nodes that no other queue has claimed.
     *
     * <p>{@code DoesNotExist} on the queue label key is the only way to express "this node
     * belongs to no named queue" to the scheduler. A {@code nodeSelector} matches label
     * values and so cannot say it, which is why the default queue previously travelled
     * with no queue constraint at all.
     *
     */
    private Affinity defaultQueueAffinity() {
        // Two terms, because nodeSelectorTerms are OR'd. A node belongs to the default
        // queue when it carries no queue label at all, or when it carries one of the
        // default spellings -- including the legacy "default" both runbooks still tell
        // operators to apply. Without the second term this affinity would exclude
        // precisely the already-deployed workers it exists to select.
        return new AffinityBuilder()
            .withNewNodeAffinity()
                .withNewRequiredDuringSchedulingIgnoredDuringExecution()
                    .addNewNodeSelectorTerm()
                        .addNewMatchExpression()
                            .withKey(queueLabelKey)
                            .withOperator("DoesNotExist")
                        .endMatchExpression()
                    .endNodeSelectorTerm()
                    .addNewNodeSelectorTerm()
                        .addNewMatchExpression()
                            .withKey(queueLabelKey)
                            .withOperator("In")
                            // The same list normalizeQueueLabel reads, so the two cannot
                            // classify a node differently.
                            .withValues(DEFAULT_QUEUE_LABEL_VALUES)
                        .endMatchExpression()
                    .endNodeSelectorTerm()
                .endRequiredDuringSchedulingIgnoredDuringExecution()
            .endNodeAffinity()
            .build();
    }

    private boolean requiresSameNodeDataPvc() {
        return "ReadWriteOnce".equals(dataPvcAccessMode) ||
            "ReadWriteOncePod".equals(dataPvcAccessMode);
    }

    private Path resolveOutputDirectory(String logPath) {
        if (logPath == null || logPath.trim().isEmpty()) {
            return Paths.get(R.JOB_OUTPUT_DIRECTORY);
        }

        Path path = Paths.get(logPath);
        Path parent = path.getParent();
        if (parent != null) {
            return parent;
        }
        return Paths.get(R.JOB_OUTPUT_DIRECTORY);
    }

    /**
     * Generate a unique execution ID.
     */
    private int generateExecId() {
        synchronized (idLock) {
            int id = nextExecId++;
            if (nextExecId < 0) {
                nextExecId = 1; // Wrap around
            }
            return id;
        }
    }

    /**
     * Reserves the concurrency slot for {@code execId}.
     *
     * <p>{@code jobsHoldingSlot} is the single source of truth and {@code activeJobCount}
     * is derived from it, both mutated under {@link #slotLock}, so the two cannot drift.
     * They used to be an independent counter and set updated at different points, which
     * made the cap advisory: a CAS on the integer followed by an unrelated set insertion
     * proves neither "never exceeds max" nor "count equals set size".
     *
     * <p>Idempotent by design. Restoring a reservation for an execution already holding
     * one — which startup reconciliation and the orphan inventory both do — succeeds
     * without double-counting.
     *
     * @return false only when the cap is genuinely full; the caller must then defer
     */
    private boolean tryAcquireSubmissionSlot(int execId) {
        synchronized (slotLock) {
            if (jobsHoldingSlot.contains(execId)) {
                return true;
            }
            if (jobsHoldingSlot.size() >= maxConcurrentJobs) {
                return false;
            }
            jobsHoldingSlot.add(execId);
            activeJobCount.set(jobsHoldingSlot.size());
            log.debug(
                "Reserved K8s concurrency slot (execId=" + execId + ", active=" +
                jobsHoldingSlot.size() + ", max=" + maxConcurrentJobs + ")"
            );
            return true;
        }
    }

    /**
     * Accounts for an execution that ALREADY EXISTS, even if that takes the tally past
     * {@code maxConcurrentJobs}.
     *
     * <p>Distinct from {@link #tryAcquireSubmissionSlot} and the distinction is the whole
     * point. That method admits NEW work and must respect the cap. This one records work
     * that is already running in the cluster — rebuilt by startup reconciliation, or found
     * by the orphan inventory — and for that, refusing because the cap is full would mean
     * discovering a live execution and choosing not to count it. The cap is a limit on what
     * StarExec may START, never a licence to under-report what exists.
     *
     * <p>So if 53 live executions are discovered against a cap of 50, the tally becomes 53
     * and admission defers until it falls below the limit. Idempotent: restoring an
     * execution that already holds a slot changes nothing.
     */
    private void restoreSubmissionSlot(int execId) {
        restoreSubmissionSlot(execId, false);
    }

    /**
     * @param unidentifiedExecution true when this execution was discovered rather than
     *        reconstructed from StarExec's own records — an untracked pod, or a kill for
     *        which no local job name survived. That is a fidelity risk needing operator
     *        attention, so it is reported at ERROR (and therefore reaches the admin error
     *        digest) rather than as routine reconstruction noise.
     */
    private void restoreSubmissionSlot(int execId, boolean unidentifiedExecution) {
        synchronized (slotLock) {
            if (!jobsHoldingSlot.add(execId)) {
                return;
            }
            activeJobCount.set(jobsHoldingSlot.size());
            int held = jobsHoldingSlot.size();
            if (unidentifiedExecution) {
                log.error(
                    "Accounting for UNIDENTIFIED execution " + execId + ": it was found in" +
                    " the cluster rather than in StarExec's own tracking, so something ran" +
                    " outside the accounting. Held slots now " + held + "/" +
                    maxConcurrentJobs + ". OPERATOR ACTION: identify pods labelled " +
                    EXEC_ID_LABEL + "=" + execId + " in namespace " + namespace + "."
                );
            } else if (held > maxConcurrentJobs) {
                log.warn(
                    "Accounting for pre-existing execution " + execId + " takes the held" +
                    " slot count to " + held + ", above the configured cap of " +
                    maxConcurrentJobs + ". These executions are real and already consuming" +
                    " cluster resources, so they are counted rather than hidden; admission" +
                    " defers until the count falls below the cap."
                );
            } else {
                log.debug(
                    "Restored K8s concurrency slot for pre-existing execution " + execId +
                    " (active=" + held + ", max=" + maxConcurrentJobs + ")"
                );
            }
        }
    }

    /** A submission whose {@code create()} outcome was never established. */
    private static final class AmbiguousSubmission {

        private final int pairId;
        private final String jobName;
        private final long firstSeenAtMillis;

        /**
         * True once the cluster has positively established that nothing was created.
         *
         * <p>Two phases, because they fail independently. Until this is set the record is
         * AWAITING_CLUSTER_RESOLUTION and each sweep re-observes the Job and re-censuses
         * the pods. Once set it is AWAITING_DB_RESET: cluster safety is proven and must not
         * be re-proved, so later sweeps only retry the database reset. Collapsing the two
         * would either repeat the cluster work forever or, worse, drop the record before
         * the database caught up.
         */
        private volatile boolean clusterProvedAbsent;

        private AmbiguousSubmission(int pairId, String jobName, long firstSeenAtMillis) {
            this.pairId = pairId;
            this.jobName = jobName;
            this.firstSeenAtMillis = firstSeenAtMillis;
        }
    }

    /**
     * Submissions retained as "established" without proof, awaiting resolution.
     *
     * <p>These need their own retry owner and cannot borrow one. The job monitor
     * enumerates Kubernetes Jobs, so a Job that was never created gives it nothing to
     * process; the orphan inventory enumerates pods, so it finds nothing either. Without
     * {@link #resolveAmbiguousSubmissions()} such an execution would hold its slot until
     * the JVM restarted.
     */
    private final Map<Integer, AmbiguousSubmission> ambiguousSubmissions =
        new ConcurrentHashMap<>();

    /**
     * Resolves submissions whose {@code create()} outcome was never established.
     *
     * <p>Driven by the existing periodic orphan sweep rather than a new scheduler.
     *
     * <pre>
     *   Job PRESENT                  -> resolved; the monitor owns it from here
     *   Job ABSENT + census safe     -> nothing was ever created: release the reservation
     *                                   and put the pair back for a clean retry
     *   anything else                -> retain accounting and try again next sweep
     * </pre>
     */
    /**
     * Returns the pair to PENDING_SUBMIT after cluster absence has been established.
     *
     * @return true when the obligation is discharged and the record may be dropped —
     *         either the reset succeeded, or the pair had already moved on by itself.
     *         False means retry on the next sweep.
     */
    private boolean retryAmbiguousDbReset(int execId, AmbiguousSubmission pending) {
        try {
            JobPairs.ConditionalPairUpdateResult result =
                JobPairs.tryResetEnqueuedToPending(pending.pairId);
            if (result == JobPairs.ConditionalPairUpdateResult.UPDATED) {
                log.info(
                    "Pair " + pending.pairId + " returned to PENDING_SUBMIT after its" +
                    " ambiguous submission (execId " + execId + ") was proved never to have" +
                    " been created"
                );
                return true;
            }
            if (result == JobPairs.ConditionalPairUpdateResult.STALE) {
                // No longer ENQUEUED: something else already moved it on, so there is
                // nothing left to do and the record would only be noise.
                log.info(
                    "Pair " + pending.pairId + " is no longer ENQUEUED; dropping the" +
                    " ambiguous-submission record for execId " + execId
                );
                return true;
            }
            log.error(
                "Could not return pair " + pending.pairId + " to PENDING_SUBMIT after" +
                " resolving ambiguous submission execId " + execId + " (" + result +
                "); retaining the record so the next sweep retries. The pair is safe but" +
                " stuck until this succeeds."
            );
            return false;
        } catch (Exception e) {
            log.error(
                "Could not return pair " + pending.pairId + " to PENDING_SUBMIT after" +
                " resolving ambiguous submission execId " + execId +
                "; retaining the record so the next sweep retries", e
            );
            return false;
        }
    }

    private void resolveAmbiguousSubmissions() {
        for (Map.Entry<Integer, AmbiguousSubmission> entry : ambiguousSubmissions.entrySet()) {
            int execId = entry.getKey();
            AmbiguousSubmission pending = entry.getValue();
            JobPresence presence = observeJob(pending.jobName);

            if (presence == JobPresence.PRESENT) {
                log.info(
                    "Ambiguous submission for pair " + pending.pairId + " (execId " + execId +
                    ") resolved: Job " + pending.jobName + " exists and the monitor now owns it"
                );
                ambiguousSubmissions.remove(execId);
                continue;
            }

            // Phase 2: cluster safety already proven on an earlier sweep, so do not repeat
            // the deletion/census work. Only the database reset is outstanding.
            if (pending.clusterProvedAbsent) {
                if (retryAmbiguousDbReset(execId, pending)) {
                    ambiguousSubmissions.remove(execId);
                }
                continue;
            }

            if (presence == JobPresence.ABSENT) {
                PodPhaseView.Census census = censusFor(execId);
                if (census.isSafe()) {
                    log.warn(
                        "Ambiguous submission for pair " + pending.pairId + " (execId " +
                        execId + ") resolved: Job " + pending.jobName + " was never created" +
                        " and no pod for it can run (" + census.safety() + "). Releasing its" +
                        " reservation and returning the pair for a clean retry."
                    );
                    // Capacity can be released the moment absence is established -- that is
                    // a measurement-safety question and it is now answered.
                    execIdToJobName.remove(execId);
                    execIdToJobUid.remove(execId);
                    execIdToPairId.remove(execId);
                    execIdToOutputDir.remove(execId);
                    releaseSubmissionSlot(execId);

                    // But the RECORD survives until the database agrees. Dropping it here
                    // and merely logging a failed reset would leave the pair ENQUEUED
                    // against an execution that never existed, with nothing in Kubernetes
                    // for the monitor or the inventory to rediscover -- safe, but stuck
                    // forever. This is a liveness obligation, not a safety one.
                    pending.clusterProvedAbsent = true;
                    if (retryAmbiguousDbReset(execId, pending)) {
                        ambiguousSubmissions.remove(execId);
                    }
                    continue;
                }
            }

            log.warn(
                "Ambiguous submission for pair " + pending.pairId + " (execId " + execId +
                ", job " + pending.jobName + ") is still unresolved after " +
                ((clock.getAsLong() - pending.firstSeenAtMillis) / 1000) + "s (Job " +
                presence + "); its slot and tracking are retained and it will be retried."
            );
        }
    }

    /** The number of executions currently holding a slot. */
    private int activeSlotCount() {
        synchronized (slotLock) {
            return jobsHoldingSlot.size();
        }
    }

    /**
     * Releases the concurrency slot held by {@code execId}, if any.
     *
     * <p>Idempotent: the set membership check is the guard, so a second release for the
     * same execution is a no-op rather than a double decrement.
     */
    private void releaseSubmissionSlot(int execId) {
        synchronized (slotLock) {
            if (!jobsHoldingSlot.remove(execId)) {
                return;
            }
            activeJobCount.set(jobsHoldingSlot.size());
            log.debug(
                "Released K8s concurrency slot (execId=" +
                execId +
                ", active=" +
                jobsHoldingSlot.size() +
                ", max=" +
                maxConcurrentJobs +
                ")"
            );
        }
    }

    /**
     * Clears an execution's tracking and slot, but only once nothing for it can still run.
     *
     * <p>The completion callbacks used to clear all four unconditionally, on the strength of
     * the Job having reported a terminal outcome. A Job can carry a terminal condition while
     * its Pod is still terminating, and releasing the slot then lets an unrelated pair be
     * scheduled onto hardware the previous execution has not finished vacating — which on a
     * benchmarking platform contaminates the measurement rather than merely wasting capacity.
     *
     * <p>When safety is not established the accounting is retained and the execution is
     * registered for the sweep to revisit, so the hold is bounded by observation rather than
     * by process lifetime.
     */
    /**
     * Records that an execution was stopped, so its own late callbacks can be discarded.
     *
     * <p>Against the concrete Kubernetes object when one is known. When it is not, a legacy
     * tombstone is kept instead, and a tombstone suppresses nothing: it cannot distinguish
     * the execution that was stopped from a later one handed the same number, and guessing
     * in that situation is the defect this exists to prevent.
     */
    private void recordExecutionStopped(ExecutionRef execution, int execId, String context) {
        if (execution != null) {
            killedExecutions.add(execution);
            return;
        }
        legacyKilledExecIds.add(execId);
        log.warn(
            "Stopped execId " + execId + " (" + context + ") without a Kubernetes object" +
            " identity to record it against. A later execution reusing this id will NOT be" +
            " suppressed by it; a late callback from the stopped execution will be resolved" +
            " against the Job it names."
        );
    }

    /**
     * As below, for a callback that knows which execution it is speaking for.
     *
     * <p>The accounting under an execution id belongs to whichever execution holds the id
     * now. A superseded Job's terminal callback releasing it would hand the current
     * execution's submission slot back while it is still running, and unrelated pairs would
     * be scheduled on top of it — the primary invariant names releasing accounting
     * explicitly for that reason.
     */
    private void releaseAccountingIfSafe(ExecutionRef execution, String context) {
        if (execution == null || !ownsTracking(execution)) {
            log.info(
                "Not releasing accounting after " + context + ": " + execution +
                " does not hold the tracking under its execution id."
            );
            return;
        }
        releaseAccountingIfSafe(execution.execId(), context);
    }

    private void releaseAccountingIfSafe(int execId, String context) {
        if (observeExecutionSafety(execId) != KillOutcome.CONFIRMED_SAFE) {
            log.warn(
                "Not releasing accounting for execId " + execId + " after " + context +
                    ": execution safety is not established (controller " +
                    observeControllerFor(execId) + ", pods " + censusFor(execId) + ")."
            );
            recordUnverified(execId, context);
            return;
        }
        execIdToJobName.remove(execId);
        execIdToJobUid.remove(execId);
        execIdToPairId.remove(execId);
        execIdToOutputDir.remove(execId);
        ambiguousSubmissions.remove(execId);
        unverifiedExecutions.remove(execId);
        releaseSubmissionSlot(execId);
    }

    /**
     * Releases all concurrency slots. Shutdown only: {@code destroyIf} is the sole caller,
     * and it runs after the Kubernetes client is closed. {@code killAll} deliberately does
     * NOT use this -- it releases per execution, so an execution it could not confirm
     * stopped keeps its slot.
     */
    private void releaseAllSlots() {
        int released = 0;
        for (Integer execId : jobsHoldingSlot) {
            releaseSubmissionSlot(execId);
            released++;
        }
        if (released > 0) {
            log.info("Released " + released +
                     " K8s concurrency slots during bulk teardown");
        }
    }

    /**
     * Rebuilds in-memory execution tracking from Kubernetes Job labels and
     * reconciles stale ENQUEUED/RUNNING database rows after a StarExec restart.
     *
     * <p>Recovery is intentionally conservative:</p>
     * <ul>
     *   <li>active K8s Job evidence rebuilds tracking and marks the pair RUNNING;</li>
     *   <li>terminal K8s Job evidence is processed through the normal completion callback;</li>
     *   <li>ENQUEUED DB rows without Job evidence are reset to PENDING_SUBMIT;</li>
     *   <li>RUNNING DB rows without Job evidence are marked as terminal failure.</li>
     * </ul>
     */
    private void reconcileOrphanedPairs() {
        try {
            log.info("Starting Kubernetes orphaned-pair reconciliation...");

            JobList jobList = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(MANAGED_LABEL, "true")
                .list();

            Map<Integer, Job> pairIdToActiveJob = new HashMap<>();
            Map<Integer, Job> pairIdToTerminalJob = new HashMap<>();
            int malformedJobs = 0;
            int orphanedKubernetesJobs = 0;
            int maxRecoveredExecId = 0;

            for (Job job : jobList.getItems()) {
                Integer pairId = extractPairId(job);
                Integer execId = extractExecId(job);
                if (pairId == null || pairId <= 0 || execId == null || execId <= 0) {
                    malformedJobs++;
                    continue;
                }

                JobPairs.PairStatusLookupResult lookup =
                    JobPairs.getPairStatusLookup(pairId);
                if (lookup.isMissing()) {
                    deleteKubernetesJob(job);
                    orphanedKubernetesJobs++;
                    continue;
                }
                if (lookup.isError()) {
                    log.warn("Skipping reconciliation for pair " + pairId +
                             " because DB status lookup failed");
                    continue;
                }

                // isControllerSpent, not isTerminalJob: this bucket decides whether startup
                // publishes a terminal pair status, and a Job that has merely lost a Pod can
                // still start the next attempt. Anything not provably spent is treated as
                // active, which is the conservative direction.
                if (isControllerSpent(job)) {
                    pairIdToTerminalJob.put(pairId, job);
                } else {
                    pairIdToActiveJob.put(pairId, job);
                }

                if (execId > maxRecoveredExecId) {
                    maxRecoveredExecId = execId;
                }
            }

            // Whether a recovered job is running or merely waiting to be scheduled cannot
            // be read from the Job alone, and getting it wrong here re-applies the
            // mislabel this reconciliation exists to clear.
            PodPhaseView pods = PodPhaseView.list(
                kubernetesClient,
                namespace,
                MANAGED_LABEL,
                EXEC_ID_LABEL
            );

            List<Integer> enqueuedIds = JobPairs.getPairIdsByStatusCode(
                StatusCode.STATUS_ENQUEUED.getVal());
            List<Integer> runningIds = JobPairs.getPairIdsByStatusCode(
                StatusCode.STATUS_RUNNING.getVal());

            int enqueuedReset = 0, enqueuedProcessed = 0, enqueuedRebuilt = 0;
            int runningFailed = 0, runningProcessed = 0, runningRebuilt = 0;
            int enqueuedWithheld = 0, runningWithheld = 0;

            for (int pairId : enqueuedIds) {
                Job activeJob = pairIdToActiveJob.get(pairId);
                Job terminalJob = pairIdToTerminalJob.get(pairId);
                if (activeJob != null) {
                    rebuildTrackingFromJob(activeJob, pods);
                    enqueuedRebuilt++;
                } else if (terminalJob != null) {
                    if (processReconciledJobThroughCallback(terminalJob, pods)) {
                        enqueuedProcessed++;
                    }
                } else if (!reconciledPairIsSafe(pairId, "reset to PENDING_SUBMIT")) {
                    enqueuedWithheld++;
                } else if (JobPairs.tryResetEnqueuedToPending(pairId)
                        == JobPairs.ConditionalPairUpdateResult.UPDATED) {
                    enqueuedReset++;
                }
            }

            for (int pairId : runningIds) {
                Job activeJob = pairIdToActiveJob.get(pairId);
                Job terminalJob = pairIdToTerminalJob.get(pairId);
                if (activeJob != null) {
                    rebuildTrackingFromJob(activeJob, pods);
                    runningRebuilt++;
                } else if (terminalJob != null) {
                    if (processReconciledJobThroughCallback(terminalJob, pods)) {
                        runningProcessed++;
                    }
                } else if (!reconciledPairIsSafe(pairId, "mark failed")) {
                    runningWithheld++;
                } else if (JobPairs.tryMarkRunningAsFailed(pairId)
                        == JobPairs.ConditionalPairUpdateResult.UPDATED) {
                    runningFailed++;
                }
            }

            if (maxRecoveredExecId > 0) {
                synchronized (idLock) {
                    if (maxRecoveredExecId >= nextExecId) {
                        nextExecId = maxRecoveredExecId + 1;
                    }
                }
            }

            log.info(
                "Kubernetes reconciliation complete: ENQUEUED reset=" +
                    enqueuedReset +
                    " processed=" +
                    enqueuedProcessed +
                    " rebuilt=" +
                    enqueuedRebuilt +
                    " withheld=" +
                    enqueuedWithheld +
                    "; RUNNING failed=" +
                    runningFailed +
                    " processed=" +
                    runningProcessed +
                    " rebuilt=" +
                    runningRebuilt +
                    " withheld=" +
                    runningWithheld +
                    "; malformedJobs=" +
                    malformedJobs +
                    "; orphanedKubernetesJobs=" +
                    orphanedKubernetesJobs
            );
        } catch (Exception e) {
            log.error("Failed to reconcile Kubernetes orphaned pairs on startup", e);
        }
    }

    /**
     * Whether a pair with no surviving Job may have its DB state changed at startup.
     *
     * <p>"No Job in the listing" is controller-absence evidence, but only half of what is
     * needed. A Pod can outlive its Job — deleting a Job with Background propagation removes
     * the owner first and reaps dependents afterwards, and a crash in that window leaves
     * exactly this state. Both reconciliation branches are replacement authorizations:
     * resetting an ENQUEUED pair makes it dispatchable again, and marking a RUNNING pair
     * failed gives it an {@code end_time} and so makes it eligible for an automatic rerun.
     * Either one, performed while a Pod for that pair still runs, produces two executions
     * writing results for one pair.
     *
     * <p>Identified by pair id rather than execution id because that is all a pair with no
     * Job offers. A pair id is stable across reruns, so this over-matches — pods of earlier
     * attempts count too. Over-matching is the safe direction: it can only withhold a
     * transition, never authorize one.
     */
    private boolean reconciledPairIsSafe(int pairId, String intendedTransition) {
        PodPhaseView.Census census = censusForPair(pairId);
        if (census != null && census.isSafe()) {
            return true;
        }
        log.warn(
            "Startup reconciliation will not " + intendedTransition + " for pair " + pairId +
                ": no Job survives for it, but its pods are " +
                (census == null ? "unreadable" : census.describe()) +
                ", so a pod may still be running. Leaving the pair as it is." +
                " OPERATOR ACTION: inspect pods labelled " + PAIR_ID_LABEL + "=" + pairId +
                " in namespace " + namespace + "."
        );
        return false;
    }

    private boolean processReconciledJobThroughCallback(Job job, PodPhaseView pods) {
        Integer execId = extractExecId(job);
        if (execId == null) {
            return false;
        }
        ExecutionRef execution = ExecutionRef.fromJob(execId, job);
        if (execution == null) {
            log.warn(
                "Reconciled Kubernetes job " + getJobName(job) + " (execId " + execId +
                ") carries no object identity; its result is not being applied."
            );
            return false;
        }

        rebuildTrackingFromJob(job, pods);
        KubernetesJobCompletionCallback callback =
            new KubernetesJobCompletionCallback();
        // Reached only for Jobs already established as controller-spent by the caller, so
        // the choice here is purely which terminal condition it carries.
        if (hasTrueCondition(job, "Complete")) {
            return callback.onJobComplete(execution);
        }
        return callback.onJobFailed(execution, summarizeJobFailure(job));
    }

    private void rebuildTrackingFromJob(Job job, PodPhaseView pods) {
        Integer execId = extractExecId(job);
        Integer pairId = extractPairId(job);
        String jobName = getJobName(job);
        if (execId == null || pairId == null || jobName == null) {
            return;
        }

        // Pair and output directory first, then the identity that grants ownership of them.
        ExecutionRef execution = ExecutionRef.fromJob(execId, job);
        execIdToPairId.put(execId, pairId);
        execIdToOutputDir.put(execId, resolveOutputDirectory(job, pairId));
        execIdToJobName.put(execId, jobName);
        if (execution != null) {
            execIdToJobUid.put(execId, execution.jobUid());
        } else {
            // Tracking is rebuilt, but without an identity to check later events against.
            // Left absent rather than filled in with something derived from the name.
            execIdToJobUid.remove(execId);
        }

        // Acquire a concurrency slot so the capacity tracker stays in sync
        // with the number of reconstructed in-memory tracking entries.
        //
        // Through restoreSubmissionSlot, NOT tryAcquireSubmissionSlot. This execution
        // already exists in the cluster, so it must be counted even if that takes the
        // tally past the cap; the capped acquire would have returned false and left a live
        // execution entirely unaccounted, which an earlier version of this code did while
        // claiming the opposite.
        restoreSubmissionSlot(execId);
        {
            log.debug(
                "Reclaimed K8s concurrency slot during reconciliation (execId=" +
                execId +
                ", active=" +
                activeSlotCount() +
                ", max=" +
                maxConcurrentJobs +
                ")"
            );
        }

        if (!isTerminalJob(job) && isRunningOnANode(job, execution, pods)) {
            markPairRunningSafely(pairId, "startup reconciliation");
        }
    }

    /**
     * Whether this job has a pod actually executing on a node.
     *
     * <p>The same judgement the monitor makes, through the same classifier, because two
     * copies of it drifted before: {@code status.active} counts pending pods as well as
     * running ones, so a restart during a scheduling failure re-marked the pair RUNNING.
     */
    private boolean isRunningOnANode(Job job, ExecutionRef execution, PodPhaseView pods) {
        if (!pods.isAvailable()) {
            return hasActivePod(job);
        }
        if (execution == null) {
            // A Job with no object identity, which reconciliation can still meet. Falling
            // back to hasActivePod here would reinstate exactly what this method exists to
            // avoid -- status.active counts pending pods -- so the pod listing still
            // decides, by the bare id, which declines to answer if two Jobs carry it.
            Integer execId = extractExecId(job);
            return execId != null
                && pods.phaseFor(execId) == PodPhaseView.Phase.RUNNING;
        }
        return pods.phaseFor(execution) == PodPhaseView.Phase.RUNNING;
    }

    private boolean hasActivePod(Job job) {
        if (job == null || job.getStatus() == null) {
            return false;
        }

        Integer active = job.getStatus().getActive();
        return active != null && active > 0;
    }

    private JobPairs.ConditionalPairUpdateResult markPairRunningSafely(
        int pairId,
        String source
    ) {
        if (pairId <= 0) {
            return JobPairs.ConditionalPairUpdateResult.STALE;
        }

        try {
            JobPairs.ConditionalPairUpdateResult result = JobPairs.trySetPairRunning(pairId);
            if (result == JobPairs.ConditionalPairUpdateResult.UPDATED) {
                log.debug(
                    "Marked pair " + pairId + " as STATUS_RUNNING from " + source
                );
            } else if (result == JobPairs.ConditionalPairUpdateResult.STALE) {
                log.debug(
                    "Skipping STATUS_RUNNING update for stale or completed pair " +
                    pairId +
                    " from " +
                    source
                );
            } else {
                log.warn(
                    "Failed to set running status for pair " + pairId +
                    " from " +
                    source
                );
            }
            return result;
        } catch (Exception e) {
            log.warn(
                "Failed to set running status for pair " + pairId +
                " from " +
                source,
                e
            );
            return JobPairs.ConditionalPairUpdateResult.ERROR;
        }
    }

    /**
     * Removes the previous attempt's result artifacts from a pair's output directory.
     *
     * <p>A result must not be able to outlive the attempt that produced it. The directory
     * is keyed by pair, not by attempt, so without this a rerun that fails before
     * runsolver executes would be classified from the earlier run's files.
     *
     * <p>Best-effort: a failure here is logged rather than aborting the submission, since
     * refusing to dispatch is a worse outcome than a stale file, but it is a real
     * wrong-result risk and so is warned about rather than swallowed.
     */
    private boolean clearStaleAttemptArtifacts(Path outputDir, int pairId) {
        if (outputDir == null) {
            return true;
        }
        boolean allAbsent = true;
        for (String name : STALE_ATTEMPT_ARTIFACTS) {
            Path artifact = outputDir.resolve(name);
            try {
                Files.deleteIfExists(artifact);
            } catch (Exception e) {
                log.error(
                    "Could not delete stale " + name + " for pair " + pairId +
                    " in " + outputDir,
                    e
                );
            }
            // Confirm ABSENCE rather than trusting that delete() did not throw. The
            // caller is about to decide whether a benchmark may run on the strength of
            // this, so "the call did not fail" is not the property we need.
            if (!confirmedAbsent(artifact)) {
                allAbsent = false;
                log.error(
                    "Stale " + name + " from a previous attempt may survive in " + outputDir +
                    " for pair " + pairId + "; refusing to submit, because it would be" +
                    " read as this attempt's result"
                );
            }
        }
        // The per-stage snapshots too. This directory is not in STALE_ATTEMPT_ARTIFACTS
        // because nothing read it until stage-status ingestion existed; now that earlier
        // stages are reconstructed from it, and the output directory is keyed by pair rather
        // than by attempt, a surviving directory would let a previous attempt's stage history
        // be recorded against this one.
        Path staleSnapshots = outputDir.resolve("stage-status");
        try {
            if (Files.isDirectory(staleSnapshots)) {
                try (java.util.stream.Stream<Path> entries = Files.walk(staleSnapshots)) {
                    entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            log.error("Could not delete stale " + path, e);
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.error(
                "Could not clear stale stage snapshots for pair " + pairId + " in " + outputDir,
                e
            );
        }
        if (!confirmedAbsent(staleSnapshots)) {
            allAbsent = false;
            log.error(
                "Stale stage-status snapshots from a previous attempt may survive in " +
                outputDir + " for pair " + pairId + "; refusing to submit, because they would" +
                " be read as this attempt's stage history"
            );
        }

        return allAbsent;
    }

    /**
     * Whether a path is <em>proven</em> not to exist.
     *
     * <p>Not {@code Files.exists}, which this replaced. That method returns false both when
     * the file is absent and when its existence <em>cannot be determined</em> — an
     * unreadable parent directory, an I/O error, a security manager — and it collapses those
     * into the same answer as "definitely not there". Used as a safety check that fails
     * open: a permission problem on the output directory would read as "no stale artifact"
     * and let a previous attempt's {@code var.out} be scored as this attempt's result.
     *
     * <p>{@link java.nio.file.NoSuchFileException} is the only outcome that proves absence.
     * Every other exception means "cannot tell", which here must mean "do not proceed".
     *
     * <p>{@code NOFOLLOW_LINKS} deliberately: a symlink left where an artifact belongs is
     * itself a surviving artifact, and following it would ask about the wrong file.
     */
    private boolean confirmedAbsent(Path path) {
        try {
            Files.readAttributes(
                path,
                java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS
            );
            return false;   // it is there
        } catch (java.nio.file.NoSuchFileException e) {
            return true;    // proven absent
        } catch (Exception e) {
            // IOException, SecurityException, or anything unchecked: existence is unknown.
            log.warn("Could not establish whether " + path + " exists; treating as present", e);
            return false;
        }
    }

    /** Files a completed attempt leaves behind that would misclassify the next one. */
    private static final String[] STALE_ATTEMPT_ARTIFACTS = {
        "var.out",
        "watcher.out",
        "status.json",
        "stats.json",
    };

    private Path resolveOutputDirectory(Job job, int pairId) {
        if (job != null && job.getMetadata() != null
                && job.getMetadata().getAnnotations() != null) {
            String outputDir =
                job.getMetadata().getAnnotations().get(OUTPUT_DIR_ANNOTATION);
            if (outputDir != null && !outputDir.trim().isEmpty()) {
                return Paths.get(outputDir);
            }
        }

        try {
            return resolveOutputDirectory(JobPairs.getStdout(pairId));
        } catch (Exception e) {
            log.warn(
                "Could not reconstruct output directory for pair " + pairId +
                "; using default", e);
            return Paths.get(R.JOB_OUTPUT_DIRECTORY);
        }
    }

    /**
     * The identity currently tracked under {@code execId}, or null if there is none.
     *
     * <p>Null when the name is untracked, and null when the UID is: tracking rebuilt from a
     * Job that carried no UID identifies nothing, and saying so is the point.
     */
    private ExecutionRef trackedExecution(int execId) {
        String jobName = execIdToJobName.get(execId);
        String jobUid = execIdToJobUid.get(execId);
        if (jobName == null || jobUid == null) {
            return null;
        }
        return new ExecutionRef(execId, jobName, jobUid);
    }

    /**
     * Whether {@code execution} is the execution the tracking maps under its id describe.
     *
     * <p>Everything keyed on the execution id -- the pair, the output directory, the
     * submission slot -- belongs to whichever execution holds the id now. An event from a
     * different Job must not read or write any of it, which is what this question gates.
     *
     * <p>Tracking without a recorded UID matches on the Job name alone. That is the
     * pre-existing state of an execution reconstructed from a Job the API returned without
     * one, and refusing it outright would strand executions that predate this change; the
     * name is a weaker discriminator, not a meaningless one.
     */
    private boolean ownsTracking(ExecutionRef execution) {
        if (execution == null) {
            return false;
        }
        String trackedName = execIdToJobName.get(execution.execId());
        if (trackedName == null) {
            return false;
        }
        String trackedUid = execIdToJobUid.get(execution.execId());
        if (trackedUid != null) {
            return trackedUid.equals(execution.jobUid());
        }
        return trackedName.equals(execution.jobName());
    }

    /**
     * Whether a <em>different</em> execution now holds this one's execution id.
     *
     * <p>Distinct from {@link #ownsTracking}, and the distinction is load-bearing. Tracking
     * can be absent for legitimate reasons -- the shutdown drain and startup reconciliation
     * both reach terminal Jobs StarExec is not tracking -- and refusing those would lose
     * results this backend is supposed to collect. Refusing is only right when the id has
     * been handed to someone else, because everything still keyed on the integer, the output
     * directory above all, now describes that other execution.
     */
    private boolean isSuperseded(ExecutionRef execution) {
        if (execution == null) {
            return false;
        }
        // Read the name ONCE and answer from that reading. Asking ownsTracking() to read it
        // again turns a concurrent release -- a kill for this same execution, clearing the
        // maps between the two reads -- into "somebody else owns the id", and this event's
        // own result would be dropped as superseded.
        String trackedName = execIdToJobName.get(execution.execId());
        if (trackedName == null) {
            return false;
        }
        String trackedUid = execIdToJobUid.get(execution.execId());
        if (trackedUid != null) {
            return !trackedUid.equals(execution.jobUid());
        }
        return !trackedName.equals(execution.jobName());
    }

    private Integer extractExecId(Job job) {
        return extractIntegerLabel(job, EXEC_ID_LABEL);
    }

    private Integer extractPairId(Job job) {
        return extractIntegerLabel(job, PAIR_ID_LABEL);
    }

    private Integer extractIntegerLabel(Job job, String labelKey) {
        if (job == null || job.getMetadata() == null
                || job.getMetadata().getLabels() == null) {
            return null;
        }
        String value = job.getMetadata().getLabels().get(labelKey);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Malformed Kubernetes label " + labelKey + "='" + value + "'");
            return null;
        }
    }

    private String getJobName(Job job) {
        if (job == null || job.getMetadata() == null) {
            return null;
        }
        return job.getMetadata().getName();
    }

    /**
     * The canonical predicate: true only when this Job can never create another Pod.
     *
     * <p>A {@code batch/v1} Job is a <em>controller</em>. The question every safety-sensitive
     * release site actually needs answered is not "did a Pod finish" but "can this object
     * still start a replacement", and only the terminal Job conditions answer it.
     *
     * <p>Deliberately narrow. It does <strong>not</strong> consult:
     * <ul>
     *   <li>{@code status.failed} or {@code status.succeeded} — counters of <em>Pods</em>;
     *       with {@code backoffLimit > 0} a Job at {@code failed == 1} is live;</li>
     *   <li>{@code FailureTarget} / {@code SuccessCriteriaMet} — these <em>begin</em>
     *       termination; the real {@code Failed} / {@code Complete} condition follows;</li>
     *   <li>a terminal Pod — that is a fact about one Pod, not about the controller;</li>
     *   <li>{@code suspend} or {@code deletionTimestamp} — both are reversible or pending.</li>
     * </ul>
     *
     * <p>Necessary but never sufficient: Kubernetes can add the terminal condition while Pods
     * are still terminating, so an independent Pod census stays mandatory at every caller.
     *
     * <p>A null Job or null status is <em>not</em> spent. Absence of information is never
     * evidence of safety here.
     */
    private boolean isControllerSpent(Job job) {
        if (job == null || job.getStatus() == null) {
            return false;
        }
        return hasTrueCondition(job, "Complete") || hasTrueCondition(job, "Failed");
    }

    /**
     * Whether the Job reached a terminal outcome, for reporting and draining.
     *
     * <p>Kept separate from {@link #isControllerSpent(Job)} on purpose: this one answers
     * "what happened to the work", which legitimately tolerates the Pod counters, while the
     * other answers "can this object still start a replacement". Collapsing two different
     * questions into one helper hides semantic drift rather than removing it. No release,
     * terminal publication, or replacement may be authorized from this predicate.
     */
    private boolean isTerminalJob(Job job) {
        return isSucceededJob(job) || isFailedJob(job);
    }

    private boolean isSucceededJob(Job job) {
        if (job == null || job.getStatus() == null) {
            return false;
        }
        Integer succeeded = job.getStatus().getSucceeded();
        if (succeeded != null && succeeded > 0) {
            return true;
        }
        return hasTrueCondition(job, "Complete");
    }

    private boolean isFailedJob(Job job) {
        if (job == null || job.getStatus() == null) {
            return false;
        }
        Integer failed = job.getStatus().getFailed();
        if (failed != null && failed > 0) {
            return true;
        }
        return hasTrueCondition(job, "Failed");
    }

    private boolean hasTrueCondition(Job job, String type) {
        if (job.getStatus() == null || job.getStatus().getConditions() == null) {
            return false;
        }
        for (JobCondition condition : job.getStatus().getConditions()) {
            if (condition == null) {
                continue;
            }
            if (type.equalsIgnoreCase(condition.getType())
                    && "True".equalsIgnoreCase(condition.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private String summarizeJobFailure(Job job) {
        if (job == null || job.getStatus() == null
                || job.getStatus().getConditions() == null) {
            return "Kubernetes Job failed";
        }
        for (JobCondition condition : job.getStatus().getConditions()) {
            if (condition == null || !"Failed".equalsIgnoreCase(condition.getType())) {
                continue;
            }
            String reason = condition.getReason() != null
                ? condition.getReason() : "unknown";
            String message = condition.getMessage() != null
                ? condition.getMessage() : "no message";
            return reason + ": " + message;
        }
        return "Kubernetes Job failed";
    }

    private void deleteKubernetesJob(Job job) {
        ensureKubernetesJobGone(getJobName(job));
    }

    /**
     * Deletes a Job by name and reports whether it is actually gone.
     *
     * <p>Not "did the API report a deletion": an empty result also means the Job had
     * already vanished, and treating that as a failure would strand a pair whose Job
     * disappeared between the listing and this call. So an empty result is confirmed with
     * a read, and only a Job still present counts as a failure.
     *
     * <p>The distinction matters because callers use the Job's continued existence as
     * their retry trigger — see {@code onJobStuckPending}. A Job that is not finished is
     * never reaped by {@code ttlSecondsAfterFinished}, so one that will not delete stays
     * forever and its pod may still run.
     *
     * @return true when the Job is confirmed absent afterwards
     */
    private boolean ensureKubernetesJobGone(String jobName) {
        if (jobName == null) {
            return true;
        }
        if (kubernetesClient == null) {
            return false;
        }
        try {
            // Foreground, explicitly.
            //
            // fabric8 6.10.0 sends Background when no policy is given
            // (HasMetadataOperationsImpl.defaultContext applies withPropagationPolicy(
            // BACKGROUND) because BaseClient.getOperationContext() is null for a client
            // built by KubernetesClientBuilder). Background deletes the owner FIRST and
            // reaps dependents asynchronously, so the Job's disappearance would say
            // nothing at all about its pod. Foreground keeps the Job alive behind a
            // foregroundDeletion finalizer until its dependents are gone, which makes the
            // Job's absence a truthful signal -- though still not a sufficient one, which
            // is why every caller also takes a pod census.
            kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withName(jobName)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete();
        } catch (Exception e) {
            log.warn("Failed to delete Kubernetes Job: " + jobName, e);
        }
        // The delete RESULT is deliberately not consulted. A non-empty StatusDetails list
        // means "the API accepted the request", and under Foreground propagation that is
        // returned at the exact moment the Job is still present with its finalizer
        // attached. This method used to return true on that result without reading, so it
        // answered a different question from the one its name asks. Only a read can
        // answer this one.
        return !kubernetesJobExists(jobName);
    }

    /** A Job observation that keeps "absent" and "cannot tell" apart. */
    private enum JobPresence {
        PRESENT,
        ABSENT,
        /** The API could not be read. Never readable as either of the above. */
        UNDETERMINED,
    }

    /**
     * Observes a Job without collapsing uncertainty into a verdict.
     *
     * <p>{@link #kubernetesJobExists} deliberately reports an unreadable API as "present",
     * which is the right fail-closed answer for a caller asking "may I proceed". It is the
     * wrong answer for a caller that must distinguish "the Job is really there" from "I
     * cannot see", because those call for different actions and logging them alike misleads
     * whoever reads the result.
     */
    private JobPresence observeJob(String jobName) {
        if (kubernetesClient == null || jobName == null) {
            return JobPresence.UNDETERMINED;
        }
        try {
            Job existing = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withName(jobName)
                .get();
            return existing == null ? JobPresence.ABSENT : JobPresence.PRESENT;
        } catch (Exception e) {
            log.warn("Could not observe Kubernetes Job " + jobName, e);
            return JobPresence.UNDETERMINED;
        }
    }

    private boolean kubernetesJobExists(String jobName) {
        // Cannot tell is reported as still present, so a caller asking "may I proceed"
        // retries rather than acting on an assumption it cannot support.
        return observeJob(jobName) != JobPresence.ABSENT;
    }

    /**
     * Generate a Kubernetes-compliant job name from execution ID.
     */
    private String generateJobName(int execId) {
        return String.format(
            "starexec-job-%d-%d",
            execId,
            System.currentTimeMillis() % 100000
        );
    }

    /**
     * Kill a specific job by execution ID.
     *
     * @param execId The execution ID of the job to kill
     * @return true if successful, false otherwise
     */
    @Override
    public boolean killPair(int execId) {
        String jobName = execIdToJobName.get(execId);
        if (jobName == null) {
            log.info("No job found for execId: " + execId);
            return false;
        }

        if (kubernetesClient == null) {
            log.warn(
                "Kubernetes client not initialized; cannot kill execId: " +
                execId +
                ". Local tracking preserved for retry or destroyIf cleanup."
            );
            return false;
        }

        log.info("Killing K8s Job: execId=" + execId + ", jobName=" + jobName);
        return killPairConfirmed(execId) == KillOutcome.CONFIRMED_SAFE;
    }

    /**
     * Kills an execution and reports whether its pods are provably incapable of running.
     *
     * <p>This is the Kubernetes implementation of the strict contract. It is opt-in
     * precisely because the legacy {@code killPair} boolean cannot be reinterpreted:
     * {@code LocalBackend} returns false when the job is not in its map and
     * {@code PodmanBackend} returns false when the container is absent or has already
     * exited, and in both cases false means <em>already gone</em>, which is the safe case.
     * Reading those as "unproven" would strand pairs on every other backend.
     *
     * <p>On {@code UNPROVEN} the submission slot and every tracking map entry are left
     * intact. That is deliberate: this method is on the rerun path, so a surviving pod is
     * a literal duplicate against the replacement execution, and forgetting it would also
     * remove it from the concurrency accounting that keeps unrelated pairs off the same
     * hardware.
     */
    @Override
    public KillOutcome killPairConfirmed(int execId) {
        String jobName = execIdToJobName.get(execId);
        if (jobName == null) {
            // Absent local bookkeeping is NOT proof that the execution stopped. This is
            // exactly the state left by a restart or partial state loss, which is when a
            // pod is most likely to be running unseen. So still ask the cluster.
            //
            // The Job cannot be named here, and must not be guessed. generateJobName
            // appends System.currentTimeMillis() % 100000, so it is NOT a pure function of
            // the execution id: a name derived now would not match the one the Job was
            // created with, and asking about it would report a live Job as absent. An
            // earlier version of this branch did exactly that.
            //
            // Neither observation needs a name. Both select on the exec-id LABEL, which the
            // Job's own metadata and the pod template both carry, so they are
            // identity-correct with no local state at all.
            //
            // A pod census alone is NOT enough here. A Job is a controller: with no pod
            // visible it can still be between attempts and about to create the next one, and
            // releasing on the strength of an empty census would authorize a replacement
            // alongside it. The controller must be proven spent as well.
            if (kubernetesClient == null) {
                log.warn(
                    "No tracked Kubernetes job for execId " + execId + " and no client;" +
                    " cannot establish that it stopped"
                );
                return KillOutcome.UNPROVEN;
            }
            if (observeExecutionSafety(execId) == KillOutcome.CONFIRMED_SAFE) {
                log.info(
                    "execId " + execId + " has no local tracking; its controller is spent" +
                    " and the cluster confirms nothing for it can run"
                );
                // A LEGACY tombstone, not a cancellation. What the census established is
                // that nothing carrying this exec-id LABEL survives; the label is not an
                // identity, and the counter behind it restarts at 1 in every application
                // lifetime, so this says nothing about a Job created later that inherits the
                // number. Recording it as a concrete cancellation is what discarded a live
                // execution's callbacks on 2026-09-05.
                legacyKilledExecIds.add(execId);
                // Clear ALL tracking, not just the slot. Partial state loss is exactly what
                // put us in this branch, and it is not selective: only execIdToJobName may
                // have been lost while the pair and output-dir entries and the reservation
                // all survived. Every one of these is idempotent, so this is a no-op for
                // whichever entries were already gone, and it leaves the same clean state
                // the ordinary successful kill path does.
                execIdToJobName.remove(execId);
                execIdToJobUid.remove(execId);
                execIdToPairId.remove(execId);
                execIdToOutputDir.remove(execId);
                ambiguousSubmissions.remove(execId);
                unverifiedExecutions.remove(execId);
                releaseSubmissionSlot(execId);
                return KillOutcome.CONFIRMED_SAFE;
            }
            log.error(
                "execId " + execId + " has no local tracking and the cluster cannot" +
                " confirm it stopped (controller " + observeControllerFor(execId) +
                ", pods " + censusFor(execId) + ")." +
                " Reporting UNPROVEN so no replacement is dispatched. OPERATOR ACTION:" +
                " inspect Jobs and pods labelled " + EXEC_ID_LABEL + "=" + execId +
                " in namespace " + namespace + "."
            );
            // Bring the discovered live execution back into the accounting rather than
            // leaving it invisible.
            //
            // The retry owner is the surviving Kubernetes object, and it is a real one --
            // unlike the ambiguous-create case, where nothing exists to enumerate. Whichever
            // half was unproven (a live Job, a live pod, or an unreadable API) is discoverable
            // by the exec-id label, so the recurring inventory keeps finding it and can act on
            // it. No reconstructed Job name is needed, which is just as well, because none can
            // be reconstructed. Reported as an unidentified execution because it was found in
            // the cluster rather than in StarExec's own records.
            restoreSubmissionSlot(execId, true);
            recordUnverified(execId, "kill without local tracking");
            return KillOutcome.UNPROVEN;
        }
        if (kubernetesClient == null) {
            log.warn(
                "Kubernetes client not initialized; cannot establish that execId " + execId +
                " has stopped. Local tracking preserved."
            );
            return KillOutcome.UNPROVEN;
        }

        if (!ensureKubernetesJobGone(jobName)) {
            log.error(
                "Kubernetes Job " + jobName + " (execId " + execId + ") is still present" +
                " after a foreground delete; refusing to release its accounting because a" +
                " pod for it may still execute and a replacement could then run twice."
            );
            return KillOutcome.UNPROVEN;
        }

        // The named delete having succeeded is not the end of it. Deleting one Job by name
        // does not establish that no controller for this execution survives -- a retried
        // submission can have left a second one -- so the execution-level check runs anyway.
        if (observeExecutionSafety(execId) != KillOutcome.CONFIRMED_SAFE) {
            log.error(
                "Cannot establish that execId " + execId + " (K8s job " + jobName +
                ") has stopped (controller " + observeControllerFor(execId) +
                ", pods " + censusFor(execId) + "). Its submission slot and tracking are" +
                " retained so unrelated pairs are not scheduled on top of it. OPERATOR" +
                " ACTION: inspect Jobs and pods labelled " + EXEC_ID_LABEL + "=" + execId +
                " in namespace " + namespace + "."
            );
            recordUnverified(execId, "kill of " + jobName);
            return KillOutcome.UNPROVEN;
        }

        recordExecutionStopped(trackedExecution(execId), execId, "kill of " + jobName);
        execIdToJobName.remove(execId);
        execIdToJobUid.remove(execId);
        execIdToPairId.remove(execId);
        execIdToOutputDir.remove(execId);
        ambiguousSubmissions.remove(execId);
        unverifiedExecutions.remove(execId);
        releaseSubmissionSlot(execId);
        return KillOutcome.CONFIRMED_SAFE;
    }

    /**
     * A fresh pod census for one execution, using the authoritative identity when one is
     * available.
     *
     * <p>The execution id is authoritative. A pair id is stable across reruns, so a
     * pair-id census also matches pods of earlier attempts: safe is still sound there
     * (nothing for any attempt can run), but unsafe must never be reported as identifying
     * the current execution.
     */
    private PodPhaseView.Census censusFor(int execId) {
        return PodPhaseView.censusByExecId(
            kubernetesClient, namespace, MANAGED_LABEL, EXEC_ID_LABEL, execId
        );
    }

    private PodPhaseView.Census censusForPair(int pairId) {
        return PodPhaseView.censusByPairId(
            kubernetesClient, namespace, MANAGED_LABEL, PAIR_ID_LABEL, pairId
        );
    }

    /** Whether any controller for an execution could still create a Pod. */
    private enum ControllerSafety {
        /** Established: no Job for this execution can create another Pod. */
        SPENT,
        /** Not established, for any reason. Never readable as SPENT. */
        UNPROVEN,
    }

    /**
     * Observes every Job belonging to an execution, by label rather than by name.
     *
     * <p>{@code buildKubernetesJob} applies {@code {managed, exec-id, pair-id}} to the Job's
     * own metadata as well as the pod template, from a single map, so the controller is
     * recoverable server-side with no local state. That matters because the in-memory job
     * name is exactly what is missing in the cases this method exists for.
     *
     * <p>{@link #generateJobName(int)} must <strong>never</strong> be used to reconstruct an
     * identity: it appends {@code System.currentTimeMillis() % 100000}, so it is not a
     * reversible mapping, and a derived name would report a live Job as absent — authorizing
     * a replacement alongside a running execution.
     *
     * <p>Zero results from a <em>successful</em> listing is positive evidence of absence. It
     * is not evidence that the execution never ran: {@code ttlSecondsAfterFinished} reaps
     * completed Jobs.
     */
    private ControllerSafety observeControllerFor(int execId) {
        if (kubernetesClient == null) {
            return ControllerSafety.UNPROVEN;
        }
        try {
            Map<String, String> selector = new HashMap<>();
            selector.put(MANAGED_LABEL, "true");
            selector.put(EXEC_ID_LABEL, String.valueOf(execId));

            JobList list = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabels(selector)
                .list();

            if (list == null || list.getItems() == null) {
                // A null body is not an empty result. Treating it as absence would turn an
                // unreadable API into proof that nothing is running.
                log.warn(
                    "Job listing for exec-id " + execId + " returned no body; controller" +
                        " safety cannot be established."
                );
                return ControllerSafety.UNPROVEN;
            }

            for (Job job : list.getItems()) {
                if (!isControllerSpent(job)) {
                    log.info(
                        "Job " + getJobName(job) + " for exec-id " + execId +
                            " is not spent; it may still create a Pod."
                    );
                    return ControllerSafety.UNPROVEN;
                }
            }
            return ControllerSafety.SPENT;
        } catch (Exception e) {
            log.warn("Could not list Jobs for exec-id " + execId, e);
            return ControllerSafety.UNPROVEN;
        }
    }

    /**
     * The execution-level safety check every release site must pass.
     *
     * <p>Both halves are required, and neither implies the other. A spent controller can
     * still have a terminating Pod; a safe Pod census says nothing about a controller that is
     * about to create the next one.
     *
     * <p>Anything unknown or unobservable is {@link Backend.KillOutcome#UNPROVEN}: an active
     * Job, a listing that failed, an identity that cannot be recovered, a Pending or Running
     * Pod, an {@code Unknown} Pod phase, or a null API response.
     */
    private Backend.KillOutcome observeExecutionSafety(int execId) {
        ControllerSafety controller = observeControllerFor(execId);
        if (controller != ControllerSafety.SPENT) {
            return Backend.KillOutcome.UNPROVEN;
        }
        PodPhaseView.Census census = censusFor(execId);
        if (census == null || !census.isSafe()) {
            log.info(
                "Execution " + execId + " has a spent controller but its pod census is " +
                    (census == null ? "unavailable" : census.describe()) +
                    "; safety is not established."
            );
            return Backend.KillOutcome.UNPROVEN;
        }
        return Backend.KillOutcome.CONFIRMED_SAFE;
    }

    /**
     * Kill all running jobs.
     *
     * @return true if successful, false otherwise
     */
    @Override
    public boolean killAll() {
        log.info("Killing all K8s Jobs in namespace: " + namespace);

        if (kubernetesClient == null) {
            // Nothing can be established without a client, so nothing is released. This
            // used to clear every slot and map here, which would have declared executions
            // dead on the strength of the client being absent.
            log.warn(
                "Kubernetes client not initialized; cannot establish that any execution" +
                " has stopped. Tracking and submission slots are retained."
            );
            return false;
        }

        // The bulk delete is only the REQUEST. It is not evidence, and its result is not
        // consulted: a label-selector delete reports that the API accepted it, not that
        // any pod has stopped.
        try {
            kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(MANAGED_LABEL, "true")
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete();
        } catch (Exception e) {
            log.error("Failed to request deletion of all Kubernetes jobs", e);
        }

        // Then decide PER EXECUTION. This method used to call releaseAllSlots() and clear
        // all three maps in a finally block, which is a violation of the accounting
        // invariant during the current process lifetime -- not merely across a restart.
        // Its only caller is Jobs.pauseAll (admin endpoint RESTServices /pauseAll), so it
        // runs in a live process that can still admit work, and pausing is reversible.
        // Forgetting a surviving pod here lets unrelated benchmark pairs be scheduled
        // beside it and contaminates THEIR measurements.
        List<Integer> tracked = new ArrayList<>(execIdToJobName.keySet());
        int released = 0;
        int retained = 0;
        for (Integer execId : tracked) {
            String jobName = execIdToJobName.get(execId);
            boolean jobGone = ensureKubernetesJobGone(jobName);
            PodPhaseView.Census census = jobGone
                ? censusFor(execId)
                : null;

            if (jobGone && census != null && census.isSafe()) {
                recordExecutionStopped(
                    trackedExecution(execId), execId, "killAll of " + jobName
                );
                execIdToJobName.remove(execId);
                execIdToJobUid.remove(execId);
                execIdToPairId.remove(execId);
                execIdToOutputDir.remove(execId);
                releaseSubmissionSlot(execId);
                released++;
            } else {
                retained++;
                log.error(
                    "Cannot establish that execId " + execId + " (K8s job " + jobName +
                    ") has stopped during killAll: " +
                    (jobGone ? String.valueOf(census) : "the Job is still present") +
                    ". Its submission slot and tracking are RETAINED so unrelated pairs" +
                    " are not scheduled on top of it. OPERATOR ACTION: inspect pods" +
                    " labelled " + EXEC_ID_LABEL + "=" + execId + " in namespace " +
                    namespace + "."
                );
            }
        }

        log.info(
            "killAll complete: " + released + " execution(s) confirmed stopped and" +
            " released, " + retained + " retained pending verification"
        );
        return retained == 0;
    }

    /**
     * Get status of all running jobs.
     *
     * @return Status string describing running jobs
     */
    @Override
    public String getRunningJobsStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Kubernetes Jobs Status ===\n");
        sb.append("Namespace: ").append(namespace).append("\n");

        try {
            JobList jobs = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(MANAGED_LABEL, "true")
                .list();

            sb.append("Tracked Jobs: ").append(jobs.getItems().size()).append("\n\n");
            for (Job job : jobs.getItems()) {
                String jobName = job.getMetadata() != null ? job.getMetadata().getName() : "unknown";
                String status = summarizeJobStatus(job);
                sb.append(jobName).append(": ").append(status).append("\n");
            }
        } catch (Exception e) {
            sb.append("Failed to query jobs: ").append(e.getMessage()).append("\n");
            log.warn("Failed to query running Kubernetes jobs", e);
        }

        return sb.toString();
    }

    private String summarizeJobStatus(Job job) {
        if (job.getStatus() == null) {
            return "pending";
        }

        Integer succeeded = job.getStatus().getSucceeded();
        if (succeeded != null && succeeded > 0) {
            return "succeeded";
        }

        Integer failed = job.getStatus().getFailed();
        if (failed != null && failed > 0) {
            return "failed";
        }

        Integer active = job.getStatus().getActive();
        if (active != null && active > 0) {
            return "running";
        }

        return "pending";
    }

    /**
     * Get all active execution IDs.
     *
     * @return Set of active execution IDs
     */
    @Override
    public Set<Integer> getActiveExecutionIds() throws IOException {
        Set<Integer> activeIds = new HashSet<>();

        try {
            JobList jobs = kubernetesClient
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(MANAGED_LABEL, "true")
                .list();

            for (Job job : jobs.getItems()) {
                if (job.getStatus() != null) {
                    Integer succeeded = job.getStatus().getSucceeded();
                    Integer failed = job.getStatus().getFailed();
                    if ((succeeded != null && succeeded > 0) || (failed != null && failed > 0)) {
                        continue;
                    }
                }

                if (job.getMetadata() == null || job.getMetadata().getLabels() == null) {
                    continue;
                }

                String execIdValue = job.getMetadata().getLabels().get(EXEC_ID_LABEL);
                if (execIdValue == null || execIdValue.trim().isEmpty()) {
                    continue;
                }

                try {
                    activeIds.add(Integer.parseInt(execIdValue));
                } catch (NumberFormatException e) {
                    log.warn("Skipping job with malformed exec ID label: " + execIdValue);
                }
            }
            return activeIds;
        } catch (Exception e) {
            log.error("Failed to query active execution IDs from Kubernetes", e);
            throw new IOException("Failed to query active execution IDs", e);
        }
    }

    // =========================================================================
    // Node and Queue Management
    // =========================================================================

    /**
     * Get all worker nodes in the Kubernetes cluster.
     *
     * <p>Returns nodes that are labeled for StarExec workloads.</p>
     *
     * @return Array of node names
     */
    @Override
    public String[] getWorkerNodes() {
        log.debug("Getting worker nodes from Kubernetes cluster");

        try {
            NodeList nodes = kubernetesClient
                .nodes()
                .withLabel(workerNodeSelectorKey, workerNodeSelectorValue)
                .list();

            List<String> nodeNames = new ArrayList<>();
            for (Node node : nodes.getItems()) {
                if (node.getMetadata() != null && node.getMetadata().getName() != null) {
                    nodeNames.add(node.getMetadata().getName());
                }
            }
            return nodeNames.toArray(new String[0]);
        } catch (Exception e) {
            log.warn("Failed to read Kubernetes worker nodes", e);
            return new String[0];
        }
    }

    /**
     * Get all queues (derived from node labels).
     *
     * @return Array of queue names
     */
    @Override
    public String[] getQueues() {
        log.debug("Getting queues from Kubernetes node labels");

        Set<String> queues = new HashSet<>();

        NodeList nodes;
        try {
            nodes = kubernetesClient
                .nodes()
                .withLabel(workerNodeSelectorKey, workerNodeSelectorValue)
                .list();
        } catch (Exception e) {
            // Never fabricate an answer here. Cluster.loadQueueDetails marks every queue
            // INACTIVE and reactivates only the names this returns, so swallowing the
            // failure and returning {all.q} would deactivate every real queue in the
            // database on one transient API blip -- and each subsequent run would repeat
            // it, so nothing self-heals. Throwing lets loadQueueDetails abort before it
            // has mutated anything; same principle as queueViewLoaded, which exists so an
            // unread view is never mistaken for an empty one.
            throw new IllegalStateException(
                "Could not enumerate Kubernetes worker nodes; refusing to report a queue" +
                " list that would deactivate existing queues",
                e
            );
        }

        for (Node node : nodes.getItems()) {
            // Every reader of this label except this method already treats absent and
            // blank as first-class default-queue membership: refreshQueueViewIfStale and
            // getNodeQueueAssociations both normalize unconditionally, and
            // defaultQueueAffinity's DoesNotExist term exists precisely to schedule onto
            // unlabelled nodes. This method used to skip them instead, so the moment an
            // admin created a second queue while any node stayed unlabelled, all.q
            // vanished from the returned set and loadQueueDetails left it INACTIVE --
            // silently, permanently, with every pair submitted to it simply never
            // dispatching. Normalizing here makes the five views agree.
            String queue = (node.getMetadata() == null || node.getMetadata().getLabels() == null)
                ? null
                : node.getMetadata().getLabels().get(queueLabelKey);
            queues.add(normalizeQueueLabel(queue));
        }

        // Reachable only when the cluster has no worker nodes at all; a node that exists
        // always contributes a queue name now.
        if (queues.isEmpty()) {
            queues.add(DEFAULT_QUEUE_NAME);
        }

        return queues.toArray(new String[0]);
    }

    /**
     * Get mapping of nodes to queues.
     *
     * @return Map of node name to queue name
     */
    @Override
    public Map<String, String> getNodeQueueAssociations() {
        Map<String, String> associations = new HashMap<>();

        try {
            NodeList nodes = kubernetesClient
                .nodes()
                .withLabel(workerNodeSelectorKey, workerNodeSelectorValue)
                .list();

            for (Node node : nodes.getItems()) {
                if (node.getMetadata() == null || node.getMetadata().getName() == null) {
                    continue;
                }

                String queueName = DEFAULT_QUEUE_NAME;
                if (node.getMetadata().getLabels() != null) {
                    queueName = normalizeQueueLabel(
                        node.getMetadata().getLabels().get(queueLabelKey)
                    );
                }

                associations.put(node.getMetadata().getName(), queueName);
            }
        } catch (Exception e) {
            log.warn("Failed to read node/queue associations from Kubernetes", e);
        }

        return associations;
    }

    /**
     * Clear any error states on nodes.
     *
     * <p>For Kubernetes, this could uncordon nodes or clear taints.</p>
     *
     * @return true if successful
     */
    @Override
    public boolean clearNodeErrorStates() {
        log.info("clearNodeErrorStates called for Kubernetes backend");

        try {
            NodeList nodes = kubernetesClient
                .nodes()
                .withLabel(workerNodeSelectorKey, workerNodeSelectorValue)
                .list();

            for (Node node : nodes.getItems()) {
                if (node.getMetadata() == null || node.getMetadata().getName() == null) {
                    continue;
                }
                NodeSpec spec = node.getSpec();
                if (spec != null && Boolean.TRUE.equals(spec.getUnschedulable())) {
                    kubernetesClient
                        .nodes()
                        .withName(node.getMetadata().getName())
                        .edit(n -> {
                            if (n.getSpec() != null) {
                                n.getSpec().setUnschedulable(false);
                            }
                            return n;
                        });
                }
            }

            return true;
        } catch (Exception e) {
            log.error("Failed to clear node error states in Kubernetes", e);
            return false;
        }
    }

    /**
     * Delete a queue by removing labels from nodes.
     *
     * @param queueName Name of the queue to delete
     */
    @Override
    public void deleteQueue(String queueName) {
        log.info("Deleting queue label from nodes for queue: " + queueName);

        try {
            NodeList nodes = kubernetesClient
                .nodes()
                .withLabel(workerNodeSelectorKey, workerNodeSelectorValue)
                .withLabel(queueLabelKey, queueName)
                .list();

            for (Node node : nodes.getItems()) {
                if (node.getMetadata() == null || node.getMetadata().getName() == null) {
                    continue;
                }

                kubernetesClient
                    .nodes()
                    .withName(node.getMetadata().getName())
                    .edit(n -> {
                        if (n.getMetadata() != null && n.getMetadata().getLabels() != null) {
                            n.getMetadata().getLabels().remove(queueLabelKey);
                        }
                        return n;
                    });
            }
        } catch (Exception e) {
            log.error("Failed to delete queue from Kubernetes nodes: " + queueName, e);
        }
    }

    /**
     * Create a new queue by labeling nodes.
     *
     * @param newQueueName Name for the new queue
     * @param nodeNames Nodes to assign to the queue
     * @param sourceQueueNames Previous queue assignments (ignored)
     * @return true if successful
     */
    @Override
    public boolean createQueue(
        String newQueueName,
        String[] nodeNames,
        String[] sourceQueueNames
    ) {
        return createQueueWithSlots(
            newQueueName,
            nodeNames,
            sourceQueueNames,
            null
        );
    }

    /**
     * Create a queue with slot configuration.
     *
     * @param newQueueName Name for the new queue
     * @param nodeNames Nodes to assign
     * @param sourceQueueNames Previous assignments (ignored)
     * @param slots Number of slots (stored as annotation)
     * @return true if successful
     */
    @Override
    public boolean createQueueWithSlots(
        String newQueueName,
        String[] nodeNames,
        String[] sourceQueueNames,
        Integer slots
    ) {
        if (nodeNames == null || nodeNames.length == 0) {
            log.warn("No nodes specified for queue creation");
            return false;
        }

        log.info(
            "Creating queue '" +
                newQueueName +
                "' with " +
                nodeNames.length +
                " nodes, " +
                slots +
                " slots"
        );

        try {
            for (String nodeName : nodeNames) {
                kubernetesClient
                    .nodes()
                    .withName(nodeName)
                    .edit(n -> {
                        if (n.getMetadata() == null) {
                            return n;
                        }
                        if (n.getMetadata().getLabels() == null) {
                            n.getMetadata().setLabels(new HashMap<>());
                        }
                        n.getMetadata().getLabels().put(queueLabelKey, newQueueName);
                        if (slots != null) {
                            n.getMetadata().getLabels().put(LABEL_PREFIX + "slots", String.valueOf(slots));
                        }
                        return n;
                    });
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to create queue in Kubernetes: " + newQueueName, e);
            return false;
        }
    }

    /**
     * Move nodes between queues.
     *
     * @param destQueueName Destination queue
     * @param nodeNames Nodes to move
     * @param sourceQueueNames Source queues (ignored)
     */
    @Override
    public void moveNodes(
        String destQueueName,
        String[] nodeNames,
        String[] sourceQueueNames
    ) {
        if (nodeNames == null || nodeNames.length == 0) {
            return;
        }

        log.info(
            "Moving " +
                nodeNames.length +
                " nodes to queue '" +
                destQueueName +
                "'"
        );

        for (String nodeName : nodeNames) {
            try {
                kubernetesClient
                    .nodes()
                    .withName(nodeName)
                    .edit(n -> {
                        if (n.getMetadata() == null) {
                            return n;
                        }
                        if (n.getMetadata().getLabels() == null) {
                            n.getMetadata().setLabels(new HashMap<>());
                        }

                        // Accepts either spelling of the default queue. Queues.removeQueue
                        // passes the SGE short form "all", which previously failed this
                        // test and was written as a literal label value.
                        if (isDefaultQueueName(destQueueName)) {
                            n.getMetadata().getLabels().remove(queueLabelKey);
                        } else {
                            n.getMetadata().getLabels().put(queueLabelKey, destQueueName);
                        }
                        return n;
                    });
            } catch (Exception e) {
                log.error("Failed to move node to queue: node=" + nodeName + ", queue=" + destQueueName, e);
            }
        }
    }

    /**
     * Move a single node to a queue.
     *
     * @param nodeName Node to move
     * @param queueName Destination queue
     */
    @Override
    public void moveNode(String nodeName, String queueName) {
        moveNodes(queueName, new String[] { nodeName }, null);
    }

    @Override
    public void clearPairTracking(int pairId) {
        // Remove mapping so stale pair IDs are not returned by resolvePairId
        // after a pair has been rerun or removed.
        execIdToPairId.values().removeIf(v -> v.equals(pairId));
    }

    private final class KubernetesJobCompletionCallback
        implements KubernetesJobMonitor.JobCompletionCallback {

        @Override
        public boolean onJobRunning(ExecutionRef execution) {
            if (isStopped(execution)) {
                log.debug("Skipping running callback for stopped " + execution);
                return true;
            }

            Integer pairId = resolvePairId(execution);
            if (pairId == null) {
                log.warn("Unable to resolve pair ID for active job: " + execution);
                return false;
            }

            return markPairRunningSafely(pairId, "Kubernetes active-job polling") !=
                JobPairs.ConditionalPairUpdateResult.ERROR;
        }

        @Override
        public boolean onJobComplete(ExecutionRef execution) {
            // Skip processing if THIS execution was stopped — the kill path already removed
            // its tracking maps and released its concurrency slot. Another execution having
            // been stopped under the same number is not this one's business.
            if (isStopped(execution)) {
                log.debug("Skipping completion callback for stopped " + execution);
                killedExecutions.remove(execution);
                return true;
            }

            // A superseded execution publishes nothing. Its pair id can still be read from
            // its own Job label, but everything else this path needs -- the output
            // directory the status, stats and attributes are read from, the submission slot
            // -- is keyed on the execution id, and the id now belongs to another execution.
            // Applying a result from those artifacts would record one execution's run
            // against the other's pair.
            if (isSuperseded(execution)) {
                log.warn(
                    "Not applying the completion of " + execution + ": execution id " +
                    execution.execId() + " is now held by " +
                    execIdToJobName.get(execution.execId()) + ", whose artifacts and" +
                    " accounting this event must not touch."
                );
                return true;
            }

            String jobName = execution.jobName();
            Integer pairId = resolvePairId(execution);
            if (pairId == null) {
                log.warn("Unable to resolve pair ID for completed job: " + execution);
                return false;
            }

            try {
                // Guard: skip DB update when the pair row has disappeared or the
                // job is no longer submit-eligible. Without this check a K8s job
                // that completes after pause/kill/delete writes a status into a
                // stale or absent row, causing P0002 or overwriting a terminal.
                JobPairs.PairStatusLookupResult lookup = JobPairs.getPairStatusLookup(pairId);
                if (lookup.isMissing()) {
                    log.debug(
                        "Skipping completion update for stale pair " +
                        pairId +
                        " (K8s job " +
                        jobName +
                        ")"
                    );
                    releaseAccountingIfSafe(execution, "terminal callback for " + execution);
                    return true;
                }
                if (lookup.isError()) {
                    log.warn(
                        "Could not determine whether pair " +
                        pairId +
                        " still exists after K8s job completion; retrying"
                    );
                    return false;
                }

                int terminalStatus = readTerminalStatus(execution, StatusCode.STATUS_COMPLETE.getVal());
                int stageNumber = readStageNumber(execution, 1);

                // Earlier stages, from the per-stage snapshots the job script writes beside
                // status.json. That file is a single slot every stage truncates, so without
                // this the pair keeps only its final stage and every earlier one stays at
                // whatever it was enqueued with.
                if (!ingestEarlierStageStatuses(execution, pairId, stageNumber)) {
                    return false;
                }

                PairStatusResult updated = JobPairs.setPairStatusPreciseResult(
                    pairId,
                    stageNumber,
                    terminalStatus,
                    StatusCode.STATUS_NOT_REACHED.getVal(),
                    false
                );
                if (updated == PairStatusResult.FAILED) {
                    log.warn(
                        "Failed updating completed status for pair " +
                        pairId +
                        "; Kubernetes completion will be retried"
                    );
                    return false;
                }
                if (updated == PairStatusResult.SUPERSEDED) {
                    // Another writer already recorded a different terminal result, so
                    // this pair is finished and retrying can never succeed. Returning
                    // false here would leave the execution out of completedExecutions and the
                    // next poll would process the same job again, forever. Treat it as
                    // handled so the Kubernetes job is cleaned up.
                    log.info(
                        "Pair " + pairId +
                        " already had a different terminal status; keeping the recorded" +
                        " result and cleaning up the Kubernetes job"
                    );
                }

                // end_time is deliberately NOT set here. UpdatePairStatusPrecise writes it in
                // the same transaction as the status, guarded on IS NULL and outside its
                // duplicate branch, precisely so a retried write repairs a pair whose first
                // attempt died. This used to call setEndTime unconditionally afterwards -- a
                // bare "SET end_time = NOW()" -- which was harmless while completion ran once,
                // but stage-status ingestion makes a retry an ordinary event, and a pair's
                // recorded finish time must not move every time one happens.
                //
                // Only the completion path changes. onJobFailed and onJobStuckPending keep
                // their own calls: those record statuses through routines that do not write
                // end_time themselves.

                // Persist run-solver statistics (wallclock, cpu, memory, disk)
                // so K8s-native jobs produce the same data as container jobs.
                persistRunSolverStats(execution, pairId, stageNumber);

                // Persist attributes generated by post-processors
                persistAttributes(execution, pairId, stageNumber);
            } catch (StageStatusSnapshots.InvalidSnapshotException e) {
                // No usable stage identity, so nothing was written and nothing will be. The
                // pair keeps its non-terminal status and its output stays on the PVC.
                //
                // Reported as handled rather than retried: returning false leaves the
                // execution out of completedExecutions and the next poll processes the same
                // job against the same bytes, forever. Accounting is released the same way
                // every other exit from this callback releases it -- the execution has
                // finished either way, and holding its slot would leak capacity per pair.
                log.error(
                    "INGESTION INTERVENTION REQUIRED: pair " + pairId + " (" + execution +
                    ") finished with no usable stage identity in status.json. The pair is" +
                    " left unresolved and its output is retained. No stage has been invented" +
                    " and no solver status recorded.", e);
                releaseAccountingIfSafe(execution, "unusable stage identity for " + execution);
                return true;
            } catch (Exception e) {
                log.error("Failed updating completed status for pair " + pairId, e);
                return false;
            }

            releaseAccountingIfSafe(execution, "completion of " + jobName);
            return true;
        }

        @Override
        public boolean onJobFailed(ExecutionRef execution, String reason) {
            // Skip processing if THIS execution was stopped — the kill path already removed
            // its tracking maps and released its concurrency slot.
            if (isStopped(execution)) {
                log.debug("Skipping failure callback for stopped " + execution);
                killedExecutions.remove(execution);
                return true;
            }

            // A superseded execution publishes nothing. Its pair id can still be read from
            // its own Job label, but everything else this path needs -- the output
            // directory the status, stats and attributes are read from, the submission slot
            // -- is keyed on the execution id, and the id now belongs to another execution.
            // Applying a result from those artifacts would record one execution's run
            // against the other's pair.
            if (isSuperseded(execution)) {
                log.warn(
                    "Not applying the failure of " + execution + ": execution id " +
                    execution.execId() + " is now held by " +
                    execIdToJobName.get(execution.execId()) + ", whose artifacts and" +
                    " accounting this event must not touch."
                );
                return true;
            }

            String jobName = execution.jobName();
            Integer pairId = resolvePairId(execution);
            if (pairId == null) {
                log.warn("Unable to resolve pair ID for failed job: " + execution + ". Reason: " + reason);
                return false;
            }

            try {
                // Guard: skip DB update when the pair row has disappeared or the
                // job is no longer submit-eligible.
                JobPairs.PairStatusLookupResult lookup = JobPairs.getPairStatusLookup(pairId);
                if (lookup.isMissing()) {
                    log.debug(
                        "Skipping failure update for stale pair " +
                        pairId +
                        " (K8s job " +
                        jobName +
                        ")"
                    );
                    releaseAccountingIfSafe(execution, "terminal callback for " + execution);
                    return true;
                }
                if (lookup.isError()) {
                    log.warn(
                        "Could not determine whether pair " +
                        pairId +
                        " still exists after K8s job failure; retrying"
                    );
                    return false;
                }

                int stageNumber = readStageNumber(execution, 1);

                boolean updated = JobPairs.setPairStatusPrecise(
                    pairId,
                    stageNumber,
                    StatusCode.ERROR_RUNSCRIPT.getVal(),
                    StatusCode.STATUS_NOT_REACHED.getVal()
                );
                if (!updated) {
                    log.warn(
                        "Failed updating failed status for pair " +
                        pairId +
                        ". Reason: " +
                        reason +
                        "; Kubernetes completion will be retried"
                    );
                    return false;
                }

                // Set end_time for the failed pair.
                try {
                    if (!JobPairs.setEndTime(pairId)) {
                        log.warn("setEndTime found no row for failed pair " + pairId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to set end_time for failed pair " + pairId, e);
                }
            } catch (StageStatusSnapshots.InvalidSnapshotException e) {
                // As in the completion callback: nothing to attribute the failure to, and the
                // same bytes on every retry. Handled rather than re-polled.
                log.error(
                    "INGESTION INTERVENTION REQUIRED: the failure of pair " + pairId + " (" +
                    execution + ") could not be recorded because status.json carries no usable" +
                    " stage identity. Reason: " + reason + ". The pair is left unresolved and" +
                    " its output is retained.", e);
                releaseAccountingIfSafe(execution, "unusable stage identity for " + execution);
                return true;
            } catch (Exception e) {
                log.error("Failed updating failed status for pair " + pairId + ". Reason: " + reason, e);
                return false;
            }

            releaseAccountingIfSafe(execution, "terminal callback for " + execution);
            return true;
        }

        /**
         * A pod that has waited past the timeout without starting.
         *
         * <p>Recorded as ERROR_RUNSCRIPT because that is StarExec's existing bounded-retry
         * channel, not because a run script was missing. RERUN_FAILED_PAIRS reruns pairs at
         * exactly that code, and GetJobPairIdsWithStatusNotRerunAfterDate excludes anything
         * already in pairs_rerun, so the retry happens exactly once, is recorded in the
         * database, and survives a restart. Nothing ran, so retrying cannot contaminate a
         * measurement; if the second attempt also cannot be scheduled it stays failed and
         * visible.
         *
         * <p>The Kubernetes Job is deleted first. Left alone it would keep the pod pending,
         * and if capacity later appeared the pod would run and write results for a pair
         * StarExec has already accounted for.
         */
        @Override
        public boolean onJobStuckPending(ExecutionRef execution, String reason) {
            if (isStopped(execution)) {
                log.debug("Skipping stuck-pending callback for stopped " + execution);
                killedExecutions.remove(execution);
                return true;
            }

            // A superseded execution publishes nothing. Its pair id can still be read from
            // its own Job label, but everything else this path needs -- the output
            // directory the status, stats and attributes are read from, the submission slot
            // -- is keyed on the execution id, and the id now belongs to another execution.
            // Applying a result from those artifacts would record one execution's run
            // against the other's pair.
            if (isSuperseded(execution)) {
                log.warn(
                    "Not applying the stuck-pending escalation of " + execution + ": execution id " +
                    execution.execId() + " is now held by " +
                    execIdToJobName.get(execution.execId()) + ", whose artifacts and" +
                    " accounting this event must not touch."
                );
                return true;
            }

            int execId = execution.execId();
            String jobName = execution.jobName();
            Integer pairId = resolvePairId(execution);
            if (pairId == null) {
                log.warn(
                    "Unable to resolve pair ID for stuck job: " + execution + ". " + reason
                );
                return false;
            }

            try {
                JobPairs.PairStatusLookupResult lookup = JobPairs.getPairStatusLookup(pairId);
                if (lookup.isMissing()) {
                    log.debug(
                        "Skipping stuck-pending update for stale pair " +
                        pairId +
                        " (K8s job " +
                        jobName +
                        ")"
                    );
                    ensureKubernetesJobGone(jobName);
                    releaseAccountingIfSafe(execution, "terminal callback for " + execution);
                    return true;
                }
                if (lookup.isError()) {
                    log.warn(
                        "Could not determine whether pair " +
                        pairId +
                        " still exists after its pod failed to start; retrying"
                    );
                    return false;
                }

                // Order is load-bearing. The invariant: the Job is gone before anything
                // that makes this pair eligible for an automatic rerun is written.
                //
                // ERROR_RUNSCRIPT plus a non-null end_time is exactly what
                // GetJobPairIdsWithStatusNotRerunAfterDate selects, and RERUN_FAILED_PAIRS
                // dispatches a fresh execution for it. Both rerun paths now confirm the
                // old execution stopped before resetting, but neither can confirm anything
                // about a Job this callback has not yet deleted, so publishing that state
                // while the old Job still
                // exists leaves a pod that can start later and write a second set of
                // results for the same pair. Deleting first removes that possibility
                // rather than relying on the deletion retry winning a 90-minute race.
                //
                // The obvious objection to deleting first is that the Job is the monitor's
                // retry trigger, so a later failure could never be retried. That is why
                // KubernetesJobMonitor keeps its own cleanup-pending record and drains it
                // independently of the Job listing -- see drainCleanupPending. Every step
                // here is safe to repeat: ensureKubernetesJobGone reports an absent Job as
                // success, UpdatePairStatusPrecise treats a duplicate terminal write as
                // idempotent success by design, and setEndTime is an unconditional UPDATE.
                if (!ensureKubernetesJobGone(jobName)) {
                    log.warn(
                        "Kubernetes job " +
                        jobName +
                        " could not be deleted and is still present; it has not finished," +
                        " so ttlSecondsAfterFinished will not reap it. Leaving the pair" +
                        " untouched and retrying, so it cannot become rerun-eligible while" +
                        " a pod that may still start belongs to it."
                    );
                    return false;
                }

                // Deleting the NAMED Job is not the same as establishing that this execution
                // is over. A retried submission can have left a second controller carrying
                // the same exec-id label, and foreground propagation can return with the pod
                // still terminating. The status about to be written is precisely the one
                // that makes the pair rerun-eligible, so it must not be published while
                // anything for this execution can still run or write: a late pod would
                // otherwise overwrite the results of the replacement.
                if (observeExecutionSafety(execId) != KillOutcome.CONFIRMED_SAFE) {
                    log.warn(
                        "Pair " + pairId + " (execId " + execId + ") looks stuck, but its" +
                        " execution cannot be established as stopped (controller " +
                        observeControllerFor(execId) + ", pods " + censusFor(execId) + ")." +
                        " Not publishing a rerun-eligible status; the monitor's" +
                        " cleanup-pending record brings this back."
                    );
                    // The monitor keeps its cleanup-pending record because this returns
                    // false, so that record — not the safety sweep — owns finishing this
                    // pair's DB transition. Flagged so the sweep cannot release the
                    // tracking the continuation still needs.
                    recordUnverified(
                        execId, "stuck-pending escalation for pair " + pairId, true
                    );
                    return false;
                }

                int stageNumber = readStageNumber(execution, 1);

                boolean updated = JobPairs.setPairStatusPrecise(
                    pairId,
                    stageNumber,
                    StatusCode.ERROR_RUNSCRIPT.getVal(),
                    StatusCode.STATUS_NOT_REACHED.getVal()
                );
                if (!updated) {
                    log.warn(
                        "Failed recording stuck-pending status for pair " +
                        pairId +
                        "; the job is already gone, so the monitor's cleanup-pending" +
                        " record is what brings this back"
                    );
                    return false;
                }

                // Mandatory, not tidiness: GetJobPairIdsWithStatusNotRerunAfterDate also
                // requires (end_time >= cutoff OR end_time < epoch). With end_time NULL
                // both comparisons are NULL, the row is excluded, and the rerun this
                // status exists to trigger would silently never happen. That is why a
                // failure here returns rather than being logged and stepped over.
                boolean endTimeRecorded;
                try {
                    endTimeRecorded = JobPairs.setEndTime(pairId);
                } catch (Exception e) {
                    log.warn("Failed to set end_time for stuck pair " + pairId, e);
                    endTimeRecorded = false;
                }
                if (!endTimeRecorded) {
                    // The pair is ERROR_RUNSCRIPT with a null end_time, which the rerun
                    // query excludes -- so it is not yet rerun-eligible and no duplicate
                    // execution can be dispatched. The cleanup-pending record brings this
                    // back to finish the job.
                    log.warn(
                        "Could not record end_time for stuck pair " +
                        pairId +
                        "; without it the pair stays failed and is never rerun, so this" +
                        " is retried"
                    );
                    return false;
                }

                log.warn(
                    "Pair " +
                    pairId +
                    " never started: its pod waited past the configured timeout and the" +
                    " Kubernetes job has been removed so the pair can be rerun. " +
                    reason
                );
            } catch (StageStatusSnapshots.InvalidSnapshotException e) {
                // A pod that never started writes no status.json at all, and that case still
                // takes the default above -- this is a file that exists and cannot be used.
                log.error(
                    "INGESTION INTERVENTION REQUIRED: the stuck-pending escalation of pair " +
                    pairId + " (" + execution + ") could not be recorded because status.json" +
                    " carries no usable stage identity. The pair is left unresolved and its" +
                    " output is retained.", e);
                releaseAccountingIfSafe(execution, "unusable stage identity for " + execution);
                return true;
            } catch (Exception e) {
                log.error(
                    "Failed recording stuck-pending status for pair " + pairId,
                    e
                );
                return false;
            }

            releaseAccountingIfSafe(execution, "terminal callback for " + execution);
            return true;
        }

        /**
         * Whether this exact execution was stopped.
         *
         * <p>A legacy tombstone is deliberately not consulted. It records that some
         * execution holding this number was stopped, which is not evidence about the one
         * this event came from — and treating it as evidence is what suppressed a live
         * Job's callbacks on 2026-09-05.
         */
        private boolean isStopped(ExecutionRef execution) {
            if (killedExecutions.contains(execution)) {
                return true;
            }
            if (legacyKilledExecIds.contains(execution.execId())) {
                log.info(
                    "An unidentifiable earlier execution was stopped under execId " +
                    execution.execId() + "; " + execution + " is a different Kubernetes" +
                    " object and is being processed normally."
                );
            }
            return false;
        }

        /**
         * The pair this execution may write to.
         *
         * <p>{@code execIdToPairId} is consulted only while this execution is the one holding
         * the id. It is not an index of executions, it is the state of whichever execution
         * occupies the number now, so a late event from a superseded Job reading it would
         * write its result onto the current execution's pair.
         *
         * <p>Otherwise the Job's own {@code pair-id} label answers, which is identity-correct
         * because it is read from the object the event came from. That reading is not cached:
         * the map entry belongs to the current execution and would be overwritten with a
         * superseded one's pair.
         */
        private Integer resolvePairId(ExecutionRef execution) {
            int execId = execution.execId();
            if (ownsTracking(execution)) {
                Integer pairIdFromMap = execIdToPairId.get(execId);
                // Re-checked after the read. The tracking maps are not written atomically,
                // so the id can change hands between the two, and the value read would then
                // be the incoming execution's pair rather than this one's. Falling through
                // to the Job's own label is always correct; only the cache is in doubt.
                if (pairIdFromMap != null && pairIdFromMap > 0 && ownsTracking(execution)) {
                    return pairIdFromMap;
                }
            }

            try {
                Job job = kubernetesClient
                    .batch()
                    .v1()
                    .jobs()
                    .inNamespace(namespace)
                    .withName(execution.jobName())
                    .get();

                if (job == null || job.getMetadata() == null || job.getMetadata().getLabels() == null) {
                    return null;
                }

                // Same name, different object: a replacement Job created after this event's
                // Job was deleted. Its labels describe the replacement, not the caller.
                String uid = job.getMetadata().getUid();
                if (uid != null && !uid.equals(execution.jobUid())) {
                    log.warn(
                        "Job " + execution.jobName() + " now names a different object (" +
                        uid + ") than " + execution + "; not resolving a pair from it."
                    );
                    return null;
                }

                String pairIdValue = job.getMetadata().getLabels().get(PAIR_ID_LABEL);
                if (pairIdValue == null || pairIdValue.trim().isEmpty()) {
                    return null;
                }

                Integer parsed = Integer.parseInt(pairIdValue);
                if (ownsTracking(execution)) {
                    execIdToPairId.put(execId, parsed);
                }
                return parsed;
            } catch (Exception e) {
                log.warn("Failed to resolve pair ID for " + execution, e);
                return null;
            }
        }

        /**
         * The terminal status for a finished pair, with runsolver's own limit verdict
         * taking precedence over status.json.
         *
         * <p>status.json's status field is not an independent measurement: functions.bash
         * writes it from {@code jobscript:581-589}, which greps runsolver's English prose
         * out of watcher.out. That is the exact fragility {@link RunsolverVerdict}
         * documents — a wording change upstream silently reclassifies every timeout as a
         * clean completion — and it has a second failure mode the prose cannot cover at
         * all: {@code Watcher.hh:716} fires the prose only from the ~100ms watcher poll
         * while the child is still alive, whereas {@code TIMEOUT=} ({@code Watcher.hh:438})
         * is computed from the final getrusage after it exits. A solver that exits exactly
         * as its limit is crossed therefore produces {@code TIMEOUT=true} with no matching
         * sentence, and was recorded here as STATUS_COMPLETE.
         *
         * <p>{@code 73fc0acab} fixed this for {@link LocalJobMonitor} and
         * {@link ContainerJobMonitor} by reading runsolver's booleans directly. It did not
         * touch this backend, so the one path used in Kubernetes deployments kept the
         * defect the commit existed to remove. Ordering matches
         * {@code ContainerJobMonitor.determineStatus}: a limit verdict wins, and
         * status.json decides only when runsolver reports no breach.
         */
        private int readTerminalStatus(ExecutionRef execution, int defaultStatus) {
            StatusCode limit = readRunsolverVerdict(execution);
            if (limit != null) {
                return limit.getVal();
            }

            Path statusPath = resolveStatusPath(execution);
            if (statusPath == null || !Files.exists(statusPath)) {
                return defaultStatus;
            }

            try {
                String json = Files.readString(statusPath);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                if (root.has("status") && !root.get("status").isJsonNull()) {
                    return root.get("status").getAsInt();
                }
            } catch (Exception e) {
                log.warn("Failed to parse status.json for " + execution, e);
            }

            return defaultStatus;
        }

        /**
         * Reads runsolver's own limit verdict from var.out and watcher.out.
         *
         * <p>Every literal matched here is quoted from the vendored runsolver source at
         * {@code org/starexec/config/sge/RunSolverSource/} and is kept identical to
         * {@code ContainerJobMonitor.parseVarLine}/{@code parseWatcherLine} so the two
         * cannot disagree about the same run:
         *
         * <pre>
         *   Watcher.hh:471-475  TIMEOUT= / MEMOUT=   (boolalpha, so "true"/"false")
         *   Watcher.hh:717      "Maximum CPU time exceeded: ..."
         *   Watcher.hh:720      "Maximum wall clock time exceeded: ..."
         *   Watcher.hh:723      "Maximum VSize exceeded: ..."
         *   Watcher.hh:726      "Maximum memory exceeded: ..."   (only with -R)
         * </pre>
         *
         * @return the limit status this run breached, or {@code null} if runsolver
         *         reports no breach or its output is unreadable — never a guess
         */
        /**
         * The output directory of the execution this event belongs to, or null.
         *
         * <p>Gated on POSITIVE ownership, not on the absence of a competing owner. Those
         * are not the same condition, and the difference is reachable: tracking is
         * legitimately absent during the shutdown drain and startup reconciliation, and
         * "nobody else owns this id" is also true in the window between a concurrent
         * submission publishing its output directory and publishing the identity that
         * would reveal it. Reading the map on the weaker condition let one execution's
         * artifacts be read as another's. Absent tracking already yielded no directory, so
         * requiring ownership costs nothing that used to work.
         */
        private Path ownedOutputDir(ExecutionRef execution) {
            if (!ownsTracking(execution)) {
                return null;
            }
            return execIdToOutputDir.get(execution.execId());
        }

        private StatusCode readRunsolverVerdict(ExecutionRef execution) {
            Path outputDir = ownedOutputDir(execution);
            if (outputDir == null) {
                return null;
            }

            boolean timeout = false;
            boolean memout = false;
            boolean cpuProse = false;
            boolean wallclockProse = false;
            boolean memProse = false;

            Path varOut = outputDir.resolve("var.out");
            if (Files.exists(varOut)) {
                try {
                    for (String line : Files.readAllLines(varOut)) {
                        if (line.startsWith("TIMEOUT=")) {
                            timeout = Boolean.parseBoolean(
                                line.substring("TIMEOUT=".length()).trim());
                        } else if (line.startsWith("MEMOUT=")) {
                            memout = Boolean.parseBoolean(
                                line.substring("MEMOUT=".length()).trim());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to read var.out for " + execution, e);
                }
            }

            Path watcherOut = outputDir.resolve("watcher.out");
            if (Files.exists(watcherOut)) {
                try {
                    for (String line : Files.readAllLines(watcherOut)) {
                        if (line.contains("wall clock time exceeded")) {
                            wallclockProse = true;
                        } else if (line.contains("CPU time exceeded")) {
                            cpuProse = true;
                        } else if (line.contains("VSize exceeded")
                                || line.contains("Maximum memory exceeded")) {
                            memProse = true;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to read watcher.out for " + execution, e);
                }
            }

            return RunsolverVerdict.classify(
                timeout,
                memout,
                cpuProse,
                wallclockProse,
                memProse
            );
        }

        /**
         * Records the terminal status of every stage before the one that finished the pair.
         *
         * <p>Uses the stage-only routine, so it touches {@code jobpair_stage_data} and nothing
         * else -- no pair status, no completion, no end time. Pair completion stays exactly
         * where it was, in the single precise update this runs before. The order matters:
         * {@code UpdatePairStatusPrecise} rewrites the terminal stage and everything after it,
         * so anything earlier has to be in place first.
         *
         * <p>Returns false for every failure, which the caller turns into this backend's
         * existing retry outcome. Nothing here records a solver status: a snapshot that cannot
         * be believed is an evidence problem, and answering it with {@code ERROR_RUNSCRIPT}
         * would put a scientific failure on a pair whose solver may have been perfectly fine.
         *
         * @return true when the earlier stages are recorded, or when there are none to record
         */
        private boolean ingestEarlierStageStatuses(
            ExecutionRef execution,
            int pairId,
            int terminalStage
        ) {
            // Ownership dominates artifact ownership. ownedOutputDir is gated on positive
            // ownership of execId, job name and UID; the output directory is keyed by pair
            // rather than by attempt, so a stale execution pointed at a valid-looking
            // directory is exactly the case this must refuse.
            Path outputDir = ownedOutputDir(execution);
            if (outputDir == null) {
                log.info(
                    "Not ingesting stage snapshots for " + execution +
                    ": this execution no longer owns its tracking"
                );
                return true;
            }

            Map<Integer, Integer> snapshots;
            try {
                snapshots = StageStatusSnapshots.read(outputDir, pairId);
            } catch (StageStatusSnapshots.InvalidSnapshotException e) {
                log.error(
                    "Refusing the stage snapshots for pair " + pairId + " (" + execution +
                    "); nothing was written and the output is retained for diagnosis",
                    e
                );
                return false;
            } catch (Exception e) {
                log.error(
                    "Could not read the stage snapshots for pair " + pairId + " (" + execution +
                    "); results are retained and ingestion will retry",
                    e
                );
                return false;
            }

            Map<Integer, Integer> earlier = new TreeMap<>();
            for (Map.Entry<Integer, Integer> snapshot : snapshots.entrySet()) {
                // The terminal stage's own status comes from the runsolver artifacts, and a
                // pair killed mid-stage legitimately leaves that snapshot non-terminal.
                if (snapshot.getKey() < terminalStage) {
                    earlier.put(snapshot.getKey(), snapshot.getValue());
                }
            }
            if (earlier.isEmpty()) {
                return true;
            }

            // Re-checked at the mutation boundary, as the terminal write is. Everything above
            // reads files and takes real time; a rerun that landed meanwhile has already
            // superseded this result. This narrows the window to the width of the
            // check-then-write and cannot close it from inside this process --
            // UpdatePairStageStatusIfUnresolved refusing to overwrite a recorded result is the
            // backstop for what remains.
            if (!ownsTracking(execution)) {
                log.info(
                    "Discarding stage snapshots for " + execution +
                    ": ownership changed while its output was being read"
                );
                return false;
            }

            StageStatusBatchResult result = JobPairs.setEarlierStageStatuses(pairId, earlier);
            if (result == StageStatusBatchResult.APPLIED) {
                return true;
            }
            log.error(
                "Could not record earlier stage statuses " + earlier + " for pair " + pairId +
                " (" + result + "); nothing was written and completion will be retried"
            );
            return false;
        }

        /**
         * The stage this execution's result belongs to.
         *
         * <p>{@code defaultStage} applies only when the pair produced no status file at all,
         * which is an ordinary outcome for a pod that never started: there is nothing to
         * attribute, and the caller still has to record a platform verdict so the pair can be
         * rerun.
         *
         * <p>A file that exists must say which stage it is about. It used to fall back to the
         * same default whenever the field was missing, unparsable or a shape gson would coerce,
         * and that number was then handed to {@code UpdatePairStatusPrecise} as an authoritative
         * identity -- terminal status onto that stage, NOT_REACHED onto every stage above it.
         * A malformed file therefore produced a confident write against an invented stage.
         *
         * @throws StageStatusSnapshots.InvalidSnapshotException if a status file exists but does
         *         not carry a usable stage identity
         */
        private int readStageNumber(ExecutionRef execution, int defaultStage)
                throws StageStatusSnapshots.InvalidSnapshotException {
            Path statusPath = resolveStatusPath(execution);
            if (statusPath == null || !Files.exists(statusPath)) {
                return defaultStage;
            }

            JsonObject root;
            try {
                String json = Files.readString(statusPath);
                root = JsonParser.parseString(json).getAsJsonObject();
            } catch (Exception e) {
                throw new StageStatusSnapshots.InvalidSnapshotException(
                    "status.json for " + execution + " exists but could not be read as a status"
                        + " record, so the stage that produced this result is unknown", e);
            }
            return FinalStatusStage.require(root, String.valueOf(execution));
        }

        private Path resolveStatusPath(ExecutionRef execution) {
            Path outputDir = ownedOutputDir(execution);
            if (outputDir == null) {
                return null;
            }
            return outputDir.resolve("status.json");
        }

        /**
         * Persists run-solver statistics (wallclock, cpu, memory, disk) from
         * the shared data volume so that K8s-native jobs produce the same
         * resource-usage data as container-mode jobs.
         */
        private void persistRunSolverStats(ExecutionRef execution, int pairId, int stageNumber) {
            Path outputDir = ownedOutputDir(execution);
            if (outputDir == null) {
                return;
            }

            Path statsPath = outputDir.resolve("stats.json");
            if (!Files.exists(statsPath)) {
                return;
            }

            ContainerJobMonitor.RunsolverStats stats = new ContainerJobMonitor.RunsolverStats();
            try {
                String json = Files.readString(statsPath);
                parseStatsJsonInto(json, stats);
            } catch (Exception e) {
                log.warn("Could not read stats.json for pair " + pairId, e);
                return;
            }

            try {
                String nodeName = resolveStatsNodeName(stats);
                if (nodeName == null) {
                    // Skipping is the lesser loss. UpdatePairRunSolverStats resolves the
                    // node by name and raises P0002 if it is absent, which aborts the
                    // whole write anyway -- so guessing a name does not save the
                    // measurements, it only hides why they vanished.
                    log.error(
                        "No node name for pair " + pairId + ", so its runsolver statistics" +
                        " cannot be recorded: the database resolves stats by node name and" +
                        " would reject an invented one. stats.json reported hostname='" +
                        stats.hostname + "'. If this is a Kubernetes pair, check that the" +
                        " job pod carries STAREXEC_NODE_NAME from the downward API and that" +
                        " the node is registered in the nodes table."
                    );
                    return;
                }
                boolean ok = JobPairs.updateRunSolverStats(
                    pairId,
                    nodeName,
                    stats.wallclockTime,
                    stats.cpuTime,
                    stats.userTime,
                    stats.systemTime,
                    stats.maxVirtualMemory,
                    stats.maxResidentSetSize,
                    stageNumber,
                    stats.diskSize
                );
                if (ok) {
                    log.debug("Persisted run stats for pair " + pairId + ": " + stats);
                } else {
                    log.warn("Failed to persist run stats for pair " + pairId);
                }
            } catch (Exception e) {
                log.warn("Exception persisting run stats for pair " + pairId, e);
            }
        }

        /**
         * Persists attributes generated by post-processors.
         *
         * <p>Empty {@code starexec-result=} values are normalized to
         * {@code starexec-unknown} so downstream correctness logic treats
         * timeout/unknown outcomes consistently.</p>
         */
        private void persistAttributes(ExecutionRef execution, int pairId, int stageNumber) {
            Path outputDir = ownedOutputDir(execution);
            if (outputDir == null) return;

            Path attrsPath = outputDir.resolve("attributes.txt");
            if (!Files.exists(attrsPath)) return;

            Properties props = new Properties();
            try (BufferedReader reader = Files.newBufferedReader(attrsPath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        String value = line.substring(eq + 1).trim();
                        if (!key.isEmpty()) {
                            if (R.STAREXEC_RESULT.equals(key) && value.isEmpty()) {
                                value = R.STAREXEC_UNKNOWN;
                            }
                            props.setProperty(key, value);
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to parse attributes.txt for pair " + pairId, e);
                return;
            }

            if (!props.isEmpty()) {
                try {
                    JobPairs.addJobPairAttributes(pairId, stageNumber, props);
                    log.debug("Persisted attributes for pair " + pairId + ": " + props.size());
                } catch (Exception e) {
                    log.warn("Failed to persist attributes for pair " + pairId, e);
                }
            }
        }

        /**
         * The node a pair ran on, or {@code null} when it cannot be determined.
         *
         * <p>Returning null rather than a placeholder is the point. This used to end in
         * the literal {@code "kubernetes-worker"}, which is never a row in {@code nodes},
         * so it guaranteed the {@code P0002} that aborts the entire stats write. A name that cannot resolve does not preserve the measurements; it only
         * disguises why they disappeared, since the failure then surfaces as a database
         * exception rather than as "we did not know the node".
         *
         * <p>The first branch is now reliable on Kubernetes too: the job pod carries
         * {@code STAREXEC_NODE_NAME} from the downward API, so {@code stats.hostname} is
         * the node rather than the pod.
         *
         * <p>{@code appNodeName} remains as a second branch because it is a real node
         * name, used when the data PVC forces pods onto the application's node.
         * {@code getWorkerNodes()[0]} is deliberately gone: picking an arbitrary worker
         * records this pair's measurements against a machine that did not run it, which
         * is worse than recording nothing.
         */
        private String resolveStatsNodeName(ContainerJobMonitor.RunsolverStats stats) {
            if (stats.hostname != null && !stats.hostname.trim().isEmpty()) {
                return stats.hostname.trim();
            }
            if (appNodeName != null && !appNodeName.trim().isEmpty()) {
                return appNodeName.trim();
            }
            return null;
        }

        /**
         * Parses the subset of stats.json fields needed for run-solver
         * statistics. Missing or malformed fields gracefully leave the
         * default zero values in place.
         */
        private void parseStatsJsonInto(
            String json,
            ContainerJobMonitor.RunsolverStats stats
        ) {
            JsonObject obj;
            try {
                obj = JsonParser.parseString(json).getAsJsonObject();
            } catch (Exception e) {
                log.warn("stats.json is not valid JSON; skipping stats parse", e);
                return;
            }

            try { if (obj.has("wallclockTime")) stats.wallclockTime = obj.get("wallclockTime").getAsDouble(); } catch (Exception e) { log.warn("stats.json: could not parse wallclockTime", e); }
            try { if (obj.has("cpuTime")) stats.cpuTime = obj.get("cpuTime").getAsDouble(); } catch (Exception e) { log.warn("stats.json: could not parse cpuTime", e); }
            try { if (obj.has("userTime")) stats.userTime = obj.get("userTime").getAsDouble(); } catch (Exception e) { log.warn("stats.json: could not parse userTime", e); }
            try { if (obj.has("systemTime")) stats.systemTime = obj.get("systemTime").getAsDouble(); } catch (Exception e) { log.warn("stats.json: could not parse systemTime", e); }
            try { if (obj.has("maxVirtualMemory")) stats.maxVirtualMemory = obj.get("maxVirtualMemory").getAsDouble(); } catch (Exception e) { log.warn("stats.json: could not parse maxVirtualMemory", e); }
            try { if (obj.has("maxResidentSetSize")) stats.maxResidentSetSize = obj.get("maxResidentSetSize").getAsLong(); } catch (Exception e) { log.warn("stats.json: could not parse maxResidentSetSize", e); }
            try { if (obj.has("diskSize")) stats.diskSize = obj.get("diskSize").getAsLong(); } catch (Exception e) { log.warn("stats.json: could not parse diskSize", e); }
            try { if (obj.has("hostname")) stats.hostname = obj.get("hostname").getAsString(); } catch (Exception e) { log.warn("stats.json: could not parse hostname", e); }
        }
    }
}
