package org.starexec.test.integration.security;

import org.junit.jupiter.api.Assertions;
import org.starexec.constants.R;
import org.starexec.constants.DB;
import org.starexec.test.TestUtil;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;
import org.starexec.util.Validator;

public class ValidatorTests extends TestSequence {


	@StarexecTest
	private void DescriptionRegexTest() {
		Assertions.assertTrue(Validator.isValidPrimDescription(""));
		Assertions.assertTrue(Validator.isValidPrimDescription("hello world"));
		Assertions.assertTrue(Validator.isValidPrimDescription("This is a sentence, and it has some punctuation."));
		Assertions.assertTrue(Validator.isValidPrimDescription("293md03 32idiu"));
		Assertions.assertTrue(Validator.isValidPrimDescription(TestUtil.getRandomAlphaString(DB.BENCH_DESC_LEN)));
		Assertions.assertTrue(Validator.isValidPrimDescription("2017-05-22"));
		Assertions.assertTrue(Validator.isValidPrimDescription("C++"));
		Assertions.assertTrue(Validator.isValidPrimDescription("https://my-tool.example.com"));
		Assertions.assertFalse(Validator.isValidPrimDescription(TestUtil.getRandomAlphaString(DB.BENCH_DESC_LEN+1)));
		Assertions.assertFalse(Validator.isValidPrimDescription(null));
		Assertions.assertFalse(Validator.isValidPrimDescription("\"\""));
		Assertions.assertFalse(Validator.isValidPrimDescription("<script>"));
	}

	@StarexecTest
	private void WebsiteRegexTest() {
		Assertions.assertTrue(Validator.isValidWebsite("http://www.uiowa.edu"));
		Assertions.assertTrue(Validator.isValidWebsite("https://cs.starexec.uiowa.edu"));
		Assertions.assertTrue(Validator.isValidWebsite("http://www.google.com"));

		Assertions.assertFalse(Validator.isValidWebsite(null));
		Assertions.assertFalse(Validator.isValidWebsite("google.com"));
		Assertions.assertFalse(Validator.isValidWebsite("<script></script>"));

	}

	@StarexecTest
	private void QueueNameTest() {
		Assertions.assertTrue(Validator.isValidQueueName("hello world"));
		Assertions.assertTrue(Validator.isValidQueueName("Mark"));
		Assertions.assertTrue(Validator.isValidQueueName("293md03 32idiu"));

		Assertions.assertTrue(Validator.isValidQueueName(TestUtil.getRandomAlphaString(DB.QUEUE_NAME_LEN)));

		Assertions.assertFalse(Validator.isValidQueueName(TestUtil.getRandomAlphaString(DB.QUEUE_NAME_LEN+1)));
		Assertions.assertFalse(Validator.isValidQueueName(""));
		Assertions.assertFalse(Validator.isValidQueueName(null));
		Assertions.assertFalse(Validator.isValidQueueName("\"\""));
		Assertions.assertFalse(Validator.isValidQueueName("<script>"));
	}

	@StarexecTest
	private void JobNameTest() {
		Assertions.assertTrue(Validator.isValidJobName("hello world"));
		Assertions.assertTrue(Validator.isValidJobName("Mark"));
		Assertions.assertTrue(Validator.isValidJobName("293md03 32idiu"));
		Assertions.assertTrue(Validator.isValidJobName(TestUtil.getRandomAlphaString(DB.JOB_NAME_LEN)));

		Assertions.assertFalse(Validator.isValidJobName(TestUtil.getRandomAlphaString(DB.JOB_NAME_LEN+1)));
		Assertions.assertFalse(Validator.isValidJobName(""));
		Assertions.assertFalse(Validator.isValidJobName(null));
		Assertions.assertFalse(Validator.isValidJobName("\"\""));
		Assertions.assertFalse(Validator.isValidJobName("<script>"));
	}

	@StarexecTest
	private void ConfigurationNameTest() {
		Assertions.assertTrue(Validator.isValidConfigurationName("hello world"));
		Assertions.assertTrue(Validator.isValidConfigurationName("Mark"));
		Assertions.assertTrue(Validator.isValidConfigurationName("293md03 32idiu"));
		Assertions.assertTrue(Validator.isValidConfigurationName(TestUtil.getRandomAlphaString(DB.CONFIGURATION_NAME_LEN)));
		Assertions.assertFalse(Validator.isValidConfigurationName(TestUtil.getRandomAlphaString(DB.CONFIGURATION_NAME_LEN+1)));
		Assertions.assertFalse(Validator.isValidConfigurationName(""));
		Assertions.assertFalse(Validator.isValidConfigurationName(null));
		Assertions.assertFalse(Validator.isValidConfigurationName("\"\""));
		Assertions.assertFalse(Validator.isValidConfigurationName("<script>"));
	}

