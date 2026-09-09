package org.starexec.backend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.starexec.data.to.Status.StatusCode;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the per-stage status records a finished job left behind, and refuses everything it
 * cannot vouch for.
 *
 * <h2>The protocol</h2>
 *
 * A job's status file is a single slot: {@code functions.bash} writes
 * {@code $STAREXEC_OUTPUT_DIR/status.json} with {@code >} once per stage, so in a multi-stage
 * pair each stage truncates the previous one and only the last survives. Beside it the same
 * helper writes the identical record once per stage to
 * {@code $STAREXEC_OUTPUT_DIR/stage-status/<n>.json}, through a temporary file and a rename, so
 * that earlier stages have somewhere to live. That directory is what this class reads.
 *
 * <h2>What this class is, and deliberately is not</h2>
 *
 * It is the backend-independent half of the protocol: discover, parse, validate, return. It
 * holds no database connection, knows nothing about Kubernetes, container ids or
 * {@code ExecutionRef}, and never decides what to do about a failure. Those belong to the
 * backend, because they differ per backend -- ownership, retry policy, cleanup and accounting
 * are not shared and must not become shared by accident.
 *
 * <p>The consequence worth stating: this class answers "is this a record I can believe?" and
 * never "should this be written?". A caller still has to decide, against its own notion of
 * ownership, whether the pair these records name is the pair it is currently processing.
 *
 * <h2>Why the rules are what they are</h2>
 *
 * The directory is writable by solver code -- the job container runs everything as root -- so
 * every field is checked against something the caller supplies rather than something the file
 * asserts. The pair comes from the caller. The stage in the file name must agree with the stage
 * inside the record. And the status must be one a stage may legally be left holding.
 *
 * <p>That last rule is the sharp one. {@code STATUS_PROCESSING_RESULTS(19)},
 * {@code STATUS_PAUSED(20)} and {@code STATUS_PROCESSING(22)} all mean work is still owed, and a
 * stage parked at 22 is picked up by the periodic post-processing task, which then sets the whole
 * PAIR to {@code STATUS_COMPLETE}. Accepting one of them would let a job convert its own timeout
 * into a clean completion. The authority is {@link StatusCode#isTerminalExecutionResult()}, which
 * mirrors {@code starexec.IsTerminalPairStatus} -- the predicate the database itself enforces --
 * and the two are pinned together value by value by {@code TerminalStatusContractSqlTest}. A
 * numeric test such as {@code >= 7} is not equivalent and must not be substituted.
 *
 * <p>A record that fails any check aborts the whole read rather than being skipped. Half a
 * pair's history is worse than none: the caller can retry a refusal, but it cannot detect a
 * silent omission.
 */
public final class StageStatusSnapshots {

    /**
     * File names this accepts, bounded to nine digits.
     *
     * <p>The bound is not cosmetic. An unbounded {@code [0-9]*} matches a twenty-digit name that
     * overflows {@code int}, and the resulting {@link NumberFormatException} would escape the
     * read as an unchecked exception -- which, for a caller that retains its evidence and retries,
     * means the same file is re-read and re-throws on every pass. Nine digits is past any real
     * stage count and comfortably inside {@link Integer#MAX_VALUE}. It matches the producer's own
     * guard in {@code functions.bash}, so this cannot reject a name the helper can emit.
     */
    private static final Pattern SNAPSHOT_NAME = Pattern.compile("^([1-9][0-9]{0,8})\\.json$");

    /** The directory the producer writes, relative to the pair's output directory. */
    private static final String DIRECTORY = "stage-status";

    /**
     * A snapshot is one short JSON object. Reading whatever size the job chose to write would let
     * it exhaust the heap, and {@link OutOfMemoryError} is an {@code Error} -- it escapes
     * {@code catch (Exception)} and would take the caller's processing loop with it.
     */
    private static final long MAX_BYTES = 8L * 1024L;

    /** Refuses a directory holding more entries than any real pair could have stages. */
    private static final int MAX_ENTRIES = 1024;

    private StageStatusSnapshots() {
    }

    /**
     * Reads and validates every per-stage record for one pair.
     *
     * @param outputDir     the pair's output directory, resolved by the caller from whatever it
     *                      trusts; must already be known to belong to the execution being
     *                      processed
     * @param expectedPairId the pair the caller believes it is processing, from the caller's own
     *                      records and never from a file the job wrote
     * @return stage number to status code, in stage order; empty when the directory is absent,
     *         which is how output from a job script predating the protocol reads
     * @throws InvalidSnapshotException when a record is malformed, oversized, inconsistent with
     *         its own file name, names a different pair, or carries a status that is not a
     *         terminal execution result
     * @throws IOException when the directory cannot be read
     */
    public static Map<Integer, Integer> read(Path outputDir, int expectedPairId)
        throws InvalidSnapshotException, IOException {

        return read(outputDir, expectedPairId, Integer.MAX_VALUE);
    }

    /**
     * Reads and validates the per-stage records for one pair, up to but excluding the stage the
     * caller is currently resolving.
     *
     * <h2>Why a stage bound is part of the read</h2>
     *
     * <p>The producer writes a stage's snapshot twice: {@code STATUS_RUNNING} when the stage
     * starts ({@code functions.bash} {@code sendNode}) and its real outcome when the stage ends.
     * Between those two writes the file legitimately holds a non-terminal status, and a pair
     * killed mid-stage legitimately leaves it that way for good.
     *
     * <p>The two-argument overload validates every snapshot it finds, which is correct only once
     * no stage is still in flight. Applied to a running pair it rejects the pair's own current
     * stage, and because an {@link InvalidSnapshotException} is classified as a deterministic
     * artifact defect -- the same bytes failing the same check forever -- the caller holds the
     * pair for intervention and never looks again. The pair then finishes cleanly, its evidence
     * becomes consistent, and nothing re-reads it. That is a completed run lost to a poll that
     * arrived a second early.
     *
     * <p>So the bound is what makes the deterministic classification honest. Every check this
     * class applies to a returned record is a property of the bytes: a malformed record, an
     * oversized one, a name disagreeing with its contents, a record naming another pair. Those
     * never become valid by waiting. Terminality is the one rule that depends on when the file is
     * read, and it is applied only where it cannot depend on timing -- to stages the caller has
     * already moved past.
     *
     * <h2>Why excluding rather than merely not checking</h2>
     *
     * <p>Stages at or after {@code terminalStage} are left out of the result entirely rather than
     * returned unchecked. The directory is writable by solver code, and the status-laundering this
     * class exists to prevent needs only for an unvalidated status to reach a caller that ingests
     * it. A value that is never returned cannot be ingested by a caller that forgets to filter,
     * so the guarantee holds here instead of resting on every caller repeating it.
     *
     * <p>The terminal stage's own status is not this class's to supply in any case: it comes from
     * {@code status.json} and the runsolver artifacts, which is why both callers already discard
     * it.
     *
     * @param outputDir      as above
     * @param expectedPairId as above
     * @param terminalStage  the stage the caller is resolving from other evidence. Records for
     *                       this stage and later are neither returned nor checked for terminality.
     *                       Pass {@link Integer#MAX_VALUE} to validate every stage, which is what
     *                       the two-argument overload does.
     * @return stage number to status code, in stage order, for stages before {@code terminalStage}
     * @throws InvalidSnapshotException when a returned record is malformed, oversized,
     *         inconsistent with its own file name, names a different pair, or carries a status
     *         that is not a terminal execution result
     * @throws IOException when the directory cannot be read
     */
    public static Map<Integer, Integer> read(
        Path outputDir,
        int expectedPairId,
        int terminalStage
    ) throws InvalidSnapshotException, IOException {

        if (outputDir == null) {
            return Collections.emptyMap();
        }
        Path dir = outputDir.resolve(DIRECTORY);
        if (!Files.isDirectory(dir)) {
            return Collections.emptyMap();
        }

        Map<Integer, Integer> snapshots = new TreeMap<>();
        int seen = 0;

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (++seen > MAX_ENTRIES) {
                    throw new InvalidSnapshotException(
                        dir + " holds more than " + MAX_ENTRIES + " entries; refusing to read it"
                    );
                }

                String name = entry.getFileName().toString();
                Matcher named = SNAPSHOT_NAME.matcher(name);
                if (!named.matches()) {
                    // The producer's own temporary file, or something that is not a snapshot.
                    // Ignored rather than rejected: an unrecognised name is not a claim about
                    // any stage, so there is nothing to disbelieve.
                    continue;
                }
                int stageFromName;
                try {
                    stageFromName = Integer.parseInt(named.group(1));
                } catch (NumberFormatException impossible) {
                    // SNAPSHOT_NAME caps the group at nine digits, so this cannot fire while
                    // the two agree. Handled anyway rather than relying on a constraint three
                    // lines away: if that bound is ever loosened, an overflow here would
                    // escape as an unchecked exception and the caller -- which retains its
                    // evidence and retries -- would re-read the same file and re-throw on
                    // every poll. A refusal is recoverable; a wedge is not.
                    throw new InvalidSnapshotException(
                        entry + " is named for a stage number that is not a usable integer",
                        impossible
                    );
                }

                if (stageFromName >= terminalStage) {
                    // The stage the caller is still resolving, or one after it. Skipped before any
                    // content is read, not merely left unvalidated: while a stage runs, its
                    // snapshot is rewritten, so every property of the bytes -- shape, size, the
                    // status inside -- is provisional. Reading them here would let the timing of a
                    // poll decide whether the pair is refused, and a refusal is permanent.
                    continue;
                }

                long size = Files.size(entry);
                if (size > MAX_BYTES) {
                    throw new InvalidSnapshotException(
                        entry + " is " + size + " bytes; a stage snapshot may not exceed " + MAX_BYTES
                    );
                }

                JsonObject record;
                try {
                    record = JsonParser.parseString(Files.readString(entry)).getAsJsonObject();
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new InvalidSnapshotException("malformed stage snapshot " + entry, e);
                }

                int recordPairId = requiredInt(record, "pairId", entry);
                int recordStage = requiredInt(record, "stageNumber", entry);
                int recordStatus = requiredInt(record, "status", entry);

                if (recordPairId != expectedPairId) {
                    throw new InvalidSnapshotException(
                        entry + " claims pair " + recordPairId + " but this execution is pair "
                            + expectedPairId
                    );
                }
                if (recordStage != stageFromName) {
                    throw new InvalidSnapshotException(
                        entry + " is named for stage " + stageFromName + " but records stage "
                            + recordStage
                    );
                }
                if (!StatusCode.toStatusCode(recordStatus).isTerminalExecutionResult()) {
                    throw new InvalidSnapshotException(
                        entry + " carries status " + recordStatus
                            + ", which is not a terminal execution result"
                    );
                }

                snapshots.put(stageFromName, recordStatus);
            }
        }

        return Collections.unmodifiableMap(snapshots);
    }

    private static int requiredInt(JsonObject record, String field, Path entry)
        throws InvalidSnapshotException {
        try {
            if (!record.has(field) || record.get(field).isJsonNull()) {
                throw new InvalidSnapshotException(entry + " has no " + field);
            }
            return record.get(field).getAsInt();
        } catch (InvalidSnapshotException e) {
            throw e;
        } catch (Exception e) {
            // getAsInt throws for an object, an array, or a non-numeric string. Reported with
            // the path, because the path is the useful half of the message.
            throw new InvalidSnapshotException(entry + " has a non-integer " + field, e);
        }
    }

    /**
     * A record that cannot be believed.
     *
     * <p>Checked, so a caller cannot ignore it by accident, and distinct from {@link IOException}
     * so that "the file system was unavailable" and "the job wrote something it should not have"
     * stay separable. Both are the caller's to classify, but they are not the same event.
     */
    public static class InvalidSnapshotException extends Exception {

        private static final long serialVersionUID = 1L;

        public InvalidSnapshotException(String message) {
            super(message);
        }

        public InvalidSnapshotException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
