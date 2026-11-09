package org.starexec.data.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.starexec.backend.storage.DedicatedStorageResource;
import org.starexec.constants.R;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.to.enums.ProcessorType;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Authorized ordinary-job metadata; capture neither copies inputs nor grants engine admission. */
public final class JobExecutionInputs {
    public enum Kind { BENCHMARK, SOLVER, PROCESSOR }
    public record FileInput(Kind kind, int id, Path source, long declaredBytes) { }
    public record Snapshot(JobStorageLeases.Lease lease, List<FileInput> files, String payload, String sha256) {
        public Snapshot { files = List.copyOf(files); }
    }

    private JobExecutionInputs() { }

    /** Uses one repeatable database snapshot; later filesystem preparation and admission must revalidate it. */
    public static Optional<Snapshot> capture(Connection connection, JobStorageLeases.Lease expected,
                                              DedicatedStorageResource resource) throws SQLException {
        return capture(connection, expected, resource, false);
    }

    /** Revalidates prepared metadata; a consumed use marker still grants no engine admission. */
    public static Optional<Snapshot> capturePrepared(Connection connection, JobStorageLeases.Lease expected,
                                                     DedicatedStorageResource resource) throws SQLException {
        return capture(connection, expected, resource, true);
    }

    private static Optional<Snapshot> capture(Connection connection, JobStorageLeases.Lease expected,
                                               DedicatedStorageResource resource, boolean prepared) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Job input capture cannot use a foreign transaction");
        int previous = connection.getTransactionIsolation();
        int isolation = previous == Connection.TRANSACTION_SERIALIZABLE ? previous : Connection.TRANSACTION_REPEATABLE_READ;
        SQLException failure = null;
        try {
            if (previous != isolation) connection.setTransactionIsolation(isolation);
            return Common.runTransactional(connection, tx -> {
                if (expected == null || expected.releasedAt() != null || (expected.useStartedAt() != null) != prepared
                        || !expected.equals(JobStorageLeases.find(tx, expected.token()).orElse(null))
                        || !expected.resourceKey().equals(resource.filesystemKey()) || !expected.root().equals(resource.root())
                        || !expected.resourceIdentity().equals(resource.identity()) || expected.limitBytes() != resource.capacityBytes()
                        || expected.inodeLimit() != resource.inodeLimit()) throw new Refused();
                resource.requireUnchanged();
                if (prepared) {
                    lockPrepared(tx, expected);
                } else {
                    try (var claim = tx.prepareStatement("SELECT starexec.reserve_job_storage(?,?,?,?,?)")) {
                        claim.setInt(1, expected.pairId()); claim.setInt(2, expected.attemptNo()); claim.setObject(3, expected.token());
                        claim.setString(4, expected.resourceKey()); claim.setString(5, expected.resourceIdentity());
                        try (var row = claim.executeQuery()) { if (!row.next() || !row.getBoolean(1)) throw new Refused(); }
                    }
                }
                var snapshot = new Capture(tx, expected).read();
                resource.requireUnchanged();
                return Optional.of(snapshot);
            });
        } catch (Refused refused) {
            if (refused.getSuppressed().length != 0) { failure = refused; throw refused; }
            return Optional.empty();
        } catch (SQLException error) {
            failure = error;
            throw error;
        } finally {
            if (previous != isolation) {
                try { connection.setTransactionIsolation(previous); }
                catch (SQLException reset) {
                    try { connection.close(); } catch (SQLException close) { if (close != reset) reset.addSuppressed(close); }
                    if (failure != null) { if (failure != reset) failure.addSuppressed(reset); }
                    else throw reset;
                }
            }
        }
    }

    private static void lockPrepared(Connection connection, JobStorageLeases.Lease expected) throws SQLException {
        try (var query = connection.prepareStatement("SELECT starexec.lock_current_job_storage_pair(?,?,?,?,TRUE)")) {
            query.setInt(1, expected.pairId()); query.setInt(2, expected.attemptNo());
            query.setInt(3, expected.ownerId()); query.setObject(4, expected.priorExecutionId());
            try (var row = query.executeQuery()) { if (!row.next() || !row.getBoolean(1)) throw new Refused(); }
        }
        try (var query = connection.prepareStatement("SELECT disk_size,disk_quota,starexec.active_execution_storage_bytes(id)"
                + " FROM starexec.users WHERE id=? FOR UPDATE NOWAIT")) {
            query.setInt(1, expected.ownerId());
            try (var row = query.executeQuery()) {
                if (!row.next() || row.getObject(1) == null || row.getObject(2) == null
                        || row.getLong(1) < 0 || row.getBigDecimal(3) == null
                        || java.math.BigDecimal.valueOf(row.getLong(1)).compareTo(row.getBigDecimal(3)) < 0) throw new Refused();
            }
        }
        try (var query = connection.prepareStatement("SELECT observed_identity,capacity_bytes,inode_limit,root_path,"
                + "starexec.execution_storage_root_unreferenced(root_path) FROM starexec.execution_storage_resources"
                + " WHERE resource_key=? FOR UPDATE NOWAIT")) {
            query.setString(1, expected.resourceKey());
            try (var row = query.executeQuery()) {
                if (!row.next() || !expected.resourceIdentity().equals(row.getString(1))
                        || expected.limitBytes() != row.getLong(2) || expected.inodeLimit() != row.getLong(3)
                        || !expected.root().toString().equals(row.getString(4)) || !row.getBoolean(5)) throw new Refused();
            }
        }
        try (var query = connection.prepareStatement("SELECT execution_token FROM starexec.execution_storage_claims"
                + " WHERE execution_token=? FOR UPDATE NOWAIT")) {
            query.setObject(1, expected.token());
            try (var row = query.executeQuery()) { if (!row.next()) throw new Refused(); }
        }
        if (!expected.equals(JobStorageLeases.find(connection, expected.token()).orElse(null))) throw new Refused();
    }

    private static final class Capture {
        private final Connection connection;
        private final JobStorageLeases.Lease lease;
        private final Map<String, FileInput> files = new LinkedHashMap<>();
        private final Map<Integer, JsonObject> solvers = new LinkedHashMap<>();
        private final Map<Integer, JsonObject> processors = new LinkedHashMap<>();
        private final JsonArray benchmarks = new JsonArray();
        private final JsonArray benchmarkLinks = new JsonArray();
        private final JsonObject payload = new JsonObject();

        private Capture(Connection connection, JobStorageLeases.Lease lease) { this.connection = connection; this.lease = lease; }

        private Snapshot read() throws SQLException {
            payload.addProperty("version", 1);
            payload.addProperty("executionToken", lease.token().toString());
            payload.addProperty("attempt", lease.attemptNo());
            payload.addProperty("resourceKey", lease.resourceKey());
            payload.addProperty("resourceIdentity", lease.resourceIdentity());
            var job = one("SELECT row_to_json(x)::text FROM (SELECT j.id AS job_id,j.user_id,j.queue_id,j.seed,"
                    + "j.cputimeout,j.clocktimeout,j.kill_delay,j.soft_time_limit,j.maximum_memory,j.using_dependencies,"
                    + "j.suppress_timestamp,j.benchmarking_framework,j.output_benchmarks_directory_path,"
                    + "p.id AS pair_id,p.bench_id,p.primary_jobpair_data FROM starexec.jobs j"
                    + " JOIN starexec.job_pairs p ON p.job_id=j.id WHERE p.id=?) x", lease.pairId());
            if (integer(job, "user_id") != lease.ownerId()) throw new Refused();
            payload.add("job", job);
            var inputs = rows("SELECT row_to_json(x)::text FROM (SELECT input_number,bench_id FROM starexec.jobpair_inputs"
                    + " WHERE jobpair_id=? ORDER BY input_number) x", lease.pairId());
            payload.add("inputs", inputs);
            var roots = new ArrayDeque<Integer>();
            roots.add(integer(job, "bench_id"));
            int inputNumber = 1;
            for (var item : inputs) {
                var input = item.getAsJsonObject();
                if (integer(input, "input_number") != inputNumber++) throw new Refused();
                roots.add(integer(input, "bench_id"));
            }
            readBenchmarks(roots);
            payload.add("benchmarks", benchmarks);
            payload.add("benchmarkDependencies", benchmarkLinks);
            readStages(job, inputs.size());
            payload.add("solvers", array(solvers));
            payload.add("processors", array(processors));
            readPublishedInputs();
            String text = payload.toString();
            if (text.getBytes(StandardCharsets.UTF_8).length > lease.limitBytes()) throw new Refused();
            return new Snapshot(lease, new ArrayList<>(files.values()), text, sha256(text));
        }

        private void readBenchmarks(ArrayDeque<Integer> pending) throws SQLException {
            var seen = new HashSet<Integer>();
            while (!pending.isEmpty()) {
                int id = pending.removeFirst();
                if (!seen.add(id)) continue;
                var bench = one("SELECT row_to_json(x)::text FROM (SELECT id,user_id,name,path,disk_size,deleted,recycled"
                        + " FROM starexec.benchmarks WHERE id=?) x", id);
                if (flag(bench, "deleted") || flag(bench, "recycled") || !Permissions.canUserSeeBench(connection, id, lease.ownerId())) throw new Refused();
                addFile(Kind.BENCHMARK, bench);
                benchmarks.add(bench);
                var links = rows("SELECT row_to_json(x)::text FROM (SELECT primary_bench_id,secondary_bench_id,include_path"
                        + " FROM starexec.bench_dependency WHERE primary_bench_id=? ORDER BY include_path,secondary_bench_id) x", id);
                for (var item : links) {
                    var link = item.getAsJsonObject();
                    if (link.get("include_path").isJsonNull() || link.get("include_path").getAsString().isEmpty()) throw new Refused();
                    benchmarkLinks.add(link);
                    if (benchmarkLinks.size() > lease.inodeLimit()) throw new Refused();
                    pending.add(integer(link, "secondary_bench_id"));
                }
            }
        }

        private void readStages(JsonObject job, int inputCount) throws SQLException {
            var stages = rows("SELECT row_to_json(x)::text FROM (SELECT d.stage_number,d.stage_id,d.solver_id,d.config_id,"
                    + "p.stage_id AS pipeline_stage,p.pipeline_id,p.config_id AS pipeline_config,p.is_noop"
                    + " FROM starexec.jobpair_stage_data d LEFT JOIN starexec.pipeline_stages p ON p.stage_id=d.stage_id"
                    + " WHERE d.jobpair_id=? ORDER BY d.stage_number) x", lease.pairId());
            if (stages.isEmpty() || stages.size() > lease.inodeLimit()) throw new Refused();
            boolean primary = false;
            int expectedNumber = 1;
            for (var item : stages) {
                var stage = item.getAsJsonObject();
                int number = integer(stage, "stage_number");
                if (number != expectedNumber++) throw new Refused();
                boolean pipeline = !stage.get("stage_id").isJsonNull();
                if (pipeline && stage.get("pipeline_stage").isJsonNull()) throw new Refused();
                boolean noop = pipeline && flag(stage, "is_noop");
                if (noop) {
                    if (!stage.get("solver_id").isJsonNull() || !stage.get("config_id").isJsonNull()) throw new Refused();
                } else {
                    int solverId = integer(stage, "solver_id"), configId = integer(stage, "config_id");
                    var config = one("SELECT row_to_json(x)::text FROM (SELECT id,solver_id,name,updated,deleted"
                            + " FROM starexec.configurations WHERE id=?) x", configId);
                    if (integer(config, "solver_id") != solverId || flag(config, "deleted") || config.get("name").isJsonNull()) throw new Refused();
                    if (pipeline && integer(stage, "pipeline_config") != configId) throw new Refused();
                    stage.add("configuration", config);
                    readSolver(solverId);
                    if (number == integer(job, "primary_jobpair_data")) primary = true;
                }
                var params = one("SELECT row_to_json(x)::text FROM (SELECT stage_number,cputimeout,clocktimeout,maximum_memory,"
                        + "space_id,bench_suffix,post_processor,pre_processor,results_interval,stdout_save_option,extra_output_save_option"
                        + " FROM starexec.job_stage_params WHERE job_id=? AND stage_number=?) x", integer(job, "job_id"), number);
                readProcessor(params, "pre_processor", ProcessorType.PRE.getVal());
                readProcessor(params, "post_processor", ProcessorType.POST.getVal());
                stage.add("parameters", params);
                var links = pipeline ? rows("SELECT row_to_json(x)::text FROM (SELECT input_type,input_id,input_number"
                        + " FROM starexec.pipeline_dependencies WHERE stage_id=? ORDER BY input_number) x", integer(stage, "stage_id")) : new JsonArray();
                int expectedInput = 1;
                for (var entry : links) {
                    var link = entry.getAsJsonObject();
                    int type = integer(link, "input_type"), id = integer(link, "input_id");
                    if (integer(link, "input_number") != expectedInput++ || id < 1
                            || type != 1 && type != 2 || type == 1 && id > inputCount
                            || type == 2 && id >= number) throw new Refused();
                }
                stage.add("dependencies", links);
            }
            if (!primary) throw new Refused();
            payload.add("stages", stages);
        }

        private void readSolver(int id) throws SQLException {
            if (solvers.containsKey(id)) return;
            var solver = one("SELECT row_to_json(x)::text FROM (SELECT id,user_id,name,path,uploaded,disk_size,deleted,recycled,"
                    + "build_status,build_generation,build_output_artifact_id FROM starexec.solvers WHERE id=?) x", id);
            int status = integer(solver, "build_status");
            if (flag(solver, "deleted") || flag(solver, "recycled") || status != 1 && status != 2
                    || !Permissions.canUserSeeSolver(connection, id, lease.ownerId())) throw new Refused();
            addFile(Kind.SOLVER, solver);
            solvers.put(id, solver);
        }

        private void readProcessor(JsonObject params, String field, int type) throws SQLException {
            if (params.get(field).isJsonNull()) return;
            int id = integer(params, field);
            var processor = processors.get(id);
            if (processor == null) {
                processor = one("SELECT row_to_json(x)::text FROM (SELECT id,name,path,community,processor_type,disk_size,"
                        + "preserve_input,syntax_id,time_limit FROM starexec.processors WHERE id=?) x", id);
                if (id != R.NO_TYPE_PROC_ID && !GeneralSecurity.hasAdminReadPrivilegesChecked(connection, lease.ownerId())
                        && !Users.isMemberOfCommunity(connection, lease.ownerId(), integer(processor, "community"))) throw new Refused();
                addFile(Kind.PROCESSOR, processor);
                processors.put(id, processor);
            }
            if (integer(processor, "processor_type") != type) throw new Refused();
        }

        private void readPublishedInputs() throws SQLException {
            var manifests = new JsonArray();
            var published = new HashSet<Integer>();
            for (var input : PublishedSolverInputs.forPair(connection, lease.pairId(), lease.attemptNo())) {
                var artifact = input.artifact();
                var solver = solvers.get(artifact.liveSolverId());
                if (solver == null || !sha256(input.manifest().payload()).equals(input.manifest().sha256())
                        || !solver.get("build_output_artifact_id").isJsonNull()
                        && solver.get("build_output_artifact_id").getAsLong() != artifact.id()) throw new Refused();
                var manifest = new JsonObject();
                manifest.addProperty("solver", artifact.liveSolverId()); manifest.addProperty("artifact", artifact.id());
                manifest.addProperty("payload", input.manifest().payload()); manifest.addProperty("sha256", input.manifest().sha256());
                manifest.addProperty("bytes", input.manifest().byteSize()); manifests.add(manifest); published.add(artifact.liveSolverId());
            }
            for (var entry : solvers.entrySet()) {
                if (!entry.getValue().get("build_output_artifact_id").isJsonNull() && !published.contains(entry.getKey())) throw new Refused();
            }
            payload.add("publishedSolvers", manifests);
        }

        private void addFile(Kind kind, JsonObject source) throws SQLException {
            try {
                Path path = Path.of(source.get("path").getAsString());
                long bytes = source.get("disk_size").getAsLong();
                if (!path.isAbsolute() || path.getParent() == null || !path.equals(path.normalize()) || bytes < 0
                        || path.startsWith(lease.root()) || lease.root().startsWith(path)) throw new Refused();
                int id = integer(source, "id");
                files.put(kind + ":" + id, new FileInput(kind, id, path, bytes));
                if (files.size() > lease.inodeLimit()) throw new Refused();
            } catch (InvalidPathException | IllegalStateException | UnsupportedOperationException error) { throw new Refused(); }
        }

        private JsonArray rows(String sql, Object... args) throws SQLException {
            try (var query = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) query.setObject(i + 1, args[i]);
                var array = new JsonArray();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        array.add(JsonParser.parseString(rows.getString(1)).getAsJsonObject());
                        if (array.size() > lease.inodeLimit()) throw new Refused();
                    }
                }
                return array;
            }
        }

        private JsonObject one(String sql, Object... args) throws SQLException {
            var rows = rows(sql, args);
            if (rows.size() != 1) throw new Refused();
            return rows.get(0).getAsJsonObject();
        }
    }

    private static int integer(JsonObject object, String field) throws SQLException {
        if (!object.has(field) || object.get(field).isJsonNull()) throw new Refused();
        return object.get(field).getAsInt();
    }

    private static boolean flag(JsonObject object, String field) throws SQLException {
        if (!object.has(field) || object.get(field).isJsonNull()) throw new Refused();
        return object.get(field).getAsBoolean();
    }

    private static JsonArray array(Map<Integer, JsonObject> entries) {
        var result = new JsonArray(); entries.values().forEach(result::add); return result;
    }

    private static String sha256(String value) throws SQLException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException absent) { throw new SQLException("SHA-256 is unavailable", absent); }
    }

    private static final class Refused extends SQLException {
        private static final long serialVersionUID = 1L;
        private Refused() { super("Current authorized job input snapshot is unavailable"); }
    }
}
