package org.starexec.data.database;

import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;
import org.starexec.constants.R;
import org.starexec.logger.NonSavingStarLogger;
import org.starexec.util.NamedParameterStatement;
import org.starexec.util.Util;
import org.starexec.util.functionalInterfaces.ThrowingBiFunction;
import org.starexec.util.functionalInterfaces.ThrowingConsumer;
import org.starexec.util.functionalInterfaces.ThrowingFunction;

import java.sql.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLFeatureNotSupportedException;

/**
 * The common database class which provides common methods used by other database accessors such
 * as transaction management and rollback support. Also provides connections and maintains an active
 * data pool of available connections to the PostgreSQL database.
 */
public class Common {
	// We have to use the non saving star logger here because this class is used by the ErrorLogs class. If ErrorLogs
	// calls this class and this class logs an error then that error will be saved to the database via ErrorLogs (if we
	// were using StarLogger instead of NonSavingStarLogger). This could cause infinite recursion.
	private static final NonSavingStarLogger log = NonSavingStarLogger.getLogger(Common.class);
	private static DataSource dataPool = null;

	private static int connectionsOpened = 0;
	private static int connectionsDrift  = 0;

	// args to append to the database URL
	private static final String POSTGRES_URL_ARGUMENTS = "?sslmode=disable&stringtype=unspecified&currentSchema=starexec,public&ApplicationName=StarExec&socketTimeout=30&connectTimeout=10&tcpKeepAlive=true";

	/**
	 * Removes surrounding JDBC ODBC escape braces if present. Callers historically pass strings like
	 * "{SELECT * FROM starexec.SomeFunc(?)}" using the ODBC escape syntax. PostgreSQL JDBC chokes on
	 * the braces, so normalize the SQL before preparing the call.
	 */
	private static String stripBraces(String sql) {
		if (sql == null) return null;
		String s = sql.trim();
		if (s.startsWith("{") && s.endsWith("}")) {
			return s.substring(1, s.length() - 1).trim();
		}
		return s;
	}

	/**
	 * Create a CallableStatement when possible. If the SQL looks like a regular SELECT/INSERT/UPDATE/DELETE
	 * or a CTE (WITH ...), use a PreparedStatement for efficiency and compatibility with Postgres. Because
	 * callers expect a CallableStatement, we return a dynamic proxy that implements CallableStatement but
	 * delegates supported methods to the underlying PreparedStatement. Methods that are specific to
	 * CallableStatement (like registerOutParameter/getXXX for OUT params) will throw SQLFeatureNotSupportedException.
	 */
	private static CallableStatement prepareCallable(Connection con, String sql) throws SQLException {
		String normalized = stripBraces(sql);
		if (normalized == null) return con.prepareCall(normalized);
		String up = normalized.trim().toUpperCase();
		boolean usePrepared = up.startsWith("SELECT") || up.startsWith("INSERT") || up.startsWith("UPDATE") || up.startsWith("DELETE") || up.startsWith("WITH");
		if (!usePrepared) {
			return con.prepareCall(normalized);
		}
		final PreparedStatement ps = con.prepareStatement(normalized);
		return callableFromPrepared(ps);
	}

