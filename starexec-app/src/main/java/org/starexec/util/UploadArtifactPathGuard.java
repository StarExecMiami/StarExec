package org.starexec.util;

import org.starexec.constants.R;
import org.starexec.data.to.UploadArtifact;
import org.starexec.data.to.UploadSession;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Validates upload artifact paths before cleanup deletes any filesystem object.
 */
public class UploadArtifactPathGuard {
    private final Path benchmarkRoot;

    public UploadArtifactPathGuard() throws IOException {
        this(Paths.get(R.getBenchmarkPath()));
    }

    public UploadArtifactPathGuard(Path benchmarkRoot) throws IOException {
        this.benchmarkRoot = benchmarkRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    public Path getBenchmarkRoot() {
        return benchmarkRoot;
    }

    /**
     * Validates that a DB-provided upload artifact path is under the benchmark
     * root and does not include symlink components below that root.
     *
     * @param rawPath untrusted path from the database
     * @return normalized candidate path
     * @throws IOException if the path is unsafe or cannot be validated
     */
    public Path validate(String rawPath) throws IOException {
        Path candidate = validateUnderBenchmarkRoot(rawPath);
        validateUploadStructure(candidate);
        return candidate;
    }

    public Path validateArtifact(UploadArtifact artifact) throws IOException {
        if (artifact == null) {
            throw new IOException("upload artifact is missing");
        }

        if ("SOURCE_ARCHIVE".equals(artifact.getArtifactRole()) && "FILE".equals(artifact.getPathKind())) {
            return validateSourceArchivePath(artifact.getPath(), null);
        }

        if ("DIRECTORY".equals(artifact.getPathKind()) && artifact.getJobId() != null) {
            return validateExtractionDirectoryPath(artifact.getPath(), artifact.getJobId(), null);
        }

        return validate(artifact.getPath());
    }

    public Path validateSourceArchivePath(String rawPath, Integer userId) throws IOException {
        Path candidate = validateUnderBenchmarkRoot(rawPath);
        Path relative = benchmarkRoot.relativize(candidate);
        validateUserDatePrefix(relative, userId);

        int count = relative.getNameCount();
        if (count == 3) {
            validateSourceArchiveFileName(relative.getName(2).toString(), candidate);
            return candidate;
        }
        if (count == 4 && relative.getName(2).toString().startsWith("upload-session-")) {
            validateSourceArchiveFileName(relative.getName(3).toString(), candidate);
            return candidate;
        }
        throw new IOException("source archive path is not bound to an upload location: " + candidate);
    }

    public Path validateExtractionDirectoryPath(String rawPath, long jobId, Integer userId) throws IOException {
        Path candidate = validateUnderBenchmarkRoot(rawPath);
        Path relative = benchmarkRoot.relativize(candidate);
        validateUserDatePrefix(relative, userId);

        int count = relative.getNameCount();
        String directoryName;
        if (count == 3) {
            directoryName = relative.getName(2).toString();
        } else if (count == 4 && relative.getName(2).toString().startsWith("upload-session-")) {
            directoryName = relative.getName(3).toString();
        } else {
            throw new IOException("extraction directory is not bound to an upload location: " + candidate);
        }

        String expectedPrefix = "upload_" + jobId + "_";
        if (!directoryName.startsWith(expectedPrefix)) {
            throw new IOException("extraction directory is not bound to upload job " + jobId + ": " + candidate);
        }
        if (directoryName.contains("/") || directoryName.contains("\\")) {
            throw new IOException("extraction directory name is invalid: " + candidate);
        }
        return candidate;
    }

    public Path validateTemporaryExtractionDirectory(String rawPath) throws IOException {
        Path candidate = validateUnderBenchmarkRoot(rawPath);
        Path relative = benchmarkRoot.relativize(candidate);
        validateUserDatePrefix(relative, null);

        int count = relative.getNameCount();
        String directoryName;
        if (count == 3) {
            directoryName = relative.getName(2).toString();
        } else if (count == 4 && relative.getName(2).toString().startsWith("upload-session-")) {
            directoryName = relative.getName(3).toString();
        } else {
            throw new IOException("temporary extraction directory is not upload-owned: " + candidate);
        }
        if (!directoryName.startsWith("upload_") || !directoryName.endsWith(".extracting")) {
            throw new IOException("temporary extraction directory is not upload-owned: " + candidate);
        }
        return candidate;
    }

    public Path validateUploadSessionStagingPath(UploadSession session) throws IOException {
        Path stagingPath = validateSessionBasePath(session);
        String fileName = stagingPath.getFileName().toString();
        String cleanFileName = sanitizeFileName(session.getFileName());
        if (!fileName.equals(cleanFileName + ".part") && !fileName.equals(cleanFileName)) {
            throw new IOException("session staging path does not match session file name: " + stagingPath);
        }
        return stagingPath;
    }

    public Path validateUploadSessionChunksDirectory(UploadSession session, String rawPath) throws IOException {
        Path stagingPath = validateUploadSessionStagingPath(session);
        return validateExactSessionPath(rawPath, stagingPath.resolveSibling(stagingPath.getFileName() + ".chunks"));
    }

    public Path validateUploadSessionAssemblingFile(UploadSession session) throws IOException {
        Path stagingPath = validateUploadSessionStagingPath(session);
        return validateExactSessionPath(
            session.getStagingPath() + ".assembling",
            stagingPath.resolveSibling(stagingPath.getFileName() + ".assembling")
        );
    }

    public Path validateUploadSessionFinalArchive(UploadSession session, String rawPath) throws IOException {
        Path stagingPath = validateUploadSessionStagingPath(session);
        Path expected = stagingPath.resolveSibling(sanitizeFileName(session.getFileName()));
        return validateExactSessionPath(rawPath, expected);
    }

    public Path validateUploadSessionChunkFile(UploadSession session, int chunkIndex, String rawPath, boolean temporary)
        throws IOException {
        Path chunksDirectory = validateUploadSessionChunksDirectory(session, session.getStagingPath() + ".chunks");
        Path candidate = validateUnderBenchmarkRoot(rawPath);
        if (!chunksDirectory.equals(candidate.getParent())) {
            throw new IOException("chunk path is outside this upload session chunk directory: " + candidate);
        }

        String fileName = candidate.getFileName().toString();
        String finalName = "chunk_" + chunkIndex + ".bin";
        if (!temporary) {
            if (!finalName.equals(fileName)) {
                throw new IOException("chunk path does not match expected chunk name: " + candidate);
            }
            return candidate;
        }

        String prefix = "chunk_" + chunkIndex + ".";
        if (!fileName.startsWith(prefix) || !fileName.endsWith(".tmp")) {
            throw new IOException("temporary chunk path does not match expected chunk name: " + candidate);
        }
        String uuidText = fileName.substring(prefix.length(), fileName.length() - ".tmp".length());
        try {
            UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            throw new IOException("temporary chunk path does not include a valid random token: " + candidate, e);
        }
        return candidate;
    }

    private Path validateUnderBenchmarkRoot(String rawPath) throws IOException {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new IOException("upload artifact path is empty");
        }

        Path candidate = Paths.get(rawPath).toAbsolutePath().normalize();
        if (!candidate.startsWith(benchmarkRoot)) {
            throw new IOException("upload artifact path is outside benchmark root: " + rawPath);
        }

        validateNoSymlinkComponents(candidate);
        return candidate;
    }

