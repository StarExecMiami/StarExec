package org.starexec.test.junit.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.util.DataTablesQuery;
import org.starexec.util.PaginationQueryBuilder;


public class PaginationQueryBuilderTests {

	private static final String baseSQL = "select * from users";
	private static DataTablesQuery query = null;
	
	@BeforeEach
	public void setupQuery() {
		query = new DataTablesQuery();
		query.setNumRecords(5);
		query.setStartingRecord(3);
		query.setSortASC(false);
	}
	
	@Test
	public void testGetSQLDESC() {
		PaginationQueryBuilder b = new PaginationQueryBuilder(baseSQL, "first_name", query);
		String expected = "select * from users\nORDER BY first_name DESC\nLIMIT 5 OFFSET 3;";
		Assertions.assertEquals(expected, b.getSQL());
	}
	
	@Test
	public void testGetSQLASC() {
		query.setSortASC(true);
		PaginationQueryBuilder b = new PaginationQueryBuilder(baseSQL, "first_name", query);
		String expected = "select * from users\nORDER BY first_name ASC\nLIMIT 5 OFFSET 3;";
		Assertions.assertEquals(expected, b.getSQL());
		
	}
}