	/**
	 * Returns a dynamic proxy implementing CallableStatement which delegates to the provided PreparedStatement
	 * for common methods. Callable-specific methods will throw SQLFeatureNotSupportedException.
	 */
	private static CallableStatement callableFromPrepared(final PreparedStatement ps) {
		InvocationHandler handler = new InvocationHandler() {
			@Override
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				String name = method.getName();
				try {
					// Try to find the same method on PreparedStatement and invoke it
					Method m = null;
					try {
						m = ps.getClass().getMethod(name, method.getParameterTypes());
					} catch (NoSuchMethodException e) {
						// Not found on PreparedStatement
					}
					if (m != null) {
						return m.invoke(ps, args);
					}
					// Handle a few common cases explicitly
					if ("close".equals(name)) {
						ps.close();
						return null;
					}
					if (name.startsWith("get") || name.startsWith("registerOutParameter") || name.startsWith("wasNull") || name.startsWith("getObject")) {
						throw new SQLFeatureNotSupportedException("CallableStatement OUT parameters are not supported on PreparedStatement-backed proxy");
					}
					throw new SQLFeatureNotSupportedException("Method '" + name + "' is not supported on PreparedStatement-backed CallableStatement proxy");
				} catch (Throwable t) {
					// Unwrap invocation target exceptions
					if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
						throw t.getCause();
					}
					throw t;
				}
			}
		};
		Object proxy = Proxy.newProxyInstance(CallableStatement.class.getClassLoader(), new Class[] { CallableStatement.class }, handler);
		return (CallableStatement) proxy;
	}

	/**
	 * Creates a new historical record in the logins table which keeps track of all user logins.
	 * @param userId The user that has logged in
	 * @param ipAddress The IP address the user logged in from
	 * @param browser The browser/agent information about the browser the user logged in with
	 */
	public static void addLoginRecord(int userId, String ipAddress, String browser) {
		Connection con = null;
		CallableStatement procedure = null;
		ResultSet rs = null;
		try {
			con = Common.getConnection();
			String callSql = stripBraces("{SELECT starexec.LoginRecord(?, ?, ?)}");
			procedure = prepareCallable(con, callSql);
			procedure.setInt(1, userId);
			procedure.setString(2, ipAddress);
			procedure.setString(3, browser);
			// Use execute() to handle functions that may return a result. If a ResultSet is returned, close it.
			procedure.execute();
			rs = procedure.getResultSet();
			if (rs != null) {
				Common.safeClose(rs);
			}
		} catch (SQLException e) {
			log.error("addLoginRecord", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(rs);
			Common.safeClose(procedure);
		}
	}

	/**
	 * Begins a transaction by turning off auto-commit
	 */
	protected static void beginTransaction(Connection con) {
		try {
			con.setAutoCommit(false);
		} catch (SQLException e) {
			// Ignore any errors
		}
	}

	/**
	 * Rolls back any actions not committed to the database
	 */
	protected static void doRollback(Connection con) {
		try {
			con.rollback();
			con.setAutoCommit(true);
			log.warn("Database transaction rollback.");
		} catch (SQLException e) {
			// Ignore any errors
		}
	}

	/**
	 * Turns on auto-commit
	 */
	protected static void enableAutoCommit(Connection con) {
		try {
			con.setAutoCommit(true);
		} catch (SQLException e) {
			// Ignore any errors
		}
	}

	/**
	 * Logs the total number of connections idle and active at the time this is called
	 */
	public static void logConnectionsOpen() {
		if (dataPool == null) {
			// Data pool not initialized (tests or early startup). Nothing to log.
			log.info("logConnectionsOpen", "dataPool is null");
			return;
		}

		log.info("logConnectionsOpen",
				"idle=" + dataPool.getIdle()
				+ "\tactive=" + dataPool.getActive()
				+ "\tinternal count=" + String.valueOf(connectionsOpened)
		);
	}

	/**
	 * Ends a transaction by committing any changes and re-enabling auto-commit
	 */
	protected static void endTransaction(Connection con) {
		try {
			con.commit();
			enableAutoCommit(con);
		} catch (SQLException e) {
			Common.doRollback(con);
		}
	}

	/**
	 * @return a new connection to the database from the connection pool
	 * @author Tyler Jensen
	 */
	protected synchronized static Connection getConnection() throws SQLException {
		try {
			Connection c = dataPool.getConnection();
			++connectionsOpened;
			checkConnectionsCount();
			return c;
		} catch (SQLException e) {
			log.error("getConnection", "connectionsOpened: "+connectionsOpened, e);
			throw e;
		}
	}

	private synchronized static void checkConnectionsCount() {
		if (dataPool == null) {
			// No pool initialized (unit tests or early startup). Skip checks.
			return;
		}

		if (connectionsOpened-dataPool.getActive() != connectionsDrift) {
			log.info("logConnectionsOpen",
			         "Number of active connections reported by dataPool differs from internal count." +
			         "\n\tconnectionsOpened:    " + connectionsOpened +
			         "\n\tdataPool.getActive(): " + dataPool.getActive()
			);
			connectionsDrift = connectionsOpened-dataPool.getActive();
		}
	}

	/**
	 * Gets information on the data pool.  Used to track down connection leak.
	 * @return Returns true if not nearing max active connections
	 */
	public static Boolean getDataPoolData() {
		if (dataPool == null) {
			// Not initialized — treat as "not nearing max" for callers in test environments.
			log.info("getDataPoolData", "dataPool is null");
			return true;
		}

		log.info("Data Pool has " + dataPool.getActive() + " active connections.  ");
		if (dataPool.getWaitCount() > 0) {
			log.info("# of threads waiting for a connection = " + dataPool.getWaitCount());
		}
		return dataPool.getActive() <= .5 * R.POSTGRES_POOL_MAX_SIZE;
	}

	/**
	 * Configures and sets up the Tomcat JDBC connection pool for PostgreSQL.
	 * This method can only be called once in the lifetime of the application.
	 * @author Tyler Jensen (adapted for Postgres)
	 */
	public static void initialize() {
		try {
			if (Util.isNullOrEmpty(R.POSTGRES_USERNAME)) {
				log.warn("Attempted to initialize datapool without POSTGRES properties being set");
				return;
			} else if (dataPool != null) {
				log.warn("Attempted to initialize datapool when it was already initialized");
				return;
			}

			log.debug("Setting up data connection pool properties (Postgres)");
			PoolProperties poolProp = new PoolProperties();				// Set up the Tomcat JDBC connection pool with the following properties
			log.info("Setting up data connection pool with these properties:");
			log.info(R.POSTGRES_URL);
			log.info(R.POSTGRES_DRIVER);
			log.info(R.POSTGRES_USERNAME);

			// URL to the database we want to use (append Postgres-specific args)
			poolProp.setUrl(R.POSTGRES_URL + POSTGRES_URL_ARGUMENTS);
			poolProp.setDriverClassName(R.POSTGRES_DRIVER);      // JDBC driver for Postgres
			poolProp.setUsername(R.POSTGRES_USERNAME);           // Database username
			poolProp.setPassword(R.POSTGRES_PASSWORD);           // Database password for the given username
			poolProp.setTestOnBorrow(true);                      // Check if a connection is live every time we take one from the pool
			poolProp.setValidationQuery("SELECT 1");             // Query to check if a connection is live
			poolProp.setValidationInterval(30000);               // Only check live connection every 30 seconds when borrowing (milliseconds)
			poolProp.setMaxActive(R.POSTGRES_POOL_MAX_SIZE);     // Max active connections in the pool
			poolProp.setInitialSize(R.POSTGRES_POOL_MIN_SIZE);   // How many connections the pool will start out with
			poolProp.setMinIdle(R.POSTGRES_POOL_MIN_SIZE);       // Minimum number of connections to keep ready
			poolProp.setDefaultAutoCommit(true);                 // Turn autocommit on (transactions disabled by default)
			poolProp.setJmxEnabled(false);                       // Turn JMX off
			// Abandoned connection handling (seconds)
			poolProp.setRemoveAbandonedTimeout(18000);
			poolProp.setRemoveAbandoned(true);
			poolProp.setLogAbandoned(true);
			// PostgreSQL-specific optimizations
			poolProp.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
			poolProp.setMaxWait(10000);                          // Max wait time for a connection (10 seconds)

			log.debug("Creating new datapool with supplied properties (Postgres)");
			dataPool = new DataSource(poolProp);              // Create the connection pool with the supplied properties

			log.debug("Datapool successfully created!");
		} catch (Exception e) {
			log.fatal("initialize", e);
		}
	}

	/**
	 * Makes a query and allows the user to make additional calls on the same connection.
	 * @param callPreparationSql the SQL to prepare the SQL Procedure (e.g. "{Call MyProcedure(?, ?)}")
	 * @param setParameters lambda used to set arguments to procedure.
	 * @param connectionResultsFunction lambda used to transform results to desired type and use open DB connection.
	 * @param <T> the type we want to transform the results to.
	 * @return the results of the query as type T.
	 * @throws SQLException if there is a database error.
	 */
	static <T> T queryKeepConnection(
			String callPreparationSql,
			ThrowingConsumer<CallableStatement, SQLException> setParameters,
			ThrowingBiFunction<Connection, ResultSet, T, SQLException> connectionResultsFunction) throws SQLException
	{
		Connection con = null;
		try {
			con = Common.getConnection();
			return queryUsingConnectionKeepConnection(callPreparationSql, con, setParameters, connectionResultsFunction);
		} finally {
			Common.safeClose(con);
		}
	}

	/**
	 * This method will start a new transaction and do an update. Does a rollback if there is an error.
	 * @param callPreparationSql the SQL needed to prepare the call.
	 * @param setParameters the code that should be run to setup the procedure. (Set parameters)
	 * @throws SQLException
	 */
	public static void update(String callPreparationSql, ThrowingConsumer<CallableStatement,SQLException> setParameters) throws SQLException {
		Connection con = null;
		try {
			con = Common.getConnection();
			Common.beginTransaction(con);
			updateUsingConnection(con, callPreparationSql, setParameters);
			Common.endTransaction(con);
		} catch (SQLException e) {
			log.warn("update", "Rolling back update & rethrowing SQLException", e);
			Common.doRollback(con);
			throw e;
		} finally {
			Common.safeClose(con);
		}
	}

	/**
	 * Runs an update that produces some output.
	 * @param callPreparationSql The SQL to call the stored procedure.
	 * @param setParameters a void function (consumer) that sets the parameters of the procedure.
	 * @param getOutput a function that gets the output from the procedure after it is run.
	 * @param <T> the type of the output
	 * @return the output of the procedure.
	 * @throws SQLException on database error.
	 */
	static <T> T updateWithOutput(
			String callPreparationSql,
			ThrowingConsumer<CallableStatement,SQLException> setParameters,
			ThrowingFunction<CallableStatement, T, SQLException> getOutput) throws SQLException {
		Connection con = null;
		CallableStatement procedure = null;
		try {
			// Setup the stored procedure.
			con = Common.getConnection();
			Common.beginTransaction(con);
			procedure = prepareCallable(con, callPreparationSql);

			// Apply the parameter setting function.
			setParameters.accept(procedure);

			// Run the procedure.
			procedure.executeUpdate();

			// Apply the output getting function and return the result.
			T output = getOutput.accept(procedure);
			Common.endTransaction(con);
			return output;
		} catch (SQLException e) {
			log.warn("updateWithOutput", "Rolling back update & rethrowing SQLException", e);
			Common.doRollback(con);
			throw e;
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}
	}

	/**
	 * Runs some SQL code. Useful for testing purposes.
	 * @param sql the sql to run.
	 * @throws SQLException on database error.
	 */
	public static void execute(String sql) throws SQLException {
		Connection con = null;
		Statement statement = null;
		try {
			con = Common.getConnection();
			Common.beginTransaction(con);
			statement = con.createStatement();
			statement.execute(sql);
			Common.endTransaction(con);
		} catch (SQLException e) {
			log.warn("execute", "Rolling back execute & rethrowing SQLException", e);
			Common.doRollback(con);
			throw e;
		} finally {
			Common.safeClose(con);
			Common.safeClose(statement);
		}
	}

	/**
	 * Executes the given statement and consumes any result sets it returns. This is useful for SELECT-based stored
	 * procedures that are invoked primarily for their side effects but still return a row.
	 *
	 * @implNote The implementation iterates over every returned result set/update-count pair. If a procedure accidentally
	 * returns a very large result set this method will fully iterate it, so procedures that are meant to be
	 * side-effect-only should keep their result sets empty or tiny.
	 *
	 * @param ps The prepared statement to execute.
	 * @throws SQLException on database error.
	 */
	public static void executeAndDrain(PreparedStatement ps) throws SQLException {
		boolean hasResults = ps.execute();
		int updateCount = ps.getUpdateCount();
		do {
			if (hasResults) {
				try (ResultSet rs = ps.getResultSet()) {
					if (rs != null) {
						while (rs.next()) {
							// Drain rows to allow PostgreSQL to free resources.
						}
					}
				}
			}
			hasResults = ps.getMoreResults();
			updateCount = ps.getUpdateCount();
		} while (hasResults || updateCount != -1);
	}

	public static <E extends Exception> void runInTransaction(ThrowingConsumer<Connection, E> work) throws SQLException, E {
		runInTransaction(con -> {
			work.accept(con);
			return null;
		});
	}

	public static <T, E extends Exception> T runInTransaction(ThrowingFunction<Connection, T, E> work) throws SQLException, E {
		Connection con = null;
		try {
			con = Common.getConnection();
			Common.beginTransaction(con);
			T result = work.accept(con);
			Common.endTransaction(con);
			return result;
		} catch (SQLException e) {
			log.warn("runInTransaction", "Rolling back transaction due to SQLException", e);
			Common.doRollback(con);
			throw e;
		} catch (RuntimeException e) {
			Common.doRollback(con);
			throw e;
		} catch (Exception e) {
			log.warn("runInTransaction", "Rolling back transaction due to exception", e);
			Common.doRollback(con);
			throw rethrowAs(e);
		} finally {
			Common.safeClose(con);
		}
	}

	@SuppressWarnings("unchecked")
	private static <E extends Exception> E rethrowAs(Exception e) throws E {
		throw (E) e;
	}


	/**
	 * This method performs an update to the database given a connection.
	 * This method is NOT responsible for closing the connection or doing rollbacks.
	 * @param con The connection to use for performing the db update.
	 * @param callPreparationSql a String that wil be passed to JDBC Connection.prepareCall
	 * @param setParameters the action to perform on the procedure (setting parameters)
	 * @throws SQLException
	 */
	protected static void updateUsingConnection(
			Connection con,
			String callPreparationSql,
			ThrowingConsumer<CallableStatement,SQLException> setParameters) throws SQLException {

		CallableStatement procedure = null;

		try {
			procedure = prepareCallable(con, callPreparationSql);
			setParameters.accept(procedure);
			procedure.executeUpdate();
		} finally {
			Common.safeClose(procedure);
		}
	}

	/**
	 * IMPORTANT: This method must only be used for queries and not updates. No transaction will be started and no rollback will
	 * occur on failure.
	 * This function accepts a ResultsConsumer lambda and queries the database. It handles the opening and closing
	 * of the connection and closing other resources.
	 * @param resultsConsumer The query lambda (Connection, CallableStatement, ResultSet) -> T
	 * @param <T> The type parameter that determines what exactly we are querying for and returning.
	 * @return Whatever we queried for and assembled from our ResultSet.
	 * @throws SQLException
	 */
	public static <T> T query(
			String callPreparationSql,
			ThrowingConsumer<CallableStatement,SQLException> setParameters,
			 ResultsConsumer<T> resultsConsumer) throws SQLException {
		Connection con = null;
		try {
			con = Common.getConnection();
			return queryUsingConnection(con, callPreparationSql, setParameters, resultsConsumer);
		} finally {
			Common.safeClose(con);
		}
	}

	/**
	 * IMPORTANT: This method must only be used for queries and not updates. No transaction will be started and no rollback will
	 * occur on failure.
	 * This function accepts a ResultsConsumer lambda and queries the database. It handles the opening and closing
	 * of the connection and closing other resources.
	 * @param setParameters lambda responsible for setting arguments to the procedure. (prepareCall is already done for you)
	 * @param resultsConsumer lambda responsible for converting the results to the desired type.
	 * @param <T> The type parameter that determines what exactly we are querying for and returning.
	 * @return Whatever we queried for and assembled from our ResultSet.
	 * @throws SQLException
	 */
	protected static <T> T queryUsingConnection(Connection con, String callPreparationSql, ThrowingConsumer<CallableStatement,SQLException> setParameters, ResultsConsumer<T> resultsConsumer) throws SQLException {
		CallableStatement procedure=null;
		ResultSet results = null;
		try {
			procedure = prepareCallable(con, callPreparationSql);
			setParameters.accept(procedure);
			results = procedure.executeQuery();
			return resultsConsumer.query(results);
		} finally {
			Common.safeClose(procedure);
			Common.safeClose(results);
		}
	}

	protected static <T> T queryUsingConnectionKeepConnection(
			String callPreparationSql,
			Connection con,
			ThrowingConsumer<CallableStatement,SQLException> setParameters,
			ThrowingBiFunction<Connection, ResultSet, T, SQLException> connectionResultsFunction) throws SQLException
	{
		CallableStatement procedure=null;
		ResultSet results = null;
		try {
			procedure = prepareCallable(con, callPreparationSql);
			setParameters.accept(procedure);
			results = procedure.executeQuery();
			return connectionResultsFunction.accept(con, results);
		} finally {
			Common.safeClose(procedure);
			Common.safeClose(results);
		}
	}

	/**
	 * Cleans up the database connection pool. This class must be reinitialized after this is called.
	 */
	public static void release() {
		if(dataPool != null) {
			dataPool.close();
		}
	}

	protected static synchronized void safeClose(Statement statement) {
		try {
			if (statement!=null && !statement.isClosed()) {
				statement.close();
			}
		} catch (SQLException e) {
			log.error("safeClose", e);
		}
	}

	protected static void safeClose(NamedParameterStatement statement) {
		try {
			if (statement!=null) {
				statement.close();
			}
		} catch (SQLException e) {
			log.error("safeClose", e);
		}
	}

	/**
	 * Closes a result set
	 * @param r The result set close
	 */
	protected static void safeClose(ResultSet r) {
		try {
			if (r!=null && !r.isClosed()) {
				r.close();
			}
		} catch (SQLException e) {
			log.error("safeClose", e);
		}
	}

	/**
	 * Method which safely closes a connection pool connection
	 * and doesn't raise any errors
	 * @param c The connection to safely close
	 */
	protected static synchronized void safeClose(Connection c) {
		try {
			if(c != null && !c.isClosed()) {
				c.close();
				--connectionsOpened;
			}
		} catch (SQLException e) {
			// Do nothing
			log.error("safeClose", e);
		}
		checkConnectionsCount();
	}
}
