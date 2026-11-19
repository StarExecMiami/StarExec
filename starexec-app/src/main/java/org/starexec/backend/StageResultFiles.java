package org.starexec.backend;

import org.starexec.backend.exception.RetryableIngestionException;
import org.starexec.logger.StarLogger;
import org.starexec.util.DescriptorDirectory;

import java.io.IOException;
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
 * Trusted live producers publish complete files by atomic replacement. Untrusted producers
 * require a private output directory and complete writer termination before these reads.
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

    /**
     * What a finished stage without its file means, which decides the level it is logged at.
     * StarLogger stores every WARN as an {@code error_logs} row, and this is said once per finished
     * stage, so only a lost result may be a WARN.
     */
    enum Absence {
        /** The stage may have none: attributes come only from a post-processor that succeeded. DEBUG. */
        NORMAL,
        /** Every stage that ran writes one, so the stage's result is lost. WARN. */
        LOSS
    }

    private StageResultFiles() {
    }

    /**
     * The finished stages' parsed files, by stage.
     *
     * @param dir            the per-stage directory, known to be declared
     * @param suffix         the file name after the stage number, such as {@code ".txt"}
     * @param what           what the files carry, for log messages, such as "attributes"
     * @param absence        what a finished stage without its file means
     * @param finishedEarlier stages before the terminal one whose snapshots are terminal
     * @param terminalStage  the stage that produced the terminal result, or below 1 while the
     *                       pair is still running
     * @return a mutable map in stage order; a finished stage without a believable file is absent
     */
    static <T> Map<Integer, T> select(
        Path dir,
        String suffix,
        String what,
        Absence absence,
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
                    String missing = "Pair " + pairId + " stage " + stage + " finished without a"
                        + " per-stage " + what + " file; recording no " + what + " for it";
                    if (absence == Absence.NORMAL) {
                        log.debug(missing);
                    } else {
                        log.warn(missing);
                    }
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
     * A published file's content, or {@code null} when it is unsafe or over {@link #MAX_BYTES}.
     *
     * @param refusal explains which result is omitted when the file is unsafe or too large
     */
    static String read(Path file, int pairId, String refusal) throws RetryableIngestionException {
        try (DescriptorDirectory directory = DescriptorDirectory.open(file.toAbsolutePath().getParent())) {
            return directory.readPublishedText(file.getFileName().toString(), (int) MAX_BYTES);
        } catch (DescriptorDirectory.InvalidFileException e) {
            log.warn(file + " cannot supply a regular file within " + MAX_BYTES + " bytes: " + refusal);
            return null;
        } catch (IOException e) {
            throw new RetryableIngestionException(
                "Could not read " + file + " for pair " + pairId, e);
        }
    }
}
