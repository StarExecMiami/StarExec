package org.starexec.test.integration.database;

import org.starexec.data.database.Queues;
import org.starexec.data.database.Reports;
import org.starexec.data.to.Queue;
import org.starexec.data.to.Report;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;
import org.junit.jupiter.api.Assertions;

import java.sql.SQLException;
import java.util.List;

public class ReportsTests extends TestSequence {
	private static final String loginsEvent = "unique logins"; // will be set to 0 at the start of the test sequence and reset after
	private static final String pairsEvent = "job pairs run";
	int uniqueLogins = 0;
	int pairsRun = 0;
	
	Queue allQ = null;
	private int getUniqueLoginsCount() {
		List<Report> reports = Reports.getAllReportsNotRelatedToQueues();
		for (Report r : reports) {
			if (r.getEventName().equals(loginsEvent)) {
				return r.getOccurrences();
			}
		}
		return 0;
	}
	private int getPairsRunCount() throws SQLException {
		List<List<Report>> reports = Reports.getAllReportsForAllQueues();
		for ( List<Report> list : reports ) {
			if ( list.get( 0 ).getQueueName() != null && list.get( 0 ).getQueueName().equals( allQ.getName() ) ) {
				for ( Report r : list ) {
					if ( r.getEventName().equals( pairsEvent ) ) {
						return r.getOccurrences();
					}
				}
			}
		}
		
		return 0;
	}
	
	@StarexecTest
	private void setEventOccurrencesNotRelatedToQueueTest() {
		Assertions.assertTrue(Reports.setEventOccurrencesNotRelatedToQueue(loginsEvent, 1));
		Assertions.assertEquals(1, getUniqueLoginsCount());
		Assertions.assertTrue(Reports.setEventOccurrencesNotRelatedToQueue(loginsEvent, 0));
	}
	@StarexecTest
	private void addToEventOccurrencesNotRelatedToQueueTest() {
		Assertions.assertTrue(Reports.setEventOccurrencesNotRelatedToQueue(loginsEvent, 1));
		Assertions.assertTrue(Reports.addToEventOccurrencesNotRelatedToQueue(loginsEvent, 2));
		Assertions.assertEquals(3, getUniqueLoginsCount());
		Assertions.assertTrue(Reports.setEventOccurrencesNotRelatedToQueue(loginsEvent, 0));
	}
	
	@StarexecTest
	private void addToEventOccurrencesRelatedToQueueTest() {
		try {
			Assertions.assertTrue(Reports.addToEventOccurrencesForQueue(pairsEvent, 1, allQ.getName()));
			Assertions.assertTrue(Reports.addToEventOccurrencesForQueue(pairsEvent, 2, allQ.getName()));
			Assertions.assertEquals(pairsRun+3, getPairsRunCount());
			Assertions.assertTrue(Reports.addToEventOccurrencesForQueue(pairsEvent, -3, allQ.getName()));
		} catch ( SQLException e ) {
			log.error( "Caught SQLException: "+ e.getMessage(), e);
		}

	}
	
	
	@Override
	protected String getTestName() {
		return "ReportsTests";
	}

	@Override
	protected void setup() throws Exception {
		uniqueLogins = getUniqueLoginsCount();
		Reports.setEventOccurrencesNotRelatedToQueue(loginsEvent, 0);
		allQ = Queues.getAllQ();
		pairsRun = getPairsRunCount();
	}

	@Override
	protected void teardown() throws Exception {
		Reports.setEventOccurrencesNotRelatedToQueue(loginsEvent, uniqueLogins);
	}

}
