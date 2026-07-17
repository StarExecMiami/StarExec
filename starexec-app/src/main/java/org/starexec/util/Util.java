package org.starexec.util;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import java.awt.*;
import java.io.*;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.starexec.constants.R;
import org.starexec.exceptions.StarExecException;
import org.starexec.logger.StarLogger;

/**
 * This class contains utility functions used throughout Starexec, including
 * many
 * for executing commands and interacting with the filesystem.
 *
 * @author Eric, and others who hate git
 */
public class Util {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern UNSAFE_SHELL_META_CHARS = Pattern.compile(
        "[;|&><`\\r\\n]"
    );
    private static final Pattern SUBSHELL_PATTERN = Pattern.compile("\\$\\(");
    private static final ExecutorService threadPool = createBoundedExecutor();
    private static final long COMMAND_TIMEOUT_SECONDS = 3600;
    private static final long OUTPUT_COLLECTION_TIMEOUT_SECONDS = 60;
    public static final int CONNECT_TIMEOUT_MS = 60000;
    public static final int READ_TIMEOUT_MS = 600000;
    private static final StarLogger log = StarLogger.getLogger(Util.class);
    private static String docRoot = null;
    private static String docRootUrl = null;

    private static ExecutorService createBoundedExecutor() {
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        int maxThreads = Math.max(cores * 2, 4);
        int queueCapacity = Math.max(maxThreads * 4, 32);
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(
                    r,
                    "starexec-util-" + counter.getAndIncrement()
                );
                thread.setDaemon(true);
                return thread;
            }
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            cores,
            maxThreads,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            factory,
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Gets the current stack trace in the program.
     *
     * @return the current stack trace as a String.
     */
    public static String getCurrentStackTrace() {
        return ExceptionUtils.getStackTrace(new Throwable());
    }

    /**
     * @param c The string color
     * @return The Java color corresponding to the string, or null if no such color
     *         exists
     *         Obtained at
     *         (http://stackoverflow.com/questions/2854043/converting-a-string-to-color-in-java)
     */
    public static Color getColorFromString(String c) {
        Color color;
        try {
            Field field = Class.forName("java.awt.Color").getField(c);
            color = (Color) field.get(null);
        } catch (Exception e) {
            color = null; // Not defined
        }
        return color;
    }

    public static <T extends Enum<T>> boolean isLegalEnumValue(
        String value,
        Class<T> clazz
    ) {
        return EnumSet.allOf(clazz)
            .stream()
            .anyMatch(x -> x.toString().equals(value));
    }

    /**
     * Gives back a String that is the contents of the first n lines of the file
     * where n always less
     * than or equal to lineLimit
     *
     * @param f         The file to read
     * @param lineLimit The maximum number of lines to read (anything less than 0
     *                  indicates no limit)
     * @return The contents of the file as a String (null if it could not be found)
     */
    public static Optional<String> readFileLimited(File f, int lineLimit)
        throws IOException {
        final String methodName = "readFileLimited";
        log.debug(methodName, "calling readFileLimited");
        try {
            // Set limit to max if it's less than 0 (anything less than 0 inclusive
            // indicates no limit)
            lineLimit = Math.min(lineLimit, Integer.MAX_VALUE);

            // If we found the correct std out file...
            if (f.exists()) {
                // Create a buffer to store the lines in and an iterator to iterate over the
                // lines
                StringBuilder sb = new StringBuilder();
                int i = 0;

                // Use try-with-resources to ensure the LineIterator is closed without using
                // deprecated APIs
                try (
                    LineIterator lineItr = FileUtils.lineIterator(
                        f,
                        java.nio.charset.StandardCharsets.UTF_8.name()
                    )
                ) {
                    // While there are more lines in the file...
                    while (lineItr.hasNext()) {
                        // If we've reached the line limit, break out, we're done.
                        if (i++ == lineLimit) {
                            break;
                        }

                        // If we're still under the limit, add the line to the buffer
                        sb.append(lineItr.nextLine());

                        // Don't forget to add a new line, since they are stripped as they are read
                        sb.append("\n");
                    }
                }

                // Return the buffer
                return Optional.of(sb.toString());
            } else {
                // If the file doesn't exist...
                log.warn(
                    methodName,
                    "Could not find file to open: " + f.getAbsolutePath()
                );
                return Optional.empty();
            }
        } catch (IOException e) {
            log.error(
                methodName,
                "Caught IOException with inputs: " +
                    "\n\tFile f: " +
                    f.getAbsolutePath() +
                    "\n\tint lineLimit: " +
                    lineLimit
            );
            throw e;
        }
    }

    public static String getTime() {
        return new Timestamp(System.currentTimeMillis()).toString();
    }

    /**
     * execute the following Runnable using a thread from our cached thread pool
     *
     * @param c the Runnable to execute
     * @author Aaron Stump
     */
    public static void threadPoolExecute(Runnable c) {
        threadPool.execute(c);
    }

    /**
     * Shuts down the reserved threadpool this util uses.
     *
     * @throws Exception if termination of the thread pool is interrupted for taking
     *                   longer than 2 seconds
     */
    public static void shutdownThreadPool() throws Exception {
        threadPool.shutdown();
        threadPool.awaitTermination(2, TimeUnit.SECONDS);
    }

    /**
     * Returns a File object representing the sandbox directory for the headnode
     *
     * @return the File object
     * @author Eric Burns
     */
    public static File getSandboxDirectory() {
        return new File(R.SANDBOX_DIRECTORY);
    }

    /**
     * Ensures a number is within a given range
     *
     * @param min   The minimum value the given value can be
     * @param max   The maximum value the given value can be
     * @param value The actual value to clamp
     * @return min if value is less than min, max if value is
     *         greater than max, or value if it is between min and max
     */
    public static int clamp(int min, int max, int value) {
        return Math.max(Math.min(value, max), min);
    }

    /**
     * Ensures a number is within a given range
     *
     * @param min   The minimum value the given value can be
     * @param max   The maximum value the given value can be
     * @param value The actual value to clamp
     * @return min if value is less than min, max if value is
     *         greater than max, or value if it is between min and max
     */
    public static long clamp(long min, long max, long value) {
        return Math.max(Math.min(value, max), min);
    }

