package org.starexec.test.integration.database;

import org.junit.jupiter.api.Assertions;
import org.starexec.constants.DB;
import org.starexec.constants.R;
import org.starexec.data.database.Communities;
import org.starexec.data.database.Uploads;
import org.starexec.data.to.*;
import org.starexec.test.TestUtil;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;

import java.util.List;
import java.util.Random;

/**
 * Tests for org.starexec.data.database.Uploads.java
 * @author Eric
 */
public class UploadTests extends TestSequence {
	private User u=null;
	private Space s=null;
	private BenchmarkUploadStatus bs=null;
	private SpaceXMLUploadStatus ss=null;
	private final Random rand=new Random();
	@Override
	protected String getTestName() {
		return "UploadTests";
	}



	@StarexecTest
	private void getBenchmarkUploadStatusSummaryTest() {
		Assertions.assertNotNull(Uploads.getUploadStatusSummary(bs.getId()));
	}
	@StarexecTest
	private void failedBenchesTest() {
		String benchName=TestUtil.getRandomAlphaString(50);
		Assertions.assertNotNull(Uploads.getFailedBenches(bs.getId()));
		Assertions.assertTrue(Uploads.addFailedBenchmark(bs.getId(), benchName, "invalid"));
		List<Benchmark> failed=Uploads.getFailedBenches(bs.getId());
		boolean found=false;
		for (Benchmark s : failed) {
			if (s.getName().equals(benchName)) {
				found=true;
				break;
			}
		}
		Assertions.assertTrue(found);
	}

	@StarexecTest
	private void xmlIncrementCompletedBenchmarkTest() {
		int totalIncrement=0;
		for (int x=0;x<40;x++) {
			int next=rand.nextInt(20);
			Assertions.assertTrue(Uploads.incrementXMLCompletedBenchmarks(ss.getId(), next));
			totalIncrement+=next;
			Assertions.assertEquals(ss.getCompletedBenchmarks()+totalIncrement, Uploads.getSpaceXMLStatus(ss.getId()).getCompletedBenchmarks());
		}
		ss=Uploads.getSpaceXMLStatus(ss.getId());
	}



	@StarexecTest
	private void xmlIncrementCompletedSolverTest() {
		int totalIncrement=0;
		for (int x=0;x<40;x++) {
			int next=rand.nextInt(20);
			Assertions.assertTrue(Uploads.incrementXMLCompletedSolvers(ss.getId(), next));
			totalIncrement+=next;
			Assertions.assertEquals(ss.getCompletedSolvers()+totalIncrement, Uploads.getSpaceXMLStatus(ss.getId()).getCompletedSolvers());
		}
		ss=Uploads.getSpaceXMLStatus(ss.getId());

	}

	@StarexecTest
	private void xmlIncrementCompletedUpdateTest() {
		int totalIncrement=0;
		for (int x=0;x<40;x++) {
			int next=rand.nextInt(20);
			Assertions.assertTrue(Uploads.incrementXMLCompletedUpdates(ss.getId(), next));
			totalIncrement+=next;
			Assertions.assertEquals(ss.getCompletedUpdates()+totalIncrement, Uploads.getSpaceXMLStatus(ss.getId()).getCompletedUpdates());
		}
		ss=Uploads.getSpaceXMLStatus(ss.getId());

	}

	@StarexecTest
	private void xmlIncrementCompletedSpaceTest() {
		int totalIncrement=0;
		for (int x=0;x<40;x++) {
			int next=rand.nextInt(20);
			Assertions.assertTrue(Uploads.incrementXMLCompletedSpaces(ss.getId(), next));
			totalIncrement+=next;
			Assertions.assertEquals(ss.getCompletedSpaces()+totalIncrement, Uploads.getSpaceXMLStatus(ss.getId()).getCompletedSpaces());
		}
		ss=Uploads.getSpaceXMLStatus(ss.getId());

	}

	@StarexecTest
	private void benchmarkIncrementCompletedBenchmarkTest() {
		int totalIncrement=0;
		for (int x=0;x<40;x++) {
			int next=rand.nextInt(20);
			Assertions.assertTrue(Uploads.incrementCompletedBenchmarks(bs.getId(), next));
			totalIncrement+=next;
			Assertions.assertEquals(bs.getCompletedBenchmarks()+totalIncrement, Uploads.getBenchmarkStatus(bs.getId()).getCompletedBenchmarks());
		}
		bs=Uploads.getBenchmarkStatus(bs.getId());

	}

