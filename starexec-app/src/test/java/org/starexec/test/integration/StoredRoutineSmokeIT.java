package org.starexec.test.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Applies the whole migration chain to a throwaway PostgreSQL container and then CALLS a
 * selection of stored routines.
 *
 * <h2>Why this exists</h2>
 *
 * PostgreSQL plans a PL/pgSQL function body lazily, at call time, so CREATE FUNCTION
 * accepts a body that can never execute. Applying the migration chain therefore proves
 * only that the text parsed. Two routines in this repository could not run at all --
 * one qualified a target column in SET, another was called by a name nothing defined --
 * and both survived a green build and a green migration run because nothing ever invoked
 * them.
 *
 * <h2>Why the database is not called "starexec"</h2>
 *
 * Twelve queue routines once referred to {@code starexec.starexec.queues}. A three-part
 * name is database.schema.table, which PostgreSQL accepts only when the first part names
 * the current database -- so that defect was invisible in a database called starexec and
 * fatal in any other. Using a different name here is deliberate: it is what makes a
 * hardcoded database name fail the test rather than pass it.
 *
 * <h2>Why the podman CLI rather than Testcontainers</h2>
 *
 * docker-java is a production compile dependency of this project, used by PodmanBackend.
 * Testcontainers bundles its own copy, and adding it puts a second version on the
 * classpath of an application that already carries notes about wrestling that library's
 * transitive tree. Shelling out costs one ProcessBuilder helper and risks nothing in the
 * production classpath.
 *
 * <h2>Running it</h2>
 *
 * <pre>mvn verify -Pit -pl starexec-app</pre>
 *
 * It is skipped, not failed, when no container runtime is available, so it never breaks
 * a build on a machine that cannot run it.
 */
public class StoredRoutineSmokeIT {

    private static final String CONTAINER = "starexec-routine-smoke-it";
    /** Deliberately not "starexec" -- see the class comment. */
    private static final String DATABASE = "starexec_it";
    private static final String IMAGE = "docker.io/library/postgres:15";
    private static final Path MIGRATIONS = Paths.get(
        "src/main/resources/db/migration");

    private static String runtime;

    // ------------------------------------------------------------------
    // container lifecycle
    // ------------------------------------------------------------------

    @BeforeClass
    public static void startDatabase() throws Exception {
        runtime = detectRuntime();
        assumeTrue("no podman or docker on PATH; skipping stored-routine smoke test",
            runtime != null);

        exec(30, runtime, "rm", "-f", CONTAINER);

        Result created = exec(120, runtime, "run", "-d", "--name", CONTAINER,
            "-e", "POSTGRES_PASSWORD=smoke",
            "-e", "POSTGRES_USER=starexec",
            "-e", "POSTGRES_DB=postgres",
            IMAGE);
        assumeTrue("could not start " + IMAGE + ": " + created.output, created.exitCode == 0);

        // pg_isready is not sufficient on its own: the official image starts a temporary
        // server to run its init scripts and then restarts, so there is a window where
        // pg_isready succeeds against a server that is about to go away. Wait until a
        // real query succeeds, twice in a row, before trusting it.
        boolean ready = false;
        int consecutive = 0;
        for (int i = 0; i < 90 && !ready; i++) {
            Result probe = execWithStdin(15, "SELECT 1;", runtime, "exec", "-i", CONTAINER,
                "psql", "-U", "starexec", "-d", "postgres", "-tA", "-v", "ON_ERROR_STOP=1");
            consecutive = (probe.exitCode == 0) ? consecutive + 1 : 0;
            ready = consecutive >= 2;
            if (!ready) {
                Thread.sleep(1000);
            }
        }
        assertTrue("postgres never accepted queries", ready);

        // Checked, not fired and forgotten. A silent failure here surfaced later as
        // "database does not exist" while applying the first migration, which points at
        // the wrong thing entirely.
        mustSucceed("create database", "postgres", "CREATE DATABASE " + DATABASE + ";");
        mustSucceed("create schema", DATABASE, "CREATE SCHEMA IF NOT EXISTS starexec;");
        mustSucceed("set search_path", DATABASE,
            "ALTER DATABASE " + DATABASE + " SET search_path TO starexec, public;");

        applyMigrations();
        seed();
    }

