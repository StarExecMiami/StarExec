package org.starexec.backend;

import org.starexec.backend.exception.RetryableIngestionException;
import org.starexec.constants.R;
import org.starexec.logger.StarLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Decides which post-processor attributes a pair's output records, and against which stage.
 *
 * <h2>The protocol</h2>
 *
 * {@code attributes.txt} is one slot for the whole pair: every stage's post-processor output
 * replaces the previous stage's, and nothing in the file says which stage wrote it. The monitors
 * used to record it against a hardcoded stage 1 (Local, Container) or against the pair's terminal
 * stage (Kubernetes), so a two-stage pair filed the final stage's result under stage 1 and lost
 * stage 1's own.
 *
 * <p>{@code functions.bash} now also publishes each stage's post-processor output, whole and by
 * rename, as {@code $STAREXEC_OUTPUT_DIR/stage-attributes/<n>.txt}. It creates the
 * {@code stage-attributes} directory when the pair starts, before any stage has run, and that
 * directory is the marker this class reads: its presence says the helper speaks the per-stage
 * protocol, whether or not any stage has published yet.
 *
 * <h2>Rules</h2>
 *
 * <ul>
 *   <li><b>Marker present:</b> only per-stage files are used; the legacy {@code attributes.txt}
 *       is never read. A stage is considered only once the caller knows it has finished -- an
 *       earlier stage whose snapshot is terminal, or the pair's terminal stage at completion --
 *       and only if the pair actually has that stage. A finished stage with no file has no
 *       attributes (it ran no post-processor, or the post-processor failed).</li>
 *   <li><b>Marker absent</b> (a helper predating the protocol): the legacy file names no stage,
 *       so it is recorded only for a single-stage pair, under that pair's only stage, and only at
 *       completion. For a multi-stage pair it is not recorded at all. A missing attribution is
 *       better than a wrong one, and neither stage 1 nor the terminal stage is a safe guess --
 *       a final stage without a post-processor leaves the previous stage's file behind.</li>
 * </ul>
 *
 * <p>Like {@link StageStatusSnapshots} this holds no database connection and writes nothing.
 * The pair's stage numbers come from the caller, fetched only when there is something to decide.
 */
public final class StageAttributeFiles {

    private static final StarLogger log = StarLogger.getLogger(StageAttributeFiles.class);

    /** The directory the producer writes, relative to the pair's output directory. */
    static final String DIRECTORY = "stage-attributes";

    /**
     * Post-processor output is a handful of key=value lines. Reading whatever size a job chose
     * to write would let it exhaust the heap, and an {@link OutOfMemoryError} escapes the
     * monitors' {@code catch (Exception)}.
     */
    private static final long MAX_BYTES = 1024L * 1024L;

    /** The stages a pair has, from the database. */
    @FunctionalInterface
    public interface PairStages {
        /** @return the pair's stage numbers, or {@code null} when they could not be read */
        Set<Integer> get();
    }

    private StageAttributeFiles() {
    }

    /**
     * Whether the helper that produced this output declared the per-stage protocol.
     *
     * <p>Existence of the entry itself, following no link: a directory replaced by a link, or by
     * anything else, still declares the protocol, so the pair cannot be steered back onto the
     * legacy path. It just has no readable per-stage files.
     */
    public static boolean declared(Path outputDir) {
        return outputDir != null
            && Files.exists(outputDir.resolve(DIRECTORY), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * The attributes to record, by stage.
     *
     * @param outputDir      the pair's output directory, already known to belong to this execution
     * @param pairId         the pair being processed, from the caller's own records
     * @param finishedEarlier stages before the terminal one whose snapshots the caller has read as
     *                       terminal
     * @param terminalStage  the stage that produced the pair's terminal result, or a value below 1
     *                       while the pair is still running or when no stage was named
     * @param legacy         the parsed legacy {@code attributes.txt}; empty when it is absent
     * @param pairStages     the pair's stage numbers, consulted only when needed
     * @return stage number to attributes, in stage order; a stage whose file is empty maps to an
     *         empty set
     * @throws RetryableIngestionException when the pair's stages or a present file could not be
     *         read. Nothing has been decided, so the caller can try again.
     */
    public static Map<Integer, Properties> select(
        Path outputDir,
        int pairId,
        Set<Integer> finishedEarlier,
        int terminalStage,
        Properties legacy,
        PairStages pairStages
    ) throws RetryableIngestionException {

        final boolean atCompletion = terminalStage >= 1;
        Map<Integer, Properties> selected = new TreeMap<>();

        if (!declared(outputDir)) {
            if (!atCompletion || legacy.isEmpty()) {
                return selected;
            }
            Set<Integer> stages = stagesOf(pairId, pairStages);
            if (stages.size() == 1) {
                selected.put(stages.iterator().next(), legacy);
            } else {
                log.warn(
                    "Pair " + pairId + " has " + stages.size() + " stages and its job script"
                        + " predates per-stage attributes, so attributes.txt cannot be attributed"
                        + " to a stage; not recording it"
                );
            }
            return selected;
        }

        Set<Integer> finished = new TreeSet<>(finishedEarlier);
        if (atCompletion) {
            finished.add(terminalStage);
        }
        if (finished.isEmpty()) {
            return selected;
        }

        Set<Integer> stages = stagesOf(pairId, pairStages);
        Path dir = outputDir.resolve(DIRECTORY);
        for (int stage : finished) {
            if (!stages.contains(stage)) {
                log.warn(
                    "Pair " + pairId + " has no stage " + stage + " (its stages are " + stages
                        + "); not recording attributes for it"
                );
                continue;
            }
            Path file = dir.resolve(stage + ".txt");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                // Only said once the pair is complete: a Local monitor sees this state on every
                // poll between a stage finishing and the pair finishing.
                if (atCompletion) {
                    log.warn(
                        "Pair " + pairId + " stage " + stage + " finished without a per-stage"
                            + " attributes file; recording no attributes for it"
                    );
                }
                continue;
            }
            Properties attributes = read(file, pairId, stage);
            if (attributes != null) {
                selected.put(stage, attributes);
            }
        }
        return Collections.unmodifiableMap(selected);
    }

    private static Set<Integer> stagesOf(int pairId, PairStages pairStages)
        throws RetryableIngestionException {
        Set<Integer> stages = pairStages.get();
        if (stages == null) {
            throw new RetryableIngestionException(
                "Could not read the stages of pair " + pairId + " to attribute its attributes");
        }
        return stages;
    }

    /** One stage's file, or {@code null} when it is too large to be believed. */
    private static Properties read(Path file, int pairId, int stage)
        throws RetryableIngestionException {
        try {
            long size = Files.size(file);
            if (size > MAX_BYTES) {
                log.warn(
                    file + " is " + size + " bytes, over the " + MAX_BYTES + " a stage's attributes"
                        + " may take; not recording attributes for pair " + pairId + " stage " + stage
                );
                return null;
            }
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RetryableIngestionException(
                "Could not read " + file + " for pair " + pairId, e);
        }
    }

    /**
     * Parses post-processor output: one {@code key=value} per line, blank lines and lines without
     * a key ignored, and an empty {@code starexec-result} normalised to {@code starexec-unknown}.
     * The same rules the monitors apply to the legacy file.
     */
    static Properties parse(String content) throws IOException {
        Properties props = new Properties();
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
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
        }
        return props;
    }
}