	@StarexecTest
	private void benchmarkIncrementCompletedSpacesTest() {
		int totalIncrement=0;
		for (int x=0;x<40;x++) {
			int next=rand.nextInt(20);
			Assertions.assertTrue(Uploads.incrementCompletedSpaces(bs.getId(), next));
			totalIncrement+=next;
			Assertions.assertEquals(bs.getCompletedSpaces()+totalIncrement, Uploads.getBenchmarkStatus(bs.getId()).getCompletedSpaces());
		}
		bs=Uploads.getBenchmarkStatus(bs.getId());
	}

	@StarexecTest
	private void benchmarkIncrementFailedBenchmarkTest() {
		int totalIncrement=0;
		for (int x=0;x<40;x++) {
			int next=rand.nextInt(20);
			Assertions.assertTrue(Uploads.incrementFailedBenchmarks(bs.getId(), next));
			totalIncrement+=next;
			Assertions.assertEquals(bs.getFailedBenchmarks()+totalIncrement, Uploads.getBenchmarkStatus(bs.getId()).getFailedBenchmarks());
		}
		bs=Uploads.getBenchmarkStatus(bs.getId());
	}

	@StarexecTest
	private void benchmarkIncrementValidatedBenchmarkTest() {
		int totalIncrement=0;
		for (int x=0;x<40;x++) {
			int next=rand.nextInt(20);
			Assertions.assertTrue(Uploads.incrementValidatedBenchmarks(bs.getId(), next));
			totalIncrement+=next;
			Assertions.assertEquals(bs.getValidatedBenchmarks()+totalIncrement, Uploads.getBenchmarkStatus(bs.getId()).getValidatedBenchmarks());
		}
		bs=Uploads.getBenchmarkStatus(bs.getId());

	}

	@StarexecTest
	private void benchmarkIncrementTotalBenchmarkTest() {
		int totalIncrement=0;
		for (int x=0;x<40;x++) {
			int next=rand.nextInt(20);
			Assertions.assertTrue(Uploads.incrementTotalBenchmarks(bs.getId(), next));
			totalIncrement+=next;
			Assertions.assertEquals(bs.getTotalBenchmarks()+totalIncrement, Uploads.getBenchmarkStatus(bs.getId()).getTotalBenchmarks());
		}
		bs=Uploads.getBenchmarkStatus(bs.getId());

	}

	@StarexecTest
	private void benchmarkIncrementTotalSpacesTest() {
		int totalIncrement=0;
		for (int x=0;x<40;x++) {
			int next=rand.nextInt(20);
			Assertions.assertTrue(Uploads.incrementTotalSpaces(bs.getId(), next));
			totalIncrement+=next;
			Assertions.assertEquals(bs.getTotalSpaces()+totalIncrement, Uploads.getBenchmarkStatus(bs.getId()).getTotalSpaces());
		}
		bs=Uploads.getBenchmarkStatus(bs.getId());

	}

	@StarexecTest
	private void setXMLTotalUpdatesTest() {
		int num=rand.nextInt(20)+5;
		Assertions.assertTrue(Uploads.setXMLTotalUpdates(ss.getId(), num));
		Assertions.assertEquals(num,Uploads.getSpaceXMLStatus(ss.getId()).getTotalUpdates());
		ss.setTotalUpdates(num);
	}
	@StarexecTest
	private void setXMLTotalSolversTest() {
		int num=rand.nextInt(20)+5;
		Assertions.assertTrue(Uploads.setXMLTotalSolvers(ss.getId(), num));
		Assertions.assertEquals(num,Uploads.getSpaceXMLStatus(ss.getId()).getTotalSolvers());
		ss.setTotalSolvers(num);
	}

	@StarexecTest
	private void setXMLTotalBenchmarksTest() {
		int num=rand.nextInt(20)+5;
		Assertions.assertTrue(Uploads.setXMLTotalBenchmarks(ss.getId(), num));
		Assertions.assertEquals(num,Uploads.getSpaceXMLStatus(ss.getId()).getTotalBenchmarks());
		ss.setTotalBenchmarks(num);
	}