	@StarexecTest
	private void ProcessorNameTest() {
		Assertions.assertTrue(Validator.isValidProcessorName("hello world"));
		Assertions.assertTrue(Validator.isValidProcessorName("Mark"));
		Assertions.assertTrue(Validator.isValidProcessorName("293md03 32idiu"));
		Assertions.assertTrue(Validator.isValidProcessorName(TestUtil.getRandomAlphaString(DB.PROCESSOR_NAME_LEN)));
		Assertions.assertFalse(Validator.isValidProcessorName(TestUtil.getRandomAlphaString(DB.PROCESSOR_NAME_LEN+1)));
		Assertions.assertFalse(Validator.isValidProcessorName(""));
		Assertions.assertFalse(Validator.isValidProcessorName(null));
		Assertions.assertFalse(Validator.isValidProcessorName("\"\""));
		Assertions.assertFalse(Validator.isValidProcessorName("<script>"));
	}

	@StarexecTest
	private void BenchmarkNameTest() {
		Assertions.assertTrue(Validator.isValidBenchName("hello world"));
		Assertions.assertTrue(Validator.isValidBenchName("Mark"));
		Assertions.assertTrue(Validator.isValidBenchName("293md03 32idiu"));
		Assertions.assertTrue(Validator.isValidBenchName(TestUtil.getRandomAlphaString(DB.BENCH_NAME_LEN)));

		Assertions.assertFalse(Validator.isValidBenchName(TestUtil.getRandomAlphaString(DB.BENCH_NAME_LEN+1)));
		Assertions.assertFalse(Validator.isValidBenchName(""));
		Assertions.assertFalse(Validator.isValidBenchName(null));
		Assertions.assertFalse(Validator.isValidBenchName("\"\""));
		Assertions.assertFalse(Validator.isValidBenchName("<script>"));
	}

	@StarexecTest
	private void SolverNameRegexTest() {
		Assertions.assertTrue(Validator.isValidSolverName("hello world"));
		Assertions.assertTrue(Validator.isValidSolverName("Mark"));
		Assertions.assertTrue(Validator.isValidSolverName("293md03 32idiu"));
		Assertions.assertTrue(Validator.isValidSolverName(TestUtil.getRandomAlphaString(DB.SOLVER_NAME_LEN)));

		Assertions.assertFalse(Validator.isValidSolverName(TestUtil.getRandomAlphaString(DB.SOLVER_NAME_LEN+1)));
		Assertions.assertFalse(Validator.isValidSolverName(""));
		Assertions.assertFalse(Validator.isValidSolverName(null));
		Assertions.assertFalse(Validator.isValidSolverName("\"\""));
		Assertions.assertFalse(Validator.isValidSolverName("<script>"));
	}

	@StarexecTest
	private void BoolRegexTest() {
		Assertions.assertTrue(Validator.isValidBool("true"));
		Assertions.assertTrue(Validator.isValidBool("false"));
		Assertions.assertTrue(Validator.isValidBool("TruE"));
		Assertions.assertTrue(Validator.isValidBool("FALsE"));
		Assertions.assertFalse(Validator.isValidBool(""));
		Assertions.assertFalse(Validator.isValidBool("94"));
		Assertions.assertFalse(Validator.isValidBool("adsfc"));
		Assertions.assertFalse(Validator.isValidBool("4ks"));
		Assertions.assertFalse(Validator.isValidBool("itisfalsethatthisshouldreturntrue"));
		Assertions.assertFalse(Validator.isValidBool(null));

	}

	@StarexecTest
	private void InstitutionRegexTest() {
		Assertions.assertTrue(Validator.isValidInstitution("The University Of Iowa"));
		Assertions.assertTrue(Validator.isValidInstitution("test"));

		StringBuilder sb=new StringBuilder();
		for (int i=0; i<DB.INSTITUTION_LEN+2;i++) {
			sb.append("a");
		}
		Assertions.assertFalse(Validator.isValidInstitution(sb.toString()));
		Assertions.assertFalse(Validator.isValidInstitution(""));
		Assertions.assertFalse(Validator.isValidInstitution(null));
		Assertions.assertFalse(Validator.isValidInstitution("The University @ Iowa"));
		Assertions.assertFalse(Validator.isValidInstitution("<script>"));
	}

