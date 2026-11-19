package org.starexec.test.unit.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class DescriptionClientPolicyTest {

    private static final Path MASTER_JS = Path.of("src/main/webapp/js/master.js");
    private static final Path EDIT_SPACE_JS = Path.of("src/main/webapp/js/edit/space.js");

    @Test
    public void clientDescriptionValidationMatchesTheServerPolicy() throws Exception {
        String masterJavaScript = Files.readString(MASTER_JS);
        assertTrue(
            masterJavaScript.contains("return \"^[^<>\\\"\\\'%;)(&]{0,1024}$\";"),
            "Expected the client description pattern to allow plus and hyphen while preserving the 1024-character limit"
        );

        String editSpaceJavaScript = Files.readString(EDIT_SPACE_JS);
        assertTrue(
            Pattern.compile("(?m)^\\s*regex\\s*:\\s*getPrimDescRegex\\(\\)")
                .matcher(editSpaceJavaScript)
                .find(),
            "Expected full space editing to apply the shared client description validator"
        );
    }
}
