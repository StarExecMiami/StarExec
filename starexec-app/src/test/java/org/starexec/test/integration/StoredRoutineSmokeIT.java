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

    private static Result exec(int timeoutSeconds, String... command) {
        return execWithStdin(timeoutSeconds, null, command);
    }

    private static Result execWithStdin(int timeoutSeconds, String stdin, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.directory(new File("."));
            Process p = pb.start();
            if (stdin != null) {
                p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            p.getOutputStream().close();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, output + "\n[timed out after " + timeoutSeconds + "s]");
            }
            return new Result(p.exitValue(), output);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Result(-1, String.valueOf(e.getMessage()));
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
