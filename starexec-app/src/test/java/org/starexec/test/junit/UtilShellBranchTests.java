package org.starexec.test.junit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.starexec.util.Util;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A one-element command array must never be handed to a shell.
 *
 * <p>{@code Util} offers two ways to run a command. The {@code String} overloads are
 * deprecated, documented as shell-invoking, and pass through
 * {@code ensureShellCommandIsSafe}, which rejects {@code ; | & > < ` \r \n} and
 * {@code $(}. The {@code String[]} overloads are the safe, tokenized form: the array is
 * the argv, and no shell parses it.
 *
 * <p>{@code buildProcess} broke that split. When the array had exactly one element it
 * quietly rewrote the call as {@code /bin/sh -c <element>} (or {@code cmd.exe /c} on
 * Windows) -- the shell path, reached through the API documented as not using one, and
 * without the metacharacter check that guards the shell path's front door. A caller who
 * chose the tokenized overload precisely to avoid a shell got a shell anyway, with no
 * validation.
 *
 * <p>No caller in this repository passes a one-element array, so the branch was
 * unreachable in practice; it is the shape of the mistake that matters. Refusing the call
 * outright is preferred over silently routing it to the guarded overload, because a
 * one-element array means the caller has built a command line as text and any splitting
 * decision this method invented would be a guess about their intent.
 */
public class UtilShellBranchTests {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/**
	 * The discriminating test: a shell would create the file, argv cannot.
	 *
	 * <p>Asserting only that an exception is thrown would be weaker -- it would still pass
	 * if the command ran and then failed for some unrelated reason. Checking the file is
	 * absent proves the shell never interpreted the redirection.
	 */
	@Test
	public void aOneElementArrayIsNotInterpretedByAShell() throws IOException {
		File marker = new File(folder.getRoot(), "shell-ran");
		String command = "echo pwned > " + marker.getAbsolutePath();

		try {
			Util.executeCommand(new String[] { command });
			fail("a one-element array must be refused, not run through a shell");
		} catch (IllegalArgumentException expected) {
			// The contract: the tokenized overload does not accept a shell command line.
		}

		assertFalse(
				"the shell interpreted '>' and created " + marker + "; argv was not honoured",
				marker.exists());
	}

	/** The same refusal must not depend on which metacharacter is used. */
	@Test
	public void aOneElementArrayIsRefusedEvenWithoutMetacharacters() {
		try {
			Util.executeCommand(new String[] { "true" });
			fail("a one-element array must be refused regardless of its content");
		} catch (IllegalArgumentException expected) {
			assertTrue(
					"the message should point the caller at the right overload",
					expected.getMessage().toLowerCase().contains("shell")
							|| expected.getMessage().toLowerCase().contains("argument"));
		} catch (IOException e) {
			fail("refusal must be an IllegalArgumentException, not an IOException: " + e);
		}
	}

	/**
	 * The tokenized path must still work, and must still not involve a shell.
	 *
	 * <p>Without this, the change above could be satisfied by breaking {@code String[]}
	 * execution altogether.
	 */
	@Test
	public void aMultiElementArrayStillRunsTokenized() throws IOException {
		String output = Util.executeCommand(new String[] { "echo", "hello" });
		assertEquals("hello", output.trim());
	}

	/**
	 * argv is passed through literally: metacharacters in an argument stay data.
	 *
	 * <p>If a shell were involved, {@code >} would redirect and {@code echo} would print
	 * nothing. That it is echoed verbatim is the proof that no shell parsed it.
	 */
	@Test
	public void metacharactersInAnArgumentAreDataNotSyntax() throws IOException {
		File marker = new File(folder.getRoot(), "argv-shell-ran");
		String output = Util.executeCommand(
				new String[] { "echo", ">", marker.getAbsolutePath() });

		assertTrue("the redirection characters must be echoed, not obeyed",
				output.contains(">"));
		assertFalse("a shell interpreted the redirection", marker.exists());
	}
}