	@StarexecTest
	private void IntRegexTest() {
		Assertions.assertTrue(Validator.isValidInteger("4"));
		Assertions.assertTrue(Validator.isValidInteger("-3835"));
		Assertions.assertTrue(Validator.isValidInteger("3420202"));
		Assertions.assertTrue(Validator.isValidInteger("0"));
		Assertions.assertFalse(Validator.isValidInteger("p"));
		Assertions.assertFalse(Validator.isValidInteger("402kj"));
		Assertions.assertFalse(Validator.isValidInteger("2378942398743289732"));
		Assertions.assertFalse(Validator.isValidInteger(".4"));
		Assertions.assertFalse(Validator.isValidInteger("4-4"));
		Assertions.assertFalse(Validator.isValidInteger(null));

	}
	@StarexecTest
	private void ArchiveRegexTest() {
		Assertions.assertTrue(Validator.isValidArchiveType(".zip"));
		Assertions.assertTrue(Validator.isValidArchiveType(".tar"));
		Assertions.assertTrue(Validator.isValidArchiveType(".tar.gz"));
		Assertions.assertTrue(Validator.isValidArchiveType(".tgz"));
		Assertions.assertFalse(Validator.isValidArchiveType(".rar"));
		Assertions.assertFalse(Validator.isValidArchiveType(".exe"));
		Assertions.assertFalse(Validator.isValidArchiveType(""));
		Assertions.assertFalse(Validator.isValidArchiveType("."));
		Assertions.assertFalse(Validator.isValidArchiveType(null));
		Assertions.assertFalse(Validator.isValidArchiveType("<script>"));

	}
	@StarexecTest
	private void DoubleRegexTest() {
		Assertions.assertTrue(Validator.isValidPosDouble("4.0"));
		Assertions.assertFalse(Validator.isValidPosDouble("-.9"));
		Assertions.assertFalse(Validator.isValidPosDouble("-4"));
		Assertions.assertTrue(Validator.isValidPosDouble("493."));
		Assertions.assertFalse(Validator.isValidPosDouble("-393.245"));
		Assertions.assertTrue(Validator.isValidPosDouble("4022.2935"));
		Assertions.assertFalse(Validator.isValidPosDouble("."));
		Assertions.assertFalse(Validator.isValidPosDouble("3.52.34"));
		Assertions.assertFalse(Validator.isValidPosDouble("-."));
		Assertions.assertFalse(Validator.isValidPosDouble("dsd"));
		Assertions.assertFalse(Validator.isValidPosDouble("3493.2de"));
		Assertions.assertFalse(Validator.isValidPosDouble(null));
	}
	@StarexecTest
	private void EmailRegexTest() {
		Assertions.assertTrue(Validator.isValidEmail("test@uiowa.edu"));
		Assertions.assertTrue(Validator.isValidEmail("test_two@hotmail.com"));
		Assertions.assertTrue(Validator.isValidEmail("AebEdxjkei382@fake.net"));
		Assertions.assertFalse(Validator.isValidEmail("testuiowaedu"));
		Assertions.assertFalse(Validator.isValidEmail("testuiowa.com"));
		Assertions.assertFalse(Validator.isValidEmail("test@uiowanet"));
		Assertions.assertFalse(Validator.isValidEmail(null));
		Assertions.assertFalse(Validator.isValidEmail("<script>"));


	}
	@StarexecTest
	private void IntegerListTest() {
		Assertions.assertTrue(Validator.isValidIntegerList(new String[]{"3","1"}));
		Assertions.assertTrue(Validator.isValidIntegerList(new String[]{"0","132929","3492"}));
		Assertions.assertTrue(Validator.isValidIntegerList(new String[]{"3"}));
		Assertions.assertTrue(Validator.isValidIntegerList(new String[]{}));
		Assertions.assertFalse(Validator.isValidIntegerList(new String[]{"3kd"}));
		Assertions.assertFalse(Validator.isValidIntegerList(new String[]{"0","132929","3492","d"}));
		Assertions.assertFalse(Validator.isValidIntegerList((String[])null));


	}


	@Override
	protected String getTestName() {
		return "ValidatorTests";
	}

	@Override
	protected void setup() throws Exception {

	}

	@Override
	protected void teardown() throws Exception {

	}

}
