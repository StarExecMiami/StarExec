package org.starexec.backend;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.starexec.backend.exception.RetryableIngestionException;
import org.starexec.logger.StarLogger;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decides which runsolver measurements a pair's output records, and against which stage.
 *
 * <h2>The protocol</h2>
 *
 * {@code stats.json} is one slot for the whole pair: every stage's {@code copyOutput} replaces the
 * previous stage's measurements. The monitors recorded it once, against the pair's terminal stage,
 * so a two-stage pair kept only its final stage's cpu, wallclock and memory, and a final stage
 * that never reached {@code copyOutput} was given the stage before it's numbers.
 *
 * <p>{@code functions.bash} now also publishes each stage's measurements, whole and by rename, as
 * {@code $STAREXEC_OUTPUT_DIR/stage-stats/<n>.json}. It creates the {@code stage-stats} directory
 * when the pair starts, and that directory is the marker this class reads. It is separate from
 * {@link StageAttributeFiles}'s marker because a helper can have one protocol and not the other.
 *
 * <h2>Rules</h2>
 *
 * <ul>
 *   <li><b>Marker present:</b> only per-stage files are used, under the rules of
 *       {@link StageResultFiles}. A file is believed only when it names this pair and this stage
 *       and carries every measurement as a number. Zero is a measurement: runsolver samples
 *       virtual memory only after 0.1 s, so a short run legitimately reports {@code MAXVM=0}.</li>
 *   <li><b>Marker absent</b> (a helper predating the protocol): {@code stats.json} is used only
 *       when it explicitly names this pair and a stage the pair has, and that stage has finished.
 *       It is recorded under the stage it names, never under the terminal stage.</li>
 * </ul>
 *
 * <p>Like {@link StageStatusSnapshots} this holds no database connection and writes nothing.
 */
public final class StageStatsFiles {

    private static final StarLogger log = StarLogger.getLogger(StageStatsFiles.class);

    /** The directory the producer writes, relative to the pair's output directory. */
    static final String DIRECTORY = "stage-stats";

    /** The pair-wide file an older helper writes. */
    static final String LEGACY_FILE = "stats.json";

    /** One stage's measurements, as {@code UpdatePairRunSolverStats} takes them. */
    public static final class Stats {
        public final double wallclockTime;
        public final double cpuTime;
        public final double userTime;
        public final double systemTime;
        public final double maxVirtualMemory;
        public final long maxResidentSetSize;
        public final long diskSize;
        /** The node the stage ran on, or {@code null} when the file named none. */
        public final String hostname;

        Stats(double wallclockTime, double cpuTime, double userTime, double systemTime,
              double maxVirtualMemory, long maxResidentSetSize, long diskSize, String hostname) {
            this.wallclockTime = wallclockTime;
            this.cpuTime = cpuTime;
            this.userTime = userTime;
            this.systemTime = systemTime;
            this.maxVirtualMemory = maxVirtualMemory;
            this.maxResidentSetSize = maxResidentSetSize;
            this.diskSize = diskSize;
            this.hostname = hostname;
        }

        @Override
        public String toString() {
            return "Stats{wall=" + wallclockTime + ", cpu=" + cpuTime + ", user=" + userTime
                + ", system=" + systemTime + ", maxvm=" + maxVirtualMemory + ", rss="
                + maxResidentSetSize + ", disk=" + diskSize + ", host=" + hostname + "}";
        }
    }

    private StageStatsFiles() {
    }