    /**
     * Initializes Starexec data directories by creating them if they
     * do not exist
     */
    public static void initializeDataDirectories() {
        File file = new File(R.STAREXEC_DATA_DIR);
        file.mkdir();

        file = new File(R.getJobInboxDir());
        file.mkdir();
        file = new File(R.JOB_LOG_DIRECTORY);
        file.mkdir();
        file = new File(R.getBenchmarkPath());
        file.mkdir();
        file = new File(R.getSolverPath());
        file.mkdir();
        file = new File(R.getSolverBuildOutputDir());
        file.mkdir();
        file = new File(R.getProcessorDir());
        file.mkdir();
        file = new File(R.JOB_OUTPUT_DIRECTORY);
        file.mkdir();
        file = new File(R.getPicturePath());
        file.mkdir();
        file = new File(R.JOB_SOLVER_CACHE_CLEAR_LOG_DIRECTORY);
        file.mkdir();
        File downloadDir = new File(R.STAREXEC_ROOT, R.DOWNLOAD_FILE_DIR);
        downloadDir.mkdirs();
        File graphDir = new File(R.STAREXEC_ROOT, R.JOBGRAPH_FILE_DIR);
        graphDir.mkdirs();
    }

    /**
     * Extracts the file extension from a file path
     *
     * @param s The file path
     * @return The extension of the file
     */
    public static String getFileExtension(String s) {
        return s.substring(s.lastIndexOf('.') + 1);
    }

    /**
     * @param name
     * @param request
     * @return True if the value of the param given by name is not null in the given
     *         request
     */
    public static boolean paramExists(String name, HttpServletRequest request) {
        return !isNullOrEmpty(request.getParameter(name));
    }

    /**
     * @param s
     * @return True if s is null or empty and false otherwise
     */
    public static boolean isNullOrEmpty(String s) {
        return (isNull(s) || s.trim().isEmpty());
    }

    /**
     * Generates a temporary password of between 6-20 characters, with at least 4
     * letters,
     * 1 number, and 1 special character
     * character
     *
     * @return a temporary password
     */
    public static String getTempPassword() {
        SecureRandom r = SECURE_RANDOM;

        // Random temp password length between 6-20 characters
        int newPassLength = r.nextInt(15) + 6;
        int set = 0;
        String[] charSets = {
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
            "0123456789",
            "`~!@#$%^&*()_+-=",
        };
        StringBuilder sb = new StringBuilder();

        // Hash to store which character sets have been used
        HashSet<Integer> setsUsed = new HashSet<>();

        while (sb.length() != newPassLength) {
            // Choose a random character set to use & get a random character from it
            set = r.nextInt(charSets.length);
            setsUsed.add(set);
            sb.append(charSets[set].charAt(r.nextInt(charSets[set].length())));

            // By the end, if the temporary password doesn't contain a character
            // from all character sets, restart and generate a new temporary password
            if (sb.length() == newPassLength && setsUsed.size() != 3) {
                sb.delete(0, sb.length());
                setsUsed.clear();
            }
        }
        return sb.toString();
    }

    /**
     * Parses a multipart request and returns a hashmap of form parameters
     *
     * @param request The request to parse
     * @return A hashmap containing the field name to field value mapping
     * @throws Exception If the request is malformed
     */
    public static HashMap<String, Object> parseMultipartRequest(
        HttpServletRequest request
    ) throws Exception {
        long t0 = System.currentTimeMillis();
        log.debug("parseMultipartRequest: begin parsing multipart request");
        HashMap<String, Object> form = new HashMap<>();
        Collection<Part> parts;
        try {
            log.debug("parseMultipartRequest: calling request.getParts()...");
            parts = request.getParts();
            log.debug(
                "parseMultipartRequest: request.getParts() returned " +
                    (parts == null ? "null" : parts.size() + " parts") +
                    " in " +
                    (System.currentTimeMillis() - t0) +
                    " ms"
            );
        } catch (Throwable ex) {
            log.error(
                "parseMultipartRequest: exception obtaining parts after " +
                    (System.currentTimeMillis() - t0) +
                    " ms",
                ex
            );
            throw ex instanceof Exception ? (Exception) ex : new Exception(ex);
        }
        if (parts == null) {
            log.warn("parseMultipartRequest: parts collection is null");
            return form;
        }
        int idx = 0;
        for (Part p : parts) {
            long ps = p.getSize();
            String pn = p.getName();
            String ct = p.getContentType();
            log.debug(
                "parseMultipartRequest: processing part #" +
                    idx +
                    " name=" +
                    pn +
                    " size=" +
                    ps +
                    " contentType=" +
                    ct
            );
            idx++;
            PartWrapper wrapper = new PartWrapper(p);
            if (wrapper.isFile()) {
                form.put(pn, wrapper);
            } else {
                try (InputStream is = p.getInputStream()) {
                    form.put(
                        pn,
                        new String(is.readAllBytes(), StandardCharsets.UTF_8)
                    );
                }
            }
        }
        log.debug(
            "parseMultipartRequest: completed parsing " +
                form.size() +
                " fields in " +
                (System.currentTimeMillis() - t0) +
                " ms"
        );
        return form;
    }

    /**
     * Calls executeCommand with a size 1 String[].
     *
     * @deprecated Passing unsanitized shell strings creates command-injection
     *             risks. Prefer
     *             tokenized overloads that do not invoke a shell.
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    public static String executeCommand(String command) throws IOException {
        ensureShellCommandIsSafe(command);
        // Use a system shell so that whitespace-separated commands like
        // "ls -l -R /path" are interpreted correctly. The previous implementation
        // wrapped the entire string as a single executable name, causing
        // java.io.IOException: Cannot run program "ls -l -R /path": error=2.
        String os = System.getProperty("os.name").toLowerCase();
        String[] cmd;
        if (os.contains("win")) {
            cmd = new String[] { "cmd.exe", "/c", command };
        } else {
            cmd = new String[] { "/bin/sh", "-c", command };
        }
        return executeCommand(cmd, null, null);
    }

    /**
     * Calls executeCommand with a size 1 String[] and a null working directory.
     *
     * @deprecated Passing unsanitized shell strings creates command-injection
     *             risks. Prefer
     *             tokenized overloads that do not invoke a shell.
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    public static String executeCommand(String command, String[] env)
        throws IOException {
        ensureShellCommandIsSafe(command);
        String os = System.getProperty("os.name").toLowerCase();
        String[] cmd;
        if (os.contains("win")) {
            cmd = new String[] { "cmd.exe", "/c", command };
        } else {
            cmd = new String[] { "/bin/sh", "-c", command };
        }
        return executeCommand(cmd, env, null);
    }

    /**
     * Calls executeCommand with a null environment and working directory
     *
     * @param command
     * @return See full executeCommand documentation
     * @throws IOException
     */
    public static String executeCommand(String[] command) throws IOException {
        return executeCommand(command, null, null);
    }

    /**
     * Calls executeSandboxCommand with a null environment and working directory
     *
     * @param command
     * @throws IOException
     */
    public static void executeSandboxCommand(String[] command)
        throws IOException {
        executeSandboxCommand(command, null, null);
    }

    // Cached result of sudo availability check (null = not yet tested)
    private static volatile Boolean sudoAvailable = null;
    private static final Object SUDO_CHECK_LOCK = new Object();

