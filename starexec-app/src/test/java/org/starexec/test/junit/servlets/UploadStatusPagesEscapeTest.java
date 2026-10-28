package org.starexec.test.junit.servlets;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The upload status pages print upload error messages and the names of failed files. Both
 * carry text from the upload itself -- an error now names the benchmark and the include path
 * it could not resolve -- so each must reach the page through {@code <c:out>}, which escapes
 * it, rather than as bare EL, which does not.
 */
public class UploadStatusPagesEscapeTest {

    private static final Path PAGES = Paths.get("src/main/webapp/secure/details");

    /** EL reading a field that carries upload text. */
    private static final Pattern UPLOAD_TEXT =
        Pattern.compile("\\$\\{[^}]*(errorMessage|bench\\.name)[^}]*}");

    /**
     * Where such EL is not printed as text: inside {@code <c:out value="...">}, or in a
     * {@code test} or {@code style} attribute, which only decide whether or how a row shows.
     */
    private static final Pattern SAFE_CONTEXT =
        Pattern.compile("(<c:out value=\"|test=\"|style=\")$");

    private static List<String> unescaped(String page) throws IOException {
        String jsp = new String(Files.readAllBytes(PAGES.resolve(page)), StandardCharsets.UTF_8);
        List<String> found = new ArrayList<>();
        Matcher m = UPLOAD_TEXT.matcher(jsp);
        while (m.find()) {
            String before = jsp.substring(Math.max(0, m.start() - 20), m.start());
            if (!SAFE_CONTEXT.matcher(before).find()) {
                found.add(m.group());
            }
        }
        return found;
    }

    @Test
    public void benchmarkUploadStatusEscapesUploadText() throws IOException {
        assertEquals(new ArrayList<String>(), unescaped("uploadStatus.jsp"));
    }

    @Test
    public void spaceXmlUploadStatusEscapesItsErrorMessage() throws IOException {
        assertEquals(new ArrayList<String>(), unescaped("XMLuploadStatus.jsp"));
    }

    /** Guards the test itself: it must find the fields it is about, or it proves nothing. */
    @Test
    public void thePagesStillPrintTheFieldsThisChecks() throws IOException {
        String jsp = new String(Files.readAllBytes(PAGES.resolve("uploadStatus.jsp")), StandardCharsets.UTF_8);
        assertTrue(jsp.contains("<c:out value=\"${newJob.errorMessage}\"/>"));
        assertTrue(jsp.contains("<c:out value=\"${bench.name}\"/>"));
    }
}
