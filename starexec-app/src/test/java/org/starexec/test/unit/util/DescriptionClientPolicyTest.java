package org.starexec.test.unit.util;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

public class DescriptionClientPolicyTest {

    private static final Path MASTER_JS = Path.of("src/main/webapp/js/master.js");
    private static final Path EDIT_SPACE_JS = Path.of("src/main/webapp/js/edit/space.js");

    @Test
    public void clientDescriptionValidationMatchesTheServerPolicy() throws Exception {
        String masterJavaScript = Files.readString(MASTER_JS);
        assertTrue(
            "Expected the client description pattern to allow plus and hyphen while preserving the 1024-character limit",
            masterJavaScript.contains("return \"^[^<>\\\"\\\'%;)(&]{0,1024}$\";")
        );

        String editSpaceJavaScript = Files.readString(EDIT_SPACE_JS);
        assertTrue(
            "Expected full space editing to apply the shared client description validator",
            Pattern.compile("(?m)^\\s*regex\\s*:\\s*getPrimDescRegex\\(\\)")
                .matcher(editSpaceJavaScript)
                .find()
        );
    }
}