    @AfterClass
    public static void stopDatabase() {
        if (runtime != null) {
            exec(60, runtime, "rm", "-f", CONTAINER);
        }
    }

    // ------------------------------------------------------------------
    // the tests
    // ------------------------------------------------------------------

    /**
     * Every versioned and repeatable migration applies. This is the part a Flyway run
     * already covers; it is here so that a failure below can be attributed to the routine
     * rather than to the schema.
     */
    @Test
    public void migrationChainApplies() throws Exception {
        int routines = Integer.parseInt(query(
            "SELECT count(*) FROM information_schema.routines WHERE routine_schema='starexec';"));
        assertTrue("expected the schema to define stored routines, found " + routines,
            routines > 400);
    }

    /**
     * Queue routines must not hardcode the database name. This is the test that would
     * have caught {@code starexec.starexec.queues}, and only because DATABASE is not
     * "starexec".
     */
    @Test
    public void queueRoutinesDoNotHardcodeTheDatabaseName() throws Exception {
        // What matters is that these execute at all in a database whose name is not
        // "starexec". A three-part reference raises "cross-database references are not
        // implemented" here and nowhere else, so reaching a row count -- whatever the
        // seed data makes it -- is the assertion. Pinning a specific number instead
        // would only couple this test to V0002.
        int queues = Integer.parseInt(query("SELECT count(*) FROM starexec.GetAllQueues();"));
        assertTrue("GetAllQueues must execute and return a count, got " + queues, queues >= 0);

        run("SELECT starexec.GetIdByName('nonexistent-queue');");
        run("SELECT starexec.GetNameById(1);");
        run("SELECT starexec.IsQueueGlobal(1);");
    }

    /** Would have caught the qualified target column in SET. */
    @Test
    public void prepareJobForPostProcessingExecutes() throws Exception {
        run("SELECT starexec.PrepareJobForPostProcessing(9001, NULL, 7, 2, 1);");
        assertEquals("2", query("SELECT status_code FROM starexec.job_pairs WHERE id=9001;"));
        assertEquals("2", query(
            "SELECT status_code FROM starexec.jobpair_stage_data WHERE jobpair_id=9001;"));
    }

    /** Would have caught the routine being called by a name nothing defined. */
    @Test
    public void notificationRoutinesResolve() throws Exception {
        // Raises a business-logic error for a subscription that does not exist, which is
        // fine: the point is that the function resolves rather than being undefined.
        Result r = psql(DATABASE, "SELECT starexec.UnsubscribeUserFromJob(9001, 9001);");
        assertTrue("UnsubscribeUserFromJob must exist: " + r.output,
            !r.output.contains("does not exist"));
    }

    /** The three-way terminal-status contract. */
    @Test
    public void pairStatusTransitionsFollowTheThreeWayContract() throws Exception {
        run("UPDATE starexec.job_pairs SET status_code=4 WHERE id=9002;");
        run("UPDATE starexec.jobpair_stage_data SET status_code=4 WHERE jobpair_id=9002;");

        assertEquals("non-terminal to terminal must apply",
            "t", query("SELECT starexec.UpdatePairStatusPrecise(9002,1,7,23);"));
        assertEquals("the same terminal status again must be idempotent success",
            "t", query("SELECT starexec.UpdatePairStatusPrecise(9002,1,7,23);"));
        assertEquals("a different terminal status must be refused",
            "f", query("SELECT starexec.UpdatePairStatusPrecise(9002,1,11,23);"));
        assertEquals("and accepted with an explicit override",
            "t", query("SELECT starexec.UpdatePairStatusPrecise(9002,1,11,23,TRUE);"));
    }