    private void validateNoSymlinkComponents(Path candidate) throws IOException {
        Path current = benchmarkRoot;
        Path relative = benchmarkRoot.relativize(candidate);
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("upload artifact path contains symlink component: " + current);
            }
        }
    }

    private void validateUploadStructure(Path candidate) throws IOException {
        boolean hasUploadSessionComponent = false;
        boolean hasUploadTempComponent = false;
        for (Path component : benchmarkRoot.relativize(candidate)) {
            String name = component.toString();
            if (name.startsWith("upload-session-")) {
                hasUploadSessionComponent = true;
            }
            if (name.startsWith("upload_") || name.endsWith(".chunks") || name.endsWith(".assembling")) {
                hasUploadTempComponent = true;
            }
        }

        if (!hasUploadSessionComponent && !hasUploadTempComponent) {
            throw new IOException("path is not an upload-owned artifact: " + candidate);
        }
    }

    private void validateUserDatePrefix(Path relative, Integer userId) throws IOException {
        if (relative.getNameCount() < 3) {
            throw new IOException("upload path is missing user/date components: " + relative);
        }
        String actualUser = relative.getName(0).toString();
        if (userId != null && !actualUser.equals(String.valueOf(userId))) {
            throw new IOException("upload path is not owned by user " + userId + ": " + relative);
        }
        if (userId == null && !actualUser.matches("\\d+")) {
            throw new IOException("upload path user component is invalid: " + relative);
        }
        String date = relative.getName(1).toString();
        if (!date.matches("\\d{8}")) {
            throw new IOException("upload path date component is invalid: " + relative);
        }
        try {
            LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception e) {
            throw new IOException("upload path date component is invalid: " + relative, e);
        }
    }

    private void validateSourceArchiveFileName(String fileName, Path candidate) throws IOException {
        if (fileName.isEmpty()) {
            throw new IOException("source archive file name is not valid: " + candidate);
        }
    }

    private Path validateSessionBasePath(UploadSession session) throws IOException {
        if (session == null) {
            throw new IOException("upload session is missing");
        }
        Path stagingPath = validateUnderBenchmarkRoot(session.getStagingPath());
        Path relative = benchmarkRoot.relativize(stagingPath);
        validateUserDatePrefix(relative, session.getUserId());
        if (relative.getNameCount() != 4 || !relative.getName(2).toString().startsWith("upload-session-")) {
            throw new IOException("session path is not bound to an upload session directory: " + stagingPath);
        }
        return stagingPath;
    }

    private Path validateExactSessionPath(String rawPath, Path expected) throws IOException {
        Path candidate = validateUnderBenchmarkRoot(rawPath);
        if (!candidate.equals(expected.toAbsolutePath().normalize())) {
            throw new IOException("upload session path does not match expected artifact path: " + candidate);
        }
        return candidate;
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return "upload.zip";
        }
        String clean = new File(fileName).getName().trim();
        if (clean.isEmpty()) {
            return "upload.zip";
        }
        return clean.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
