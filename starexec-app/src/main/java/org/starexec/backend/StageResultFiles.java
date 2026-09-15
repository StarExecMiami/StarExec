package org.starexec.backend;

import org.starexec.backend.exception.RetryableIngestionException;
import org.starexec.logger.StarLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The rules every per-stage result file shares, whatever it carries: which stages may be read,
 * and how one stage's file is read.
 *
 * <p>A stage is read only once the caller knows it has finished -- an earlier stage whose snapshot
 * is terminal, or the pair's terminal stage at completion -- and only if the pair actually has
 * that stage. Its file must be a regular file, not a link, and no larger than {@link #MAX_BYTES}.
 * What the file means is the parser's business.
 */
final class StageResultFiles {

    private static final StarLogger log = StarLogger.getLogger(StageResultFiles.class);

    /**
     * A stage's result file is a handful of lines. Reading whatever size a job chose to write
     * would let it exhaust the heap, and an {@link OutOfMemoryError} escapes the monitors'
     * {@code catch (Exception)}.
     */
    static final long MAX_BYTES = 1024L * 1024L;

    /** Turns one stage's file content into what the caller records. */
    @FunctionalInterface
    interface Parser<T> {
        /**
         * @param content the file's content
         * @param stage   the stage the file was published for
         * @return the parsed result, or {@code null} when the file is not to be believed, which
         *         the parser has already logged
         */
        T parse(String content, int stage) throws IOException;
    }

    private StageResultFiles() {
    }

    /**
     * The finished stages' parsed files, by stage.
     *
     * @param dir            the per-stage directory, known to be declared
     * @param suffix         the file name after the stage number, such as {@code ".txt"}
     * @param what           what the files carry, for log messages, such as "attributes"
     * @param finishedEarlier stages before the terminal one whose snapshots are terminal
     * @param terminalStage  the stage that produced the terminal result, or below 1 while the
     *                       pair is still running
     * @return a mutable map in stage order; a finished stage without a believable file is absent
     */
    static <T> Map<Integer, T> select(
        Path dir,
        String suffix,
        String what,
        int pairId,
        Set<Integer> finishedEarlier,
        int terminalStage,
        StageAttributeFiles.PairStages pairStages,
        Parser<T> parser
    ) throws RetryableIngestionException {

        final boolean atCompletion = terminalStage >= 1;
        Map<Integer, T> selected = new TreeMap<>();

        Set<Integer> finished = new TreeSet<>(finishedEarlier);
        if (atCompletion) {
            finished.add(terminalStage);
        }
        if (finished.isEmpty()) {
            return selected;
        }

        Set<Integer> stages = stagesOf(pairId, pairStages, what);
        for (int stage : finished) {
            if (!stages.contains(stage)) {
                log.warn(
                    "Pair " + pairId + " has no stage " + stage + " (its stages are " + stages
                        + "); not recording " + what + " for it"
                );
                continue;
            }
            Path file = dir.resolve(stage + suffix);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                // Only said once the pair is complete: a Local monitor sees this state on every
                // poll between a stage finishing and the pair finishing.
                if (atCompletion) {
                    log.warn(
                        "Pair " + pairId + " stage " + stage + " finished without a per-stage"
                            + " " + what + " file; recording no " + what + " for it"
                    );
                }
                continue;
            }
            String content = read(
                file, pairId,
                "a stage's " + what + " may take; not recording " + what + " for pair " + pairId
                    + " stage " + stage);
            if (content == null) {
                continue;
            }
            T parsed;
            try {
                parsed = parser.parse(content, stage);
            } catch (IOException e) {
                throw new RetryableIngestionException(
                    "Could not read " + file + " for pair " + pairId, e);
            }
            if (parsed != null) {
                selected.put(stage, parsed);
            }
        }
        return selected;
    }

    /** The pair's stages; not knowing them is not a reason to guess them. */
    static Set<Integer> stagesOf(int pairId, StageAttributeFiles.PairStages pairStages, String what)
        throws RetryableIngestionException {
        Set<Integer> stages = pairStages.get();
        if (stages == null) {
            throw new RetryableIngestionException(
                "Could not read the stages of pair " + pairId + " to attribute its " + what);
        }
        return stages;
    }

    /**
     * A file's content, or {@code null} when it is over {@link #MAX_BYTES}.
     *
     * @param refusal completes the log line when the file is too large: "file is N bytes, over
     *                the MAX " + refusal
     */
    static String read(Path file, int pairId, String refusal) throws RetryableIngestionException {
        try {
            long size = Files.size(file);
            if (size > MAX_BYTES) {
                log.warn(file + " is " + size + " bytes, over the " + MAX_BYTES + " " + refusal);
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RetryableIngestionException(
                "Could not read " + file + " for pair " + pairId, e);
        }
    }
}