    /** Whether the helper that produced this output declared per-stage measurements. */
    public static boolean declared(Path outputDir) {
        return outputDir != null
            && Files.exists(outputDir.resolve(DIRECTORY), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * The measurements to record, by stage.
     *
     * @param outputDir       the pair's output directory, already known to belong to this execution
     * @param pairId          the pair being processed, from the caller's own records
     * @param finishedEarlier stages before the terminal one whose snapshots the caller has read as
     *                        terminal
     * @param terminalStage   the stage that produced the pair's terminal result, or a value below
     *                        1 while the pair is still running
     * @param pairStages      the pair's stage numbers, consulted only when needed
     * @return stage number to measurements, in stage order
     * @throws RetryableIngestionException when the pair's stages or a present file could not be
     *         read
     */
    public static Map<Integer, Stats> select(
        Path outputDir,
        int pairId,
        Set<Integer> finishedEarlier,
        int terminalStage,
        StageAttributeFiles.PairStages pairStages
    ) throws RetryableIngestionException {

        if (declared(outputDir)) {
            return Collections.unmodifiableMap(StageResultFiles.select(
                outputDir.resolve(DIRECTORY),
                ".json",
                "measurements",
                pairId,
                finishedEarlier,
                terminalStage,
                pairStages,
                (content, stage) -> parse(content, pairId, stage, true)
            ));
        }

        Map<Integer, Stats> selected = new TreeMap<>();
        boolean finishedAny = terminalStage >= 1 || !finishedEarlier.isEmpty();
        Path legacy = outputDir == null ? null : outputDir.resolve(LEGACY_FILE);
        if (!finishedAny || legacy == null
            || !Files.isRegularFile(legacy, LinkOption.NOFOLLOW_LINKS)) {
            return selected;
        }
        String content = StageResultFiles.read(
            legacy, pairId, "stats.json may take; not recording measurements for pair " + pairId);
        if (content == null) {
            return selected;
        }
        JsonObject json = object(content, legacy, pairId);
        Integer stage = json == null ? null : integer(json, "stageNumber");
        if (stage == null || stage < 1) {
            log.warn(
                legacy + " names no stage, and its job script predates per-stage measurements;"
                    + " not recording measurements for pair " + pairId
            );
            return selected;
        }
        if (stage != terminalStage && !finishedEarlier.contains(stage)) {
            // Its stage has not finished as far as the caller knows, which on a Local poll is
            // simply not yet. Nothing to say.
            return selected;
        }
        Set<Integer> stages = StageResultFiles.stagesOf(pairId, pairStages, "measurements");
        if (!stages.contains(stage)) {
            log.warn(
                "Pair " + pairId + " has no stage " + stage + " (its stages are " + stages
                    + "), which " + legacy + " names; not recording measurements for it"
            );
            return selected;
        }
        Stats stats = measurements(json, legacy, pairId, stage, false);
        if (stats != null) {
            selected.put(stage, stats);
        }
        return selected;
    }

    /**
     * One file's measurements, or {@code null} when the file is not believed.
     *
     * @param stageFile whether this is a per-stage file, whose stage number must be {@code stage};
     *                  the legacy file's was already checked by the caller
     */
    static Stats parse(String content, int pairId, int stage, boolean stageFile) {
        JsonObject json = object(content, "stage " + stage + "'s measurements", pairId);
        if (json == null) {
            return null;
        }
        if (stageFile) {
            Integer named = integer(json, "stageNumber");
            if (named == null || named != stage) {
                log.warn(
                    "Pair " + pairId + " stage " + stage + "'s measurements file names stage "
                        + named + "; not recording it"
                );
                return null;
            }
        }
        return measurements(json, "stage " + stage + "'s measurements", pairId, stage, stageFile);
    }

    private static Stats measurements(
        JsonObject json, Object source, int pairId, int stage, boolean stageFile) {
        Integer named = integer(json, "pairId");
        if (named == null || named != pairId) {
            log.warn(
                source + " names pair " + named + ", not pair " + pairId + "; not recording it"
            );
            return null;
        }
        Double wall = measurement(json, "wallclockTime");
        Double cpu = measurement(json, "cpuTime");
        Double user = measurement(json, "userTime");
        Double system = measurement(json, "systemTime");
        Double maxvm = measurement(json, "maxVirtualMemory");
        Long rss = count(json, "maxResidentSetSize");
        Long disk = count(json, "diskSize");
        if (wall == null || cpu == null || user == null || system == null || maxvm == null
            || rss == null || disk == null) {
            log.warn(
                source + " for pair " + pairId + " stage " + stage + " is missing a measurement"
                    + " or has one that is not a non-negative number; not recording it: " + json
            );
            return null;
        }
        String hostname = null;
        JsonElement host = json.get("hostname");
        if (host != null && host.isJsonPrimitive() && host.getAsJsonPrimitive().isString()) {
            String value = host.getAsString().trim();
            hostname = value.isEmpty() ? null : value;
        }
        return new Stats(wall, cpu, user, system, maxvm, rss, disk, hostname);
    }

    private static JsonObject object(String content, Object source, int pairId) {
        try {
            JsonElement element = JsonParser.parseString(content);
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (JsonParseException e) {
            // reported below
        }
        log.warn(source + " for pair " + pairId + " is not a JSON object; not recording it");
        return null;
    }

    /** A JSON number present under {@code key}; never a default. */
    private static BigDecimal number(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            return null;
        }
        try {
            return new BigDecimal(primitive.getAsString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(JsonObject json, String key) {
        BigDecimal value = number(json, key);
        if (value == null) {
            return null;
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static Double measurement(JsonObject json, String key) {
        BigDecimal value = number(json, key);
        if (value == null || value.signum() < 0) {
            return null;
        }
        double d = value.doubleValue();
        return Double.isFinite(d) ? d : null;
    }

    private static Long count(JsonObject json, String key) {
        BigDecimal value = number(json, key);
        if (value == null || value.signum() < 0) {
            return null;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }
}
