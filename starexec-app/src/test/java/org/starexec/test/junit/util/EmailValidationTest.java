package org.starexec.test.junit.util;

import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.constants.R;
import org.starexec.util.Validator;

import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which email addresses registration accepts.
 *
 * <h2>Why this exists as a JUnit test</h2>
 *
 * <p>{@code ValidatorTests} already covers {@code isValidEmail}, but it extends
 * {@code TestSequence} and its methods carry {@code @StarexecTest}, so Surefire finds no
 * runnable method and reports the class as passing without executing anything. It runs only
 * inside a deployed instance through {@code TestManager}. Email validation therefore had no
 * coverage in CI at all, which is how a top-level-domain bound could sit in
 * {@code R.EMAIL_PATTERN} rejecting real addresses without any build noticing.
 *
 * <p>The cases below are duplicated from that class deliberately rather than moved: the
 * in-deployment suite keeps its own copy, and this one runs on every build.
 */
public class EmailValidationTest {

	/**
	 * {@code Validator} compiles its patterns in an explicit {@code initialize()} rather than a
	 * static block, and the application calls it once from {@code Starexec} at context startup.
	 * A unit test has no context, so it makes the same call. The patterns come from compile-time
	 * constants in {@code R}, so nothing here needs configuration or a database.
	 */
	@BeforeClass
	public static void compilePatterns() {
		Validator.initialize();
	}

	// ------------------------------------------------------------ the regression

	/**
	 * The bound was 4, so every address on a long TLD was refused. These are all real TLDs and
	 * all ordinary in academic use, which is where StarExec's users come from.
	 */
	@Test
	public void addressesOnLongTopLevelDomainsAreAccepted() {
		assertTrue(Validator.isValidEmail("researcher@example.technology"));
		assertTrue(Validator.isValidEmail("researcher@example.university"));
		assertTrue(Validator.isValidEmail("researcher@example.education"));
		assertTrue(Validator.isValidEmail("researcher@example.engineering"));
		assertTrue(Validator.isValidEmail("researcher@example.software"));
		assertTrue(Validator.isValidEmail("researcher@example.international"));
		assertTrue(Validator.isValidEmail("r@e.accountants"));
	}

	/**
	 * A DNS label stops at 63 octets (RFC 1035 2.3.4), and so does the pattern.
	 *
	 * <p>Asserted against the pattern rather than through {@link Validator#isValidEmail}, because
	 * the two bounds cannot both be exercised there: the shortest address with a 64-character
	 * TLD is {@code a@b.} plus 64, or 68 characters, and {@code users.email} is
	 * {@code VARCHAR(64)}. In practice the column width always binds first. Testing the TLD
	 * bound through the validator would therefore assert the column check and call it the TLD
	 * check.
	 */
	@Test
	public void theTopLevelDomainIsBoundedAtTheDnsLabelLimit() {
		Pattern pattern = Pattern.compile(R.EMAIL_PATTERN, Pattern.CASE_INSENSITIVE);

		assertTrue(pattern.matcher("u@e." + "a".repeat(63)).matches());
		assertFalse(
				"64 exceeds the maximum length of a DNS label and must still be refused",
				pattern.matcher("u@e." + "a".repeat(64)).matches());
	}

	/**
	 * The other bound, which the javadoc promised and the implementation did not apply.
	 * {@code users.email} is {@code VARCHAR(64)}, so an address longer than that passed every
	 * check and then failed at the INSERT as a 22001 rather than as a validation message.
	 */
	@Test
	public void anAddressTooLongForItsColumnIsRefused() {
		String at64 = "a".repeat(57) + "@e.com";                 // exactly 63
		assertTrue("inside the column width", Validator.isValidEmail(at64));

		String at65 = "a".repeat(59) + "@e.com";                 // 65
		assertFalse("longer than users.email VARCHAR(64)", Validator.isValidEmail(at65));

		// The shape the TLD change makes easy to reach: valid pattern, over the column.
		assertFalse(Validator.isValidEmail("researcher@example." + "a".repeat(60)));
	}

	// ---------------------------------------------- what must not have changed with it

	@Test
	public void ordinaryAddressesAreStillAccepted() {
		assertTrue(Validator.isValidEmail("test@uiowa.edu"));
		assertTrue(Validator.isValidEmail("test_two@hotmail.com"));
		assertTrue(Validator.isValidEmail("AebEdxjkei382@fake.net"));
		assertTrue(Validator.isValidEmail("first.last+tag@sub.example.org"));
	}

	@Test
	public void malformedAddressesAreStillRefused() {
		assertFalse(Validator.isValidEmail("testuiowaedu"));
		assertFalse(Validator.isValidEmail("testuiowa.com"));
		assertFalse(Validator.isValidEmail("test@uiowanet"));
		assertFalse("a one-character TLD is below the bound", Validator.isValidEmail("test@x.a"));
		assertFalse(Validator.isValidEmail("test@example.123"));
		assertFalse(Validator.isValidEmail(null));
		assertFalse(Validator.isValidEmail(""));
	}

	/**
	 * The pattern is applied with {@code matches()}, so it is anchored at both ends whatever the
	 * expression says. Worth pinning: an unanchored email check is the usual way markup slips
	 * through a field that is later rendered.
	 */
	@Test
	public void aValidAddressEmbeddedInOtherTextIsRefused() {
		assertFalse(Validator.isValidEmail("<script>"));
		assertFalse(Validator.isValidEmail("<script>test@uiowa.edu</script>"));
		assertFalse(Validator.isValidEmail("test@uiowa.edu ; DROP TABLE users"));
		assertFalse(Validator.isValidEmail("prefix test@uiowa.edu"));
		assertFalse(Validator.isValidEmail("test@uiowa.edu\nsecond@line.com"));
	}
}