    /**
     * Checks if sudo command is actually functional on the system.
     *
     * This method goes beyond checking if sudo is installed - it actually tests
     * whether sudo can successfully execute a command as the sandbox user.
     * This handles containerized environments where sudo may be installed but
     * disabled due to security restrictions like the "no_new_privs" flag.
     *
     * The result is cached since the container's privilege mode won't change
     * at runtime.
     *
     * @return true if sudo is functional and can execute commands as sandbox user,
     *         false otherwise
     */
    public static boolean isSudoAvailable() {
        // Return cached result if available
        if (sudoAvailable != null) {
            return sudoAvailable;
        }

        synchronized (SUDO_CHECK_LOCK) {
            // Double-check after acquiring lock
            if (sudoAvailable != null) {
                return sudoAvailable;
            }

            log.debug(
                "isSudoAvailable",
                "Testing if sudo is functional on the system."
            );

            // First check if sudo binary exists
            try {
                ProcessBuilder whichPb = new ProcessBuilder("which", "sudo");
                Process whichProcess = whichPb.start();
                whichProcess.waitFor();
                if (whichProcess.exitValue() != 0) {
                    log.info(
                        "isSudoAvailable",
                        "sudo binary not found on system - will execute commands directly."
                    );
                    sudoAvailable = false;
                    return false;
                }
            } catch (Exception e) {
                log.info(
                    "isSudoAvailable",
                    "Failed to check for sudo binary: " + e.getMessage()
                );
                sudoAvailable = false;
                return false;
            }

            // Actually test sudo functionality by running a harmless command
            // The -n flag means non-interactive (don't prompt for password)
            // 'true' is a command that does nothing and exits with code 0
            try {
                ProcessBuilder testPb = new ProcessBuilder(
                    "sudo",
                    "-n",
                    "-u",
                    R.SANDBOX_USER_ONE,
                    "true"
                );
                testPb.redirectErrorStream(true);
                Process testProcess = testPb.start();

                // Read any output to prevent blocking
                try (
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                            testProcess.getInputStream(),
                            StandardCharsets.UTF_8
                        )
                    )
                ) {
                    String line;
                    StringBuilder output = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append(" ");
                    }
                    if (output.length() > 0) {
                        log.debug(
                            "isSudoAvailable",
                            "sudo test output: " + output.toString().trim()
                        );
                    }
                }

                boolean finished = testProcess.waitFor(5, TimeUnit.SECONDS);
                if (!finished) {
                    testProcess.destroyForcibly();
                    log.info(
                        "isSudoAvailable",
                        "sudo test timed out - will execute commands directly."
                    );
                    sudoAvailable = false;
                    return false;
                }