	@StarexecTest
	private void setXMLTotalSpacesTest() {
		int num=rand.nextInt(20)+5;
		Assertions.assertTrue(Uploads.setXMLTotalSpaces(ss.getId(), num));
		Assertions.assertEquals(num,Uploads.getSpaceXMLStatus(ss.getId()).getTotalSpaces());
		ss.setTotalSpaces(num);
	}

	@StarexecTest
	private void setXMLErrorMessageTest() {
		String msg=TestUtil.getRandomAlphaString(DB.MSG_LEN);
		Assertions.assertTrue(Uploads.setXMLErrorMessage(ss.getId(), msg));
		Assertions.assertEquals(msg,Uploads.getSpaceXMLStatus(ss.getId()).getErrorMessage());
		ss.setErrorMessage(msg);
	}
	@StarexecTest
	private void setBenchmarkErrorMessageTest() {
		String msg=TestUtil.getRandomAlphaString(DB.MSG_LEN);
		Assertions.assertTrue(Uploads.setBenchmarkErrorMessage(bs.getId(), msg));

		Assertions.assertEquals(msg,Uploads.getBenchmarkStatus(bs.getId()).getErrorMessage());
		bs.setErrorMessage(msg);
	}

	@StarexecTest
	private void xmlEverythingCompleteTest() {
		Assertions.assertFalse(Uploads.getSpaceXMLStatus(ss.getId()).isEverythingComplete());
		Assertions.assertTrue(Uploads.XMLEverythingComplete(ss.getId()));
		Assertions.assertTrue(Uploads.getSpaceXMLStatus(ss.getId()).isEverythingComplete());
		ss.setEverythingComplete(true);
	}

	@StarexecTest
	private void xmlFileUploadCompleteTest() {
		Assertions.assertFalse(Uploads.getSpaceXMLStatus(ss.getId()).isFileUploadComplete());
		Assertions.assertTrue(Uploads.XMLFileUploadComplete(ss.getId()));
		Assertions.assertTrue(Uploads.getSpaceXMLStatus(ss.getId()).isFileUploadComplete());
		ss.setFileUploadComplete(true);
	}

	@StarexecTest
	private void benchmarkEverythingCompleteTest() {
		Assertions.assertFalse(Uploads.getBenchmarkStatus(bs.getId()).isEverythingComplete());
		Assertions.assertTrue(Uploads.benchmarkEverythingComplete(bs.getId()));
		Assertions.assertTrue(Uploads.getBenchmarkStatus(bs.getId()).isEverythingComplete());
		bs.setEverythingComplete(true);
	}

	@StarexecTest
	private void benchmarkFileUploadCompleteTest() {
		Assertions.assertFalse(Uploads.getBenchmarkStatus(bs.getId()).isFileUploadComplete());
		Assertions.assertTrue(Uploads.benchmarkFileUploadComplete(bs.getId()));
		Assertions.assertTrue(Uploads.getBenchmarkStatus(bs.getId()).isFileUploadComplete());
		bs.setFileUploadComplete(true);
	}

	@StarexecTest
	private void benchmarkFileExtractCompleteTest() {
		Assertions.assertFalse(Uploads.getBenchmarkStatus(bs.getId()).isFileExtractionComplete());
		Assertions.assertTrue(Uploads.fileExtractComplete(bs.getId()));
		Assertions.assertTrue(Uploads.getBenchmarkStatus(bs.getId()).isFileExtractionComplete());
		bs.setFileExtractionComplete(true);
	}
	@StarexecTest
	private void benchmarkProcessingBegunTest() {
		Assertions.assertFalse(Uploads.getBenchmarkStatus(bs.getId()).isProcessingBegun());
		Assertions.assertTrue(Uploads.processingBegun(bs.getId()));
		Assertions.assertTrue(Uploads.getBenchmarkStatus(bs.getId()).isProcessingBegun());
		bs.setProcessingBegun(true);
	}


	@Override
	protected void setup() throws Exception {
		u=loader.loadUserIntoDatabase();
		s=loader.loadSpaceIntoDatabase(u.getId(), Communities.getTestCommunity().getId());
		bs=Uploads.getBenchmarkStatus(Uploads.createBenchmarkUploadStatus(s.getId(), u.getId()));
		Assertions.assertNotNull(bs);
		ss=Uploads.getSpaceXMLStatus(Uploads.createSpaceXMLUploadStatus(u.getId()));

	}


	@Override
	protected void teardown() throws Exception {
		loader.deleteAllPrimitives();
	}

}