    /** Disk accounting must not move when the same figure is reported twice. */
    @Test
    public void runSolverStatsAreIdempotent() throws Exception {
        run("INSERT INTO starexec.nodes (id,name,status) VALUES (9001,'smoke-node','ACTIVE')"
            + " ON CONFLICT DO NOTHING;");
        run("UPDATE starexec.users SET disk_size=0 WHERE id=9001;");
        run("UPDATE starexec.jobs SET disk_size=0 WHERE id=9001;");
        run("UPDATE starexec.jobpair_stage_data SET disk_size=0 WHERE jobpair_id=9003;");

        run("CALL starexec.UpdatePairRunSolverStats(9003,'smoke-node',1,1,1,1,1,10,1,5000);");
        String afterFirst = query("SELECT disk_size FROM starexec.users WHERE id=9001;");
        run("CALL starexec.UpdatePairRunSolverStats(9003,'smoke-node',1,1,1,1,1,10,1,5000);");
        String afterSecond = query("SELECT disk_size FROM starexec.users WHERE id=9001;");

        assertEquals("5000", afterFirst);
        assertEquals("a repeated report must not charge twice", afterFirst, afterSecond);
    }

    /** A space must not be movable into itself or below itself. */
    @Test
    public void spaceMovesCannotCreateACycle() throws Exception {
        Result self = psql(DATABASE, "SELECT starexec.MoveSpace(9101, 9101);");
        assertTrue("a self-move must be refused: " + self.output,
            self.output.contains("cannot be moved into itself"));

        Result descendant = psql(DATABASE, "SELECT starexec.MoveSpace(9102, 9101);");
        assertTrue("a move into a descendant must be refused: " + descendant.output,
            descendant.output.contains("own descendants"));

        assertEquals("t", query("SELECT starexec.IsSpaceDescendant(9101,9102);"));
        assertEquals("f", query("SELECT starexec.IsSpaceDescendant(9102,9101);"));
    }

    /**
     * AddPipelineStage must resolve against the argument types Java actually binds.
     *
     * <p>Its third parameter was {@code INT} with {@code IF _primary = 1}, a MySQL
     * boolean-as-integer that survived the port, while {@code Pipelines} binds it with
     * {@code setBoolean}. PostgreSQL includes argument types in a routine's identity, so no
     * overload resolved and every pipelined job failed to persist a single stage -- while
     * the job and pipeline rows committed, because that path is not transactional.
     *
     * <p>Passing a boolean here is what makes this discriminating: against the old signature
     * the call raises "function ... does not exist", which is the production failure.
     */
    @Test
    public void addPipelineStageAcceptsTheArgumentTypesJavaBinds() throws Exception {
        run("INSERT INTO starexec.solver_pipelines (id,name,user_id,uploaded) VALUES"
            + " (9201,'boundTypes',9001,NOW()) ON CONFLICT DO NOTHING;");

        // config_id is nullable and FKs to configurations, which would drag in a solver and
        // its owner; none of that bears on the argument-type contract under test.
        String first = query("SELECT starexec.AddPipelineStage(9201, NULL::INT, FALSE, FALSE);");
        String second = query("SELECT starexec.AddPipelineStage(9201, NULL::INT, TRUE, FALSE);");

        assertEquals("a primary stage must be recorded on the pipeline row",
            second, query("SELECT primary_stage_id FROM starexec.solver_pipelines WHERE id=9201;"));
        assertTrue("stage ids must ascend with insertion order, got " + first + " then " + second,
            Integer.parseInt(second) > Integer.parseInt(first));
    }

    /**
     * Exactly one AddPipelineStage may exist. A repeatable migration that left the stale
     * signature behind would restore the ambiguity even with the new one present.
     */
    @Test
    public void addPipelineStageHasNoSurvivingOverload() throws Exception {
        assertEquals("AddPipelineStage must not be overloaded", "1", query(
            "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace"
            + " WHERE n.nspname='starexec' AND p.proname='addpipelinestage';"));
        assertEquals("the surviving signature must take BOOLEAN and return INT", "1", query(
            "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace"
            + " WHERE n.nspname='starexec' AND p.proname='addpipelinestage' AND p.prokind='f'"
            + " AND pg_get_function_identity_arguments(p.oid)="
            + "'_pid integer, _cid integer, _primary boolean, _noop boolean'"
            + " AND pg_get_function_result(p.oid)='integer';"));
    }

