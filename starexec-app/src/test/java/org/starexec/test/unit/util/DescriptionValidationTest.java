package org.starexec.test.unit.util;

import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.constants.DB;
import org.starexec.util.Validator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DescriptionValidationTest {

    @BeforeClass
    public static void initializeValidator() {
        Validator.initialize();
    }

    @Test
    public void acceptsCommonScientificDescriptionPunctuation() {
        assertTrue(Validator.isValidPrimDescription("C++"));
        assertTrue(Validator.isValidPrimDescription("2017-05-22"));
        assertTrue(Validator.isValidPrimDescription("https://my-tool.example.com"));
    }

    @Test
    public void preservesLegacySafetyAndBoundaryBehavior() {
        assertTrue(Validator.isValidPrimDescription(""));
        assertTrue(Validator.isValidPrimDescription("line one\nline two"));
        assertTrue(Validator.isValidPrimDescription("Gödel–Löb"));
        assertTrue(Validator.isValidPrimDescription("C:\\solver"));
        assertTrue(Validator.isValidPrimDescription("a".repeat(DB.SPACE_DESC_LEN)));

        assertFalse(Validator.isValidPrimDescription(null));
        assertFalse(Validator.isValidPrimDescription("a".repeat(DB.SPACE_DESC_LEN + 1)));
        for (char forbidden : new char[]{'<', '>', '"', '\'', '%', ';', ')', '(', '&'}) {
            assertFalse(
                "Expected description containing '" + forbidden + "' to be rejected",
                Validator.isValidPrimDescription("before" + forbidden + "after")
            );
        }
    }
}