                int exitCode = testProcess.exitValue();
                if (exitCode == 0) {
                    log.info(
                        "isSudoAvailable",
                        "sudo is functional - will use sudo for sandbox commands."
                    );
                    sudoAvailable = true;
                    return true;
                } else {
                    log.info(
                        "isSudoAvailable",
                        "sudo test failed with exit code " +
                            exitCode +
                            " (may be due to no_new_privs flag in container) - will execute commands directly."
                    );
                    sudoAvailable = false;
                    return false;
                }
            } catch (Exception e) {
                log.info(
                    "isSudoAvailable",
                    "sudo functionality test failed: " +
                        e.getMessage() +
                        " - will execute commands directly."
                );
                sudoAvailable = false;
                return false;
            }
        }
    }

    /**
     * Resets the cached sudo availability result.
     * This is primarily useful for testing purposes.
     */
    static void resetSudoAvailableCache() {
        synchronized (SUDO_CHECK_LOCK) {
            sudoAvailable = null;
        }
    }

    /**
     * Executes a command as the sandbox user using sudo (if available) (if
     * available)
     *
     * @param command          The command to execute, tokenized
     * @param envp             Environment variables for the command
     * @param workingDirectory Directory to use as the command working directory
     * @return The combined stdout and stderr from the command
     * @throws IOException
     */
    public static String executeSandboxCommand(
        String[] command,
        String[] envp,
        File workingDirectory
    ) throws IOException {
        // Check if sudo is available - if not (e.g., in Docker container), execute
        // command directly
        if (isSudoAvailable()) {
            log.debug(
                "sudo is available, executing command with sudo: " +
                    java.util.Arrays.toString(command)
            );
            String[] newCommand = new String[command.length + 3];
            newCommand[0] = "sudo";
            newCommand[1] = "-u";
            newCommand[2] = R.SANDBOX_USER_ONE;
            System.arraycopy(command, 0, newCommand, 3, command.length);
            return executeCommand(newCommand, envp, workingDirectory);
        } else {
            // In containerized environments, execute command directly
            log.debug(
                "sudo not available, executing command directly: " +
                    java.util.Arrays.toString(command)
            );
            return executeCommand(command, envp, workingDirectory);
        }
    }

    /**
     * Runs a command on the system command line (bash for unix, command line for
     * windows)
     * and returns the process representing the command
     *
     * @param command          An array holding the command and then its arguments
     * @param envp             The environment
     * @param workingDirectory the working directory to use
     * @return A process containing both stderr and stdout from the command
     * @throws IOException We do not want to catch exceptions at this level, because
     *                     this code is generic and
     *                     has no useful way to handle them! Throwing an exception
     *                     to higher levels is the desired behavior.
     */
    public static Process executeCommandAndReturnProcess(
        String[] command,
        String[] envp,
        File workingDirectory
    ) throws IOException {
        ProcessBuilder pb = buildProcess(command);
        if (workingDirectory != null) {
            pb.directory(workingDirectory);
        }
        applyEnvironmentOverrides(pb, envp);
        return pb.start();
    }

    /**
     * Runs a command on the system command line (bash for unix, command line for
     * windows)
     * and returns the results from the command as a string
     *
     * @param command          An array holding the command and then its arguments
     * @param envp             The environment
     * @param workingDirectory the working directory to use
     * @return A String containing both stderr and stdout from the command
     * @throws IOException We do not want to catch exceptions at this level, because
     *                     this code is generic and
     *                     has no useful way to handle them! Throwing an exception
     *                     to higher levels is the desired behavior.
     */

    public static String executeCommand(
        String[] command,
        String[] envp,
        File workingDirectory
    ) throws IOException {
        ProcessBuilder pb = buildProcess(command);
        if (workingDirectory != null) {
            pb.directory(workingDirectory);
        }
        applyEnvironmentOverrides(pb, envp);

        Process process = pb.start();
        Future<String> stdoutFuture = threadPool.submit(() ->
            readStream(process.getInputStream())
        );
        Future<String> stderrFuture = threadPool.submit(() ->
            readStream(process.getErrorStream())
        );
        boolean finished;
        try {
            finished = process.waitFor(
                COMMAND_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelFuture(stdoutFuture);
            cancelFuture(stderrFuture);
            throw new IOException(
                "Interrupted while waiting for command completion",
                e
            );
        }
        if (!finished) {
            process.destroyForcibly();
            cancelFuture(stdoutFuture);
            cancelFuture(stderrFuture);
            throw new IOException(
                "Command timed out after " +
                    COMMAND_TIMEOUT_SECONDS +
                    " seconds: " +
                    Arrays.toString(command)
            );
        }

        String stdout = collectProcessOutput(stdoutFuture, "stdout");
        String stderr = collectProcessOutput(stderrFuture, "stderr");
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException(
                "Command failed with exit code " +
                    exitCode +
                    ": " +
                    Arrays.toString(command) +
                    System.lineSeparator() +
                    "STDOUT:" +
                    System.lineSeparator() +
                    stdout +
                    System.lineSeparator() +
                    "STDERR:" +
                    System.lineSeparator() +
                    stderr
            );
        }
        return stdout;
    }

    private static String collectProcessOutput(
        Future<String> outputFuture,
        String streamName
    ) throws IOException {
        try {
            return outputFuture.get(
                OUTPUT_COLLECTION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(
                "Interrupted while collecting " + streamName + " output",
                e
            );
        } catch (ExecutionException e) {
            throw new IOException(
                "Failed to read command " + streamName + " output",
                e.getCause()
            );
        } catch (TimeoutException e) {
            throw new IOException(
                "Timed out collecting command " + streamName + " output",
                e
            );
        }
    }

    private static String readStream(InputStream inputStream)
        throws IOException {
        try (
            InputStream in = inputStream;
            InputStreamReader isr = new InputStreamReader(
                in,
                StandardCharsets.UTF_8
            );
            BufferedReader reader = new BufferedReader(isr)
        ) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append(System.lineSeparator());
            }
            return sb.toString();
        }
    }

    private static void cancelFuture(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private static void applyEnvironmentOverrides(
        ProcessBuilder pb,
        String[] envp
    ) {
        if (envp == null) {
            return;
        }
        Map<String, String> env = pb.environment();
        for (String entry : envp) {
            if (entry == null) {
                continue;
            }
            int idx = entry.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = entry.substring(0, idx);
            String value = entry.substring(idx + 1);
            env.put(key, value);
        }
    }

    private static void ensureShellCommandIsSafe(String command)
        throws IOException {
        if (isNullOrEmpty(command)) {
            throw new IOException("Command cannot be null or empty");
        }
        if (
            UNSAFE_SHELL_META_CHARS.matcher(command).find() ||
            SUBSHELL_PATTERN.matcher(command).find()
        ) {
            log.warn("Rejected unsafe shell command: " + command);
            throw new IOException(
                "Unsafe shell metacharacters detected in command. Use the tokenized overload instead."
            );
        }
    }

    private static ProcessBuilder buildProcess(String[] command) {
        if (command == null || command.length == 0) {
            throw new IllegalArgumentException("Command cannot be empty");
        }
        if (command.length == 1) {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                return new ProcessBuilder("cmd.exe", "/c", command[0]);
            }
            return new ProcessBuilder("/bin/sh", "-c", command[0]);
        }
        return new ProcessBuilder(command);
    }

    private static boolean isPermissionDenied(IOException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lowered = message.toLowerCase(Locale.ROOT);
        return (
            lowered.contains("operation not permitted") ||
            lowered.contains("permission denied")
        );
    }

    /**
     * drains the given InputStream, adding each line read to the given
     * StringBuffer.
     *
     * @param sb the StringBuffer to which to append lines
     * @param s  the InputStream to drain
     * @return true iff we read a string
     */
    protected static boolean drainInputStream(StringBuffer sb, InputStream s) {
        InputStreamReader ins = new InputStreamReader(s);
        BufferedReader reader = new BufferedReader(ins);

        boolean readsomething = false;
        String line = null;
        try {
            while ((line = reader.readLine()) != null) {
                readsomething = true;
                sb.append(line).append(System.getProperty("line.separator"));
            }
            reader.close();

            // output the InputStream text to the log file (print sb)
            log.debug("The process produced stdout output:\n" + sb);
        } catch (IOException e) {
            log.warn("drainInputStream caught: " + e.toString(), e);
        } finally {
            try {
                reader.close();
            } catch (Exception e) {
                log.warn(
                    "Caught exception closing reader while draining streams."
                );
            }
        }
        return readsomething;
    }

    /*
     * This method gets the stdout from a process. If there was stderr output, then
     * something bad happened as a result of running something, and an exception
     * is thrown to the caller.
     *
     * @param p the process
     *
     * @return
     */
    public static String getstdout(final Process p) throws StarExecException {
        final StringBuffer message = new StringBuffer();
        // if we got an error from stderr, we throw our custom exception
        if (drainInputStream(message, p.getErrorStream())) {
            throw new StarExecException(message.toString());
        }
        // if nothing was read into the buffer, get the output
        drainInputStream(message, p.getInputStream());
        return message.toString();
    }

    /**
     * Converts a list of strings into a list of ints
     *
     * @param stringList The list of numeric strings to convert to ints
     * @return A list of ints parsed from the string list
     */
    public static List<Integer> toIntegerList(String[] stringList) {
        if (nonNull(stringList)) {
            return Arrays.stream(stringList)
                .map(Integer::parseInt)
                .collect(Collectors.toList());
        }
        return new LinkedList<>();
    }

    /**
     * Normalizes all line endings in the given file to the line ending of the OS
     * the JVM is running on
     *
     * @param f The file to normalize
     */
    public static void normalizeFile(File f) {
        if (!f.exists()) {
            log.warn("Could not find file to open: " + f.getAbsolutePath());
            return;
        }

        Path original = f.toPath();
        Path parent = original.getParent();
        try {
            Path temp = Files.createTempFile(
                parent,
                f.getName(),
                ".normalized"
            );
            try (
                BufferedReader reader = Files.newBufferedReader(
                    original,
                    StandardCharsets.UTF_8
                );
                BufferedWriter writer = Files.newBufferedWriter(
                    temp,
                    StandardCharsets.UTF_8
                )
            ) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                }
            }
            try {
                Files.move(
                    temp,
                    original,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, original, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed to normalize file " + f.getAbsolutePath(), e);
        }
    }

    /**
     * @param nums
     * @return A string containing a comma-separated list of the given numbers
     */
    public static String makeCommaSeparatedList(List<Integer> nums) {
        StringBuilder sb = new StringBuilder();
        nums.forEach(id -> {
            sb.append(id);
            sb.append(",");
        });
        sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    /**
     * Retrieves all files in the given directory that are as old as, or older than
     * the specified number of days
     *
     * @param directory   The directory to clear old files out of (non-recursive)
     * @param daysAgo     Files older than this many days ago will be deleted
     * @param includeDirs Whether to include directories as well as files
     * @return All files older than the given filter
     */
    public static Collection<File> getOldFiles(
        String directory,
        int daysAgo,
        boolean includeDirs
    ) {
        File dir = new File(directory);

        if (!dir.exists()) {
            return null;
        }

        // Subtract days from the current time
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE, -daysAgo);
        // Create a new filter for files older than this new time
        IOFileFilter dateFilter = FileFilterUtils.ageFileFilter(
            calendar.getTime()
        );
        Collection<File> outdatedFiles;
        // Get all of the outdated files
        if (includeDirs) {
            File[] files = dir.listFiles((FileFilter) dateFilter);
            outdatedFiles = Arrays.asList(files);
        } else {
            outdatedFiles = FileUtils.listFiles(dir, dateFilter, null);
        }
        return outdatedFiles;
    }

    /**
     * Deletes all files in the given directory that are as old as, or older than
     * the specified number of days.
     * The given directory itself is NOT deleted
     *
     * @param directory The directory to clear old files out of (non-recursive)
     * @param daysAgo   Files older than this many days ago will be deleted
     */
    public static void clearOldSandboxFiles(String directory, int daysAgo) {
        try {
            Collection<File> outdatedFiles = getOldFiles(
                directory,
                daysAgo,
                true
            );
            log.debug(
                "found a total of " +
                    outdatedFiles.size() +
                    " outdated files to delete in " +
                    directory
            );
            // Remove them all
            for (File f : outdatedFiles) {
                sandboxChmodDirectory(f);
                if (f.isDirectory()) {
                    FileUtils.deleteDirectory(f);
                } else {
                    FileUtils.deleteQuietly(f);
                }
            }
        } catch (Exception e) {
            log.warn(e.getMessage(), e);
        }
    }

    /*
     * THIS IS NOT SAFE TO RUN ON STAREXEC
     * <p>
     * This code is designed to be used for single uses on Stardev to clear the job
     * directory in a smart way after
     * redeploying and resetting the stardev database causes job directories to
     * clear out. This procedure may also
     * be useful on Starexec, but extreme care needs to be taken to make sure the
     * correct directories are deleted.
     * This should not be used on Starexec without going through the code below line
     * by line, as changes in the
     * job output directory since this was written (April 2016) may cause unexpected
     * results.
     * <p>
     * Clears out directories under joboutput that do not belong to any job in the
     * database. These
     * directories are ones that were not cleared correctly.
     */
    /*
     * public static void clearOrphanedJobDirectories() {
     * log.info("calling clearOrphanedJobDirectories");
     * File outputDirectory = new File(R.JOB_OUTPUT_DIRECTORY);
     * // we are going to consider removing all files / directories under the job
     * output directory
     * HashSet<String> filesToConsider = new HashSet<>();
     * for (File f : outputDirectory.listFiles()) {
     * filesToConsider.add(f.getAbsolutePath());
     * }
     * log.info("found this many job output subdirectories to consider " +
     * filesToConsider.size());
     * // exclude the log directory from removal
     * filesToConsider.remove(new File(R.JOB_LOG_DIRECTORY).getAbsolutePath());
     *
     * // exclude the directories of existing jobs from removal. This should be safe
     * from race conditions
     * // because we are getting the list of jobs after getting the list of files.
     * As such, jobs directories
     * // created between these operations will not be present in filesToConsider
     * for (Integer i : Jobs.getAllJobIds()) {
     * filesToConsider.remove(Jobs.getDirectory(i));
     * }
     * log.
     * info("found this many job output subdirectories to consider after filter " +
     * filesToConsider.size());
     *
     * for (String s : filesToConsider) {
     * log.info("deleting the following orphaned job directory");
     * log.info(s);
     * if (!Util.safeDeleteDirectory(s)) {
     * log.error("failed to deleted directory " + s);
     * }
     * }
     * }
     */

    /**
     * Deletes all files in the given directory that are as old as, or older than
     * the specified number of days
     *
     * @param directory   The directory to clear old files out of (non-recursive)
     * @param daysAgo     Files older than this many days ago will be deleted
     * @param includeDirs Whether to delete directories as well as files
     */
    public static void clearOldFiles(
        String directory,
        int daysAgo,
        boolean includeDirs
    ) {
        try {
            Collection<File> outdatedFiles = getOldFiles(
                directory,
                daysAgo,
                includeDirs
            );
            if (outdatedFiles == null) return;
            log.debug(
                "found a total of " +
                    outdatedFiles.size() +
                    " outdated files to delete in " +
                    directory
            );
            // Remove them all
            outdatedFiles.forEach(FileUtils::deleteQuietly);
        } catch (Exception e) {
            log.warn(e.getMessage(), e);
        }
    }

    /**
     * Returns a configuration's absolute file path given the solver's path and
     * the configuration's name
     *
     * @param solverPath the absolute path to the solver's directory
     * @param configName the configuration's name (which is also the filename)
     * @return null if the solver path or configuration's name are null or empty,
     *         otherwise
     *         this returns the absolute path to the given configuration's file on
     *         disk
     * @author Todd Elvers
     */
    public static String getSolverConfigPath(
        String solverPath,
        String configName
    ) {
        if (isNullOrEmpty(solverPath) || isNullOrEmpty(configName)) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(solverPath); // Path = .../solvers/{user_id}/{solver_name}/{unique_timestamp}/
        sb.append(R.SOLVER_BIN_DIR); // Path = .../solvers/{user_id}/{solver_name}/{unique_timestamp}/bin
        sb.append(File.separator); // Path = .../solvers/{user_id}/{solver_name}/{unique_timestamp}/bin/
        // Append 'run_' prefix to the configuration's filename if it isn't already
        // there
        if (!configName.startsWith(R.CONFIGURATION_PREFIX)) {
            sb.append(R.CONFIGURATION_PREFIX);
        }
        sb.append(configName); // Path =
        // .../solvers/{user_id}/{solver_name}/{unique_timestamp}/bin/{starexec_run_configName}
        return sb.toString();
    }

    private static void initDocRoot() {
        if (isNull(docRoot)) {
            docRoot = "/" + R.STAREXEC_APPNAME + "/";
        }
    }

    private static void initDocRootUrl() {
        initDocRoot();
        if (isNull(docRootUrl)) {
            // Include port in URL if it's non-standard
            String port = "";
            boolean isHttp = R.STAREXEC_URL_PREFIX.equalsIgnoreCase("http");
            boolean isHttps = R.STAREXEC_URL_PREFIX.equalsIgnoreCase("https");

            if (
                (isHttp && R.PROXY_PORT != 80) ||
                (isHttps && R.PROXY_PORT != 443)
            ) {
                port = ":" + R.PROXY_PORT;
            }

            docRootUrl =
                R.STAREXEC_URL_PREFIX +
                "://" +
                R.STAREXEC_SERVERNAME +
                port +
                docRoot;
        }
    }

    /**
     * Prepend the document root to the given path, to form a site root-relative
     * path.
     *
     * @param s
     * @return a path to the given document relative to STAREXEC_ROOT
     * @author Aaron Stump
     */
    public static String docRoot(String s) {
        initDocRoot();
        return docRoot + s;
    }

    /**
     * Prepend the "https://", the server name, and the document root, to form an
     * absolute path (URL).
     *
     * @param s The relative path to create a url for
     * @return the absolute url associated with the given relative path
     * @author Aaron Stump
     */
    public static String url(String s) {
        initDocRootUrl();
        return docRootUrl + s;
    }

    /**
     * Build a URL with the correct scheme, server name, port, and path.
     * Includes port in URL if non-standard (not 80 for HTTP, not 443 for HTTPS).
     *
     * @param scheme The URL scheme (http or https)
     * @param host   The hostname
     * @param port   The port number
     * @param path   The path portion of the URL
     * @return Complete URL with scheme://host:port/path format (port omitted if
     *         standard)
     */
    public static String buildUrl(
        String scheme,
        String host,
        int port,
        String path
    ) {
        String portStr = "";
        boolean isHttp = scheme.equalsIgnoreCase("http");
        boolean isHttps = scheme.equalsIgnoreCase("https");

        if ((isHttp && port != 80) || (isHttps && port != 443)) {
            portStr = ":" + port;
        }

        return scheme + "://" + host + portStr + path;
    }

    /**
     * Given an integer number of bytes, returns a human-readable string
     * approximating the number of bytes given with the proper units
     *
     * @param bytes The number of bytes
     * @return The number of bytes to two decimal places in a useful unit
     * @author Eric Burns
     */
    public static String byteCountToDisplaySize(long bytes) {
        final String[] suffix = { "Bytes", "KB", "MB", "GB", "TB", "PB", "EB" };
        int suffixIndex = 0;
        double b = (double) bytes;
        while (b > 1024) {
            suffixIndex += 1;
            b = b / 1024;
        }
        DecimalFormat df = new DecimalFormat("#.##");

        return df.format(b) + " " + suffix[suffixIndex];
    }

    /**
     * Converts gigabytes to bytes.
     *
     * @param gigabytes
     * @return Number of bytes representing the given gigabytes
     */
    public static long gigabytesToBytes(double gigabytes) {
        return (long) (1073741824 * gigabytes);
    }

    /**
     * @param bytes
     * @return Converts bytes to megabytes, truncated to the nearest integer
     *         megabyte
     */
    public static long bytesToMegabytes(long bytes) {
        return (bytes / (1024 * 1024));
    }

    /**
     * @param bytes
     * @return the number of gigabytes representing the given number of bytes
     */
    public static double bytesToGigabytes(long bytes) {
        return ((double) bytes / 1073741824.0);
    }

    /**
     * Deletes the file specified by the given path, and then moves up and deletes
     * empty directories on the path to the the file that was deleted. Will ignore
     * any directories
     * at or above 'endPath'
     *
     * @param path    The path to the file to delete
     * @param endPath The path specifying the directory to terminate at. Will not
     *                delete any directories
     *                at or above this path.
     * @return True on success and false otherwise.
     */
    public static boolean safeDeleteFileAndEmptyParents(
        String path,
        String endPath
    ) {
        log.debug("got call to delete file and empty parents on " + path);
        log.debug("endPath is " + endPath);
        if (!safeDeleteDirectory(path)) {
            return false;
        }
        File file = new File(path);
        File endFile = new File(endPath);
        while (nonNull(file)) {
            file = file.getParentFile();
            if (isNull(file)) {
                break;
            }
            log.debug("working on parent directory " + file.getAbsolutePath());
            if (endFile.getAbsolutePath().equals(file.getAbsolutePath())) {
                log.debug("terminating at endpath");
                break;
            }
            if (file.isDirectory()) {
                if (!file.delete()) {
                    log.debug("terminating at non-empty directory");
                    // if the directory does not get deleted, that just means that it was not empty
                    break;
                }
                log.debug("deleted directory");
            }
        }

        return true;
    }

    /**
     * Attempts to delete the directory or file specified the given path without
     * throwing any errors
     *
     * @param path The path to the directory to delete
     * @return True on success, false otherwise
     * @author Eric Burns
     */
    public static boolean safeDeleteDirectory(String path) {
        File file = new File(path);
        try {
            deletePath(file);
            return true;
        } catch (Exception e) {
            if (isDeletePermissionFailure(e)) {
                log.warn(
                    "safeDeleteDirectory: permission issue deleting " +
                        file.getAbsolutePath() +
                        "; attempting permission repair before retry",
                    e
                );
                try {
                    repairDeletePermissions(file);
                    deletePath(file);
                    return true;
                } catch (Exception retryException) {
                    log.error("safeDeleteDirectory", retryException);
                }
            } else {
                log.error("safeDeleteDirectory", e);
            }
        }
        return false;
    }

    private static void deletePath(File file) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            FileUtils.deleteDirectory(file);
        } else if (!FileUtils.deleteQuietly(file)) {
            throw new IOException("Cannot delete file: " + file.getAbsolutePath());
        }
    }

    private static void repairDeletePermissions(File file) throws IOException {
        if (file == null) {
            return;
        }

        File target = file.isDirectory() ? file : file.getParentFile();
        if (target != null) {
            chmodDirectory(target.getAbsolutePath(), false);
            chmodDirectory(target.getAbsolutePath(), true);
        }

        if (file.exists() && !file.isDirectory()) {
            chmodSinglePath(file, "u+rwx");
            chmodSinglePath(file, "g+rwx");
        }
    }

    private static boolean isDeletePermissionFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AccessDeniedException) {
                return true;
            }
            if (current instanceof IOException ioException && isPermissionDenied(ioException)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Given a list, a comparator, and all of the attributes needed to paginate a
     * DataTables object,
     * returns a sublist of the given list containing the ordered items to display
     *
     * @param <T>     Type of the given list and comparator. Can be any sortable
     *                object type.
     * @param arr     List to sort
     * @param compare Comparator object that will be used to determine the ordering
     *                of objects during sorting
     * @param start   Record to start on
     * @param records Number of records to give back (actual number will be less if
     *                the size of the list is less than
     *                records)
     * @return Entries sorted and filtered according to the given comparator
     */

    public static <T> List<T> handlePagination(
        List<T> arr,
        Comparator<T> compare,
        int start,
        int records
    ) {
        arr.sort(compare);
        List<T> returnList = new ArrayList<>();
        if (start >= arr.size()) {
            // we'll just return nothing
        } else if (start + records > arr.size()) {
            returnList = arr.subList(start, arr.size());
        } else {
            returnList = arr.subList(start, start + records);
        }
        return returnList;
    }

    /**
     * Gets a String representation of a Throwable object's
     * stack trace.
     *
     * @param t a throwable object
     * @return the string representation of t's stack trace.
     * @author Albert Giegerich
     */
    public static String getStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * Grants full permission to the owner of the specified directory.
     * I am adding this function because sandbox is unable to create directories as
     * it unzips currently.
     * In the update from tc7 to tc9, the tmp directory made in the sandbox
     * directory
     * defaulted to r-s permissions for the sandbox group. This function adds write
     * permissions to the sandbox group such that user sandbox can successfully
     * unzip the uploaded archive solver files.
     * -Alexander Brown, 5/9/21
     *
     * @param dir
     * @throws IOException
     */
    public static void sandboxChmodDirectoryDirect(File dir)
        throws IOException {
        if (!dir.isDirectory()) {
            return;
        }

        // // print the permissions before the change
        // log.debug("\n\npermissions before chmod command\n");
        // String[] lsCmd = new String[3];
        // lsCmd[0] = "ls";
        // lsCmd[1] = "-al";
        // lsCmd[2] = dir.toString();
        // Util.executeCommand(lsCmd);
        // // for (File f : dir.listFiles()) {
        // // chmod[5] = f.getAbsolutePath();
        // // Util.executeCommand(chmod);
        // // }

        // make the permissions change to the sandbox group as active user (tomcat)
        String[] chmod = new String[3];
        chmod[0] = "chmod";
        chmod[1] = "g+rws";
        chmod[2] = dir.toString();
        Util.executeCommand(chmod);

        // // print the permissions after the change
        // log.debug("\n\npermissions after chmod command\n");
        // Util.executeCommand(lsCmd);
    }

    /**
     * Recursively grants full permission to the owner of everything in the given
     * directory. The top level directory is not affected, only everything inside
     *
     * @param dir
     * @throws IOException
     */
    public static void sandboxChmodDirectory(File dir) throws IOException {
        if (!dir.isDirectory()) {
            return;
        }

        // give sandbox full permissions over the solver directory
        // Execute chmod as the current user (starexec) who owns the files
        // The sandbox user will later access these files via group permissions
        log.debug("Executing chmod for directory: " + dir.getAbsolutePath());

        File[] files = dir.listFiles();
        if (files == null) {
            log.warn(
                "Cannot list files in directory (permission denied or not a directory): " +
                    dir.getAbsolutePath()
            );
            return;
        }

        String[] chmod = new String[4];
        chmod[0] = "chmod";
        chmod[1] = "-R";
        chmod[2] = "u+rwx,g+rwx";
        for (File f : files) {
            chmod[3] = f.getAbsolutePath();
            try {
                Util.executeCommand(chmod);
            } catch (IOException e) {
                if (isPermissionDenied(e)) {
                    log.info(
                        "Permission denied chmodding as current user, retrying as sandbox for " +
                            f.getAbsolutePath()
                    );
                    runChmodAsSandboxUser(chmod);
                } else {
                    throw e;
                }
            }
        }
    }

    // public static void sandboxChownDirectory(File dir) throws IOException {
    // if (!dir.isDirectory()) {
    // return;
    // }
    // //make owner sandbox
    // String[] chown = new String[7];
    // chown[0] = "sudo";
    // chown[1] = "chown";
    // chown[2] = "-R";
    // chown[3] = "sandbox:sandbox";
    // for (File f : dir.listFiles()) {
    // chown[4] = f.getAbsolutePath();
    // Util.executeCommand(chown);
    // }
    // }

    /**
     * Adds rwx permissions to the directory for either the owner or the group
     *
     * @param dir   The directory to modify
     * @param group True to modify permissions for the group and false for the
     *              directory
     * @throws IOException
     */
    public static void chmodDirectory(String dir, boolean group)
        throws IOException {
        File root = new File(dir);
        if (!root.exists()) {
            log.warn("chmodDirectory", "Path does not exist: " + dir);
            return;
        }
        String permission = group ? "g+rwx" : "u+rwx";
        chmodFileTree(root, permission);
    }

    private static void chmodFileTree(File file, String permission)
        throws IOException {
        if (file == null || !file.exists()) {
            return;
        }
        chmodSinglePath(file, permission);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    chmodFileTree(child, permission);
                }
            }
        }
    }

    private static void chmodSinglePath(File target, String permission)
        throws IOException {
        String[] chmod = new String[3];
        chmod[0] = "chmod";
        chmod[1] = permission;
        chmod[2] = target.getAbsolutePath();
        try {
            Util.executeCommand(chmod);
        } catch (IOException e) {
            if (isPermissionDenied(e)) {
                log.info(
                    "Permission denied chmodding as current user, retrying as sandbox for " +
                        target.getAbsolutePath()
                );
                runChmodAsSandboxUser(chmod);
            } else {
                throw e;
            }
        }
    }

    private static void runChmodAsSandboxUser(String[] chmod) {
        try {
            Util.executeSandboxCommand(chmod);
        } catch (IOException e) {
            log.warn("Failed to run chmod as sandbox user", e);
        }
    }

    /**
     * Copies all of the given files to a single, newly created sandbox directory
     * and returns the sandbox directory. The sandbox user will be the owner and
     * have full permissions over everything in the sandbox directory.
     *
     * @param files
     * @return The sandbox directory that contains all the copied files
     * @throws IOException
     */
    public static File copyFilesToNewSandbox(List<File> files)
        throws IOException {
        File sandbox = null;
        File sandbox2 = null;
        try {
            sandbox = getRandomSandboxDirectory();
            sandbox2 = getRandomSandboxDirectory();
            String[] cpCmd = new String[4];
            cpCmd[0] = "cp";
            cpCmd[1] = "-r";
            cpCmd[3] = sandbox.getAbsolutePath();
            for (File f : files) {
                cpCmd[2] = f.getAbsolutePath();
                Util.executeCommand(cpCmd);
            }

            /* next, copy the files over so they are owned by sandbox */

            // first make sure sandbox2 is group writeable, since we will copy
            // the files when sudo'ed to the sandbox user. The group for sandbox2
            // is set as sandbox (by the system) when the directory is created.
            sandboxChmodDirectoryDirect(sandbox2);

            // now copy as sandbox user. We could also have just chown'ed everything...
            String[] sudoCpCmd = new String[4];

            sudoCpCmd[0] = "cp";
            sudoCpCmd[1] = "-r";
            sudoCpCmd[3] = sandbox2.getAbsolutePath();
            for (File f : sandbox.listFiles()) {
                sudoCpCmd[2] = f.getAbsolutePath();
                Util.executeSandboxCommand(sudoCpCmd);
            }

            // now give full permissions to the sandbox user for contents of sandbox2
            sandboxChmodDirectory(sandbox2);
        } finally {
            FileUtils.deleteQuietly(sandbox);
        }
        return sandbox2;
    }

    /**
     * Creates and returns a unique, empty directory immediately inside
     * of the sandbox directory on the head node
     *
     * @return The sandbox directory that was created
     */
    public static File getRandomSandboxDirectory() {
        File sandboxDirectory = Util.getSandboxDirectory();
        String randomDirectory = Util.getRandomAlphaString(64);

        File sandboxDir = new File(sandboxDirectory, randomDirectory);

        sandboxDir.mkdirs();
        return sandboxDir;
    }

    /**
     * Generates a random alpha string [A-Za-z] of the given length.
     * Used to create unique directory names without depending on test utilities.
     */
    public static String getRandomAlphaString(int length) {
        final String alphabet =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int idx = SECURE_RANDOM.nextInt(alphabet.length());
            sb.append(alphabet.charAt(idx));
        }
        return sb.toString();
    }

    /**
     * Executes ls -l -R on the sandbox directory and logs the results
     */
    public static void logSandboxContents() {
        try {
            log.debug("logging sandbox contents");
            log.debug("PERMISSION CHECK");
            // Using shell execution; command will be run via /bin/sh -c
            log.debug(
                Util.executeCommand(
                    "ls -l -R " + Util.getSandboxDirectory().getAbsolutePath()
                )
            );
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    /**
     * Gets the HTML for a web page as a String with query parameters.
     *
     * @param url             The url to get the page from.
     * @param queryParameters A mapping of query parameters to their values.
     * @param cookiesToSend   Cookies to send with the request
     * @return the web page in string form.
     * @throws IOException if there is some error getting the web pages
     * @author Albert Giegerich
     */
    public static String getWebPage(
        String url,
        Map<String, String> queryParameters,
        List<Cookie> cookiesToSend
    ) throws IOException {
        if (queryParameters.keySet().isEmpty()) {
            return url;
        }

        // Initially contains the ? necessary for the query string.
        StringBuilder queryStringBuilder = new StringBuilder("?");

        queryParameters
            .keySet()
            .forEach(parameter -> {
                String value = queryParameters.get(parameter);
                queryStringBuilder
                    .append(parameter)
                    .append("=")
                    .append(value)
                    .append("&");
            });
        // delete the last & character
        queryStringBuilder.deleteCharAt(queryStringBuilder.length() - 1);

        return getWebPage(url + queryStringBuilder.toString(), cookiesToSend);
    }

    /**
     * Gets the HTML for a web page as a String.
     *
     * @param url           The url to get the page from.
     * @param cookiesToSend The cookies to attach to this request.
     * @return the web page in String form.
     * @throws IOException
     * @author Albert Giegerich
     */

    public static String getWebPage(String url, List<Cookie> cookiesToSend)
        throws IOException {
        String nextLine;
        StringBuilder outputHtml = new StringBuilder();
        URI uri = URI.create(url);
        URL inputUrl = uri.toURL();
        URLConnection urlConnection = inputUrl.openConnection();
        if (nonNull(cookiesToSend)) {
            String cookieString = buildCookieString(cookiesToSend);
            urlConnection.setRequestProperty("Cookie", cookieString);
        }
        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(urlConnection.getInputStream())
            )
        ) {
            while ((nextLine = reader.readLine()) != null) {
                outputHtml.append(nextLine);
            }
        }
        return outputHtml.toString();
    }

    /**
     * Builds a String representing a list of Cookies that we can pass to
     * URLConnection.setRequestProperty to send
     * cookies.
     */
    private static String buildCookieString(List<Cookie> cookies) {
        // StringJoiner cookieStringJoiner = new StringJoiner("; ");
        StringBuilder cookieStringBuilder = new StringBuilder();
        cookies.forEach(cookie ->
            cookieStringBuilder
                .append(cookie.getName())
                .append("=")
                .append(cookie.getValue())
                .append(";")
        );
        if (!cookies.isEmpty()) {
            cookieStringBuilder.deleteCharAt(cookieStringBuilder.length() - 1);
        }
        return cookieStringBuilder.toString();
    }

    /**
     * Attempts to copy the file at the end of the given URL to the given file,
     * using a proxy
     *
     * @param url
     * @param archiveFile
     * @return True on success and false otherwise
     */
    public static boolean copyFileFromURLUsingProxy(URL url, File archiveFile) {
        final String methodName = "copyFileFromURLUsingProxy";
        try {
            Proxy proxy = new Proxy(
                Proxy.Type.HTTP,
                new InetSocketAddress(R.PROXY_ADDRESS, R.PROXY_PORT)
            );
            URLConnection connection = url.openConnection(proxy);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            FileUtils.copyInputStreamToFile(
                connection.getInputStream(),
                archiveFile
            );
            return true;
        } catch (Exception e) {
            log.error(methodName, e.getMessage(), e);
        }
        return false;
    }

    /**
     * Replaces NODE path separator with HEAD path separator
     *
     * @param path to normalize
     * @return normalized path
     */
    public static String normalizeFilePath(String path) {
        return (
            path.replace(R.JOB_PAIR_PATH_DELIMITER, File.separator) +
            File.separator
        );
    }

    /**
     * Try to detect if a file is binary.
     * For example, if a file is an execuatable binary instead of a text shell
     * script, this function will return true
     *
     * @param f file to check
     * @return true if file is binary, false otherwise
     */
    public static boolean isBinaryFile(File f) throws IOException {
        final String[] command = { "file", "-bi", f.getCanonicalPath() };
        return Util.executeCommand(command).contains("charset=binary");
    }

    /**
     * Encodes a cookie value to comply with RFC 6265.
     * Cookie values cannot contain spaces, commas, semicolons, or other special
     * characters.
     * This method URL-encodes the value to ensure compliance.
     *
     * @param value The cookie value to encode
     * @return The URL-encoded value, or empty string if value is null/empty
     */
    public static String encodeCookieValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Creates a cookie with a URL-encoded value to comply with RFC 6265.
     * Cookie values cannot contain spaces, commas, semicolons, or other special
     * characters.
     * The cookie is configured with security best practices:
     * - HttpOnly: prevents XSS access to cookies
     * - Path: scoped to /starexec
     *
     * @param name  The cookie name
     * @param value The cookie value (will be URL-encoded)
     * @return A new Cookie with the encoded value and security attributes
     */
    public static Cookie createEncodedCookie(String name, String value) {
        Cookie cookie = new Cookie(name, encodeCookieValue(value));
        cookie.setHttpOnly(true);
        cookie.setPath("/" + R.STAREXEC_APPNAME);
        // Enable secure flag when running over HTTPS
        if ("https".equalsIgnoreCase(R.STAREXEC_URL_PREFIX)) {
            cookie.setSecure(true);
        }
        return cookie;
    }
}