    /**
     * Stage order is part of a pipeline's meaning -- stage 1 feeds stage 2 -- and
     * GetStagesByPipelineId had no ORDER BY, so it returned rows in whatever order the scan
     * produced. Rewriting the first stage is what makes that order diverge: an UPDATE writes
     * a new tuple version whose line pointer is appended, so a scan reports that row last.
     */
    @Test
    public void stagesComeBackInPipelineOrder() throws Exception {
        run("INSERT INTO starexec.solver_pipelines (id,name,user_id,uploaded) VALUES"
            + " (9202,'ordering',9001,NOW()) ON CONFLICT DO NOTHING;");
        run("DELETE FROM starexec.pipeline_stages WHERE pipeline_id=9202;");

        List<String> inserted = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            inserted.add(query("SELECT starexec.AddPipelineStage(9202, NULL::INT, FALSE, FALSE);"));
        }
        run("UPDATE starexec.pipeline_stages SET is_noop = is_noop WHERE stage_id="
            + inserted.get(0) + ";");

        List<String> returned = List.of(query(
            "SELECT stage_id FROM starexec.GetStagesByPipelineId(9202);").split("\\R"));
        assertEquals("stages must come back ordered by stage_id", inserted, returned);
    }

    /**
     * A failure at stage insertion must leave no pipeline behind.
     *
     * <p>Pipeline creation ran on its own connection in autoCommit, so the pipeline row was
     * durable before its stages were attempted and stayed durable when they failed -- the
     * reproduction database holds two such pipelines with no stages at all. The fix puts the
     * whole upload in one transaction; this is what that transaction has to do.
     *
     * <p>Note the COMMIT: once a statement has failed, PostgreSQL will not honour it. Partial
     * work cannot be salvaged by asking, which is the property being relied on.
     */
    @Test
    public void aFailureAtStageInsertionLeavesNoPipeline() throws Exception {
        String before = query("SELECT count(*) FROM starexec.solver_pipelines;");

        Result attempt = psqlTolerant(String.join("\n",
            "BEGIN;",
            "SELECT starexec.AddPipeline(9001,'rollbackAtStage');",
            // Induced failure: pipeline_stages.pipeline_id has a foreign key.
            "SELECT starexec.AddPipelineStage(999999, NULL::INT, TRUE, FALSE);",
            "COMMIT;"));
        assertTrue("the induced failure must be the foreign key, was: " + attempt.output,
            attempt.output.contains("pipeline_stages_pipeline_id"));
        assertTrue("an aborted transaction must not commit: " + attempt.output,
            attempt.output.contains("ROLLBACK"));

        assertEquals("the pipeline must not survive the failure of its stage",
            "0", query("SELECT count(*) FROM starexec.solver_pipelines"
                + " WHERE name='rollbackAtStage';"));
        assertEquals("no other pipeline may be disturbed",
            before, query("SELECT count(*) FROM starexec.solver_pipelines;"));
    }

    /**
     * The second boundary: a failure at pair or stage-data insertion must leave no job, no
     * pairs, no stage data, and no counter drift.
     *
     * <p>The job row used to be written outside any transaction, between two short ones, so
     * it survived the rollback of the pairs that were meant to fill it -- a job that can
     * never run, reported as created.
     */
    @Test
    public void aFailureAtPairInsertionLeavesNoJob() throws Exception {
        String pairsBefore = query("SELECT count(*) FROM starexec.job_pairs;");
        String stageDataBefore = query("SELECT count(*) FROM starexec.jobpair_stage_data;");
        final String initiatedCount = "SELECT occurrences FROM starexec.report_data"
            + " WHERE event_name='jobs initiated' AND queue_name IS NULL;";
        String initiatedBefore = query(initiatedCount);

        Result attempt = psqlTolerant(String.join("\n",
            "BEGIN;",
            "INSERT INTO starexec.jobs (id,user_id,name,created,primary_space,cpuTimeout,",
            "  clockTimeout,maximum_memory,total_pairs,disk_size)",
            "  VALUES (9401,9001,'rollbackAtPairs',NOW(),9001,60,60,1073741824,1,0);",
            "INSERT INTO starexec.job_pairs (id,job_id,status_code,path)",
            "  VALUES (9401,9401,1,'p1');",
            // Induced failure: jobpair_stage_data.jobpair_id has a foreign key.
            "INSERT INTO starexec.jobpair_stage_data (jobpair_id,stage_number,status_code,disk_size)",
            "  VALUES (999999,1,1,0);",
            // The counter is part of the same outcome, so it is written inside the same
            // transaction and has to disappear with it.
            "CALL starexec.AddToEventOccurrencesNotRelatedToQueue('jobs initiated', 1);",
            "COMMIT;"));
        assertTrue("an aborted transaction must not commit: " + attempt.output,
            attempt.output.contains("ROLLBACK"));

        assertEquals("the job must not survive the failure of its pairs",
            "0", query("SELECT count(*) FROM starexec.jobs WHERE id=9401;"));
        assertEquals("no job pair may survive",
            "0", query("SELECT count(*) FROM starexec.job_pairs WHERE job_id=9401;"));
        assertEquals("no stage data may survive",
            stageDataBefore, query("SELECT count(*) FROM starexec.jobpair_stage_data;"));
        assertEquals("no unrelated pair may be disturbed",
            pairsBefore, query("SELECT count(*) FROM starexec.job_pairs;"));
        assertEquals("the jobs-initiated counter must not drift",
            initiatedBefore, query(initiatedCount));
    }

    /** Upload completion must report an outcome rather than nothing. */
    @Test
    public void uploadCompletionRoutinesResolve() throws Exception {
        run("SELECT starexec.set_upload_job_total_files(9001, 5);");
        assertEquals("NOT_FOUND", query("SELECT starexec.complete_upload_job(999999);"));
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private static void applyMigrations() throws Exception {
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(MIGRATIONS)) {
            List<Path> all = s.filter(p -> p.getFileName().toString().endsWith(".sql")).toList();
            all.stream()
                .filter(p -> p.getFileName().toString().startsWith("V"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(files::add);
            all.stream()
                .filter(p -> p.getFileName().toString().startsWith("R__"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(files::add);
        }
        assertTrue("no migrations found under " + MIGRATIONS.toAbsolutePath(), !files.isEmpty());

        for (Path f : files) {
            Result r = execWithStdin(180, Files.readString(f),
                runtime, "exec", "-i", CONTAINER,
                "psql", "-U", "starexec", "-d", DATABASE, "-v", "ON_ERROR_STOP=1", "-q");
            assertEquals("migration failed: " + f.getFileName() + "\n" + r.output, 0, r.exitCode);
        }
    }

    /**
     * The smallest fixture the routines under test will accept. Every column here is NOT
     * NULL without a default; leaving any of them out fails at insert rather than in the
     * routine, which is a confusing way to learn about a schema.
     */
    private static void seed() throws Exception {
        mustSucceedSeed(String.join("\n",
            "INSERT INTO starexec.users (id,email,first_name,last_name,institution,created,",
            "  password,disk_quota,disk_size) VALUES",
            "  (9001,'smoke@test','S','T','I',NOW(),'x',1000000000,0) ON CONFLICT DO NOTHING;",
            "INSERT INTO starexec.spaces (id,name,created,locked) VALUES",
            "  (9001,'smokeSpace',NOW(),false),",
            "  (9101,'cycleParent',NOW(),false),",
            "  (9102,'cycleChild',NOW(),false) ON CONFLICT DO NOTHING;",
            "INSERT INTO starexec.set_assoc (space_id,child_id) VALUES (9101,9102)",
            "  ON CONFLICT DO NOTHING;",
            "INSERT INTO starexec.closure (ancestor,descendant) VALUES",
            "  (9101,9101),(9102,9102),(9101,9102) ON CONFLICT DO NOTHING;",
            "INSERT INTO starexec.jobs (id,user_id,name,created,primary_space,cpuTimeout,",
            "  clockTimeout,maximum_memory,total_pairs,disk_size) VALUES",
            "  (9001,9001,'smokeJob',NOW(),9001,60,60,1073741824,3,0) ON CONFLICT DO NOTHING;",
            "INSERT INTO starexec.job_pairs (id,job_id,status_code,path) VALUES",
            "  (9001,9001,7,'p1'),(9002,9001,4,'p2'),(9003,9001,4,'p3') ON CONFLICT DO NOTHING;",
            "INSERT INTO starexec.jobpair_stage_data (jobpair_id,stage_number,status_code,",
            "  disk_size) VALUES (9001,1,7,0),(9002,1,4,0),(9003,1,4,0) ON CONFLICT DO NOTHING;"
        ));
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private static String detectRuntime() {
        for (String candidate : new String[] { "podman", "docker" }) {
            try {
                if (exec(15, candidate, "version", "--format", "{{.Client.Version}}").exitCode == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // try the next one
            }
        }
        return null;
    }

    /** Runs SQL and fails the test if psql reports an error. */
    private static void run(String sql) throws Exception {
        Result r = psql(DATABASE, sql);
        assertEquals("SQL failed: " + sql + "\n" + r.output, 0, r.exitCode);
    }

    /** Runs SQL expected to yield a single value, and returns it trimmed. */
    private static String query(String sql) throws Exception {
        Result r = psql(DATABASE, sql);
        assertEquals("query failed: " + sql + "\n" + r.output, 0, r.exitCode);
        return r.output.trim();
    }

    private static void mustSucceedSeed(String sql) {
        mustSucceed("seed fixture", DATABASE, sql);
    }

    /** Runs setup SQL and fails loudly if it did not work. */
    private static void mustSucceed(String what, String db, String sql) {
        Result r = execWithStdin(60, sql, runtime, "exec", "-i", CONTAINER,
            "psql", "-U", "starexec", "-d", db, "-q", "-v", "ON_ERROR_STOP=1");
        assertEquals(what + " failed:\n" + r.output, 0, r.exitCode);
    }

    private static Result psql(String db, String sql) throws Exception {
        return execWithStdin(120, sql, runtime, "exec", "-i", CONTAINER,
            "psql", "-U", "starexec", "-d", db, "-tA", "-v", "ON_ERROR_STOP=1");
    }

    /**
     * Runs SQL that is <em>expected</em> to fail part-way, without ON_ERROR_STOP, so psql
     * carries on to the closing COMMIT and reports what the server did with it. The output is
     * the assertion; the exit code is not.
     */
    private static Result psqlTolerant(String sql) throws Exception {
        return execWithStdin(120, sql, runtime, "exec", "-i", CONTAINER,
            "psql", "-U", "starexec", "-d", DATABASE, "-tA", "-e");
    }

    private static Result exec(int timeoutSeconds, String... command) {
        return execWithStdin(timeoutSeconds, null, command);
    }

    /**
     * Runs a command with {@code stdin} as its input, through temporary files rather than
     * pipes.
     *
     * <p>Pipes deadlock here. This wrote the whole of {@code stdin} before reading any
     * output, and {@code R__procedures_and_views.sql} is ~400 KB against a 64 KB pipe
     * buffer, so psql has to be consuming input throughout -- which it stops doing as soon
     * as its own output fills the other 64 KB pipe. On a fresh database that output is
     * ~43 KB of "does not exist, skipping" NOTICEs, close enough to the limit that adding
     * or removing a few DROP statements decides it.
     *
     * <p>The failure is also invisible: the blocking {@code readAllBytes} sits before
     * {@code waitFor}, so the timeout below is never reached and the build hangs instead of
     * failing. Observed as a 30-minute stall in {@code mvn verify -Pit} that a 180-second
     * timeout should have caught.
     *
     * <p>Files have no such limit and leave the timeout reachable.
     */
    private static Result execWithStdin(int timeoutSeconds, String stdin, String... command) {
        Path in = null;
        Path out = null;
        try {
            out = Files.createTempFile("smoke-out", ".txt");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(out.toFile());
            pb.directory(new File("."));
            if (stdin != null) {
                in = Files.createTempFile("smoke-in", ".sql");
                Files.writeString(in, stdin, StandardCharsets.UTF_8);
                pb.redirectInput(in.toFile());
            } else {
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            }

            Process p = pb.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, readOrEmpty(out) + "\n[timed out after " + timeoutSeconds + "s]");
            }
            return new Result(p.exitValue(), readOrEmpty(out));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Result(-1, String.valueOf(e.getMessage()));
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    private static String readOrEmpty(Path file) {
        try {
            return file == null ? "" : Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // a temp file the OS will clean up
        }
    }

    private static final class Result {
        final int exitCode;
        final String output;
        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
