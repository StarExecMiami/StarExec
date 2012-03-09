package org.starexec.servlets;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.jboss.resteasy.spi.HttpResponse;

import org.starexec.app.Starexec;
import org.starexec.constants.R;
import org.starexec.data.database.*;
import org.starexec.data.to.*;
import org.starexec.util.ArchiveUtil;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;
import org.starexec.util.Validator;

/**
 * Handles requests to download files from starexec
 * @author Skylar Stark & Tyler Jensen
 */
@SuppressWarnings("serial")
public class Download extends HttpServlet {
	private static final Logger log = Logger.getLogger(Download.class);	 
    
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	}
	
	@Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    	User u = SessionUtil.getUser(request);
    	String fileName = null;
    	
    	try {
    		
			if (false == validateRequest(request)) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "the download request was invalid");
				return;
			}
			
			if (request.getParameter("type").equals("solver")) {
				Solver s = Solvers.get(Integer.parseInt(request.getParameter("id")));
				fileName = handleSolver(s, u.getId(), u.getArchiveType(), response);
			} else if (request.getParameter("type").equals("bench")) {
				Benchmark b = Benchmarks.get(Integer.parseInt(request.getParameter("id")));
				fileName = handleBenchmark(b, u.getId(), u.getArchiveType(), response);
			} else if (request.getParameter("type").equals("jp_output")) {
				JobPair jp = Jobs.getPair(Integer.parseInt(request.getParameter("id")));
				fileName = handlePairOutput(jp, u.getId(), u.getArchiveType(), response);				
			}
		
			// Redirect based on success/failure
			if(fileName != null) {
				response.sendRedirect("/starexec/secure/files/" + fileName);
			} else {
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "failed to process file for download.");	
			}									
		} catch (Exception e) {
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
			log.error(e.getMessage(), e);
		}
	}	
    
	/**
	 * Processes a solver to be downloaded. The solver is archived in a format that is
	 * specified by the user, given a random name, and placed in a secure folder on the server.
	 * @param s the solver to be downloaded
	 * @param userId the id of the user making the download request
	 * @param format the user's preferred archive type
	 * @return the filename of the created archive
	 * @author Skylar Stark
	 */
    private static String handleSolver(Solver s, int userId, String format, HttpServletResponse response) throws IOException {
		// If we can see this solver AND the solver is downloadable...
		if (Permissions.canUserSeeSolver(s.getId(), userId) && s.isDownloadable()) {
			// Path is /starexec/WebContent/secure/files/{random name}.{format}
			// Create the file so we can use it
			String fileName = s.getName() + "_(" + UUID.randomUUID().toString() + ")" + format;
			File uniqueDir = new File(new File(R.STAREXEC_ROOT, R.DOWNLOAD_FILE_DIR), fileName);
			uniqueDir.createNewFile();
			ArchiveUtil.createArchive(new File(s.getPath()), uniqueDir, format);
			
			return fileName;
		}
		else {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "you do not have permission to download this solver.");
		}
    	
    	return null;
    }
    
	/**
	 * Processes a benchmark to be downloaded. The benchmark is archived in a format that is
	 * specified by the user, given a random name, and placed in a secure folder on the server.
	 * @param b the benchmark to be downloaded
	 * @param userId the id of the user making the download request
	 * @param format the user's preferred archive type
	 * @return the filename of the created archive
	 * @author Skylar Stark
	 */
    private static String handleBenchmark(Benchmark b, int userId, String format, HttpServletResponse response) throws IOException {
		// If we can see this benchmark AND the benchmark is downloadable...
		if (Permissions.canUserSeeBench(b.getId(), userId) && b.isDownloadable()) {
			// Path is /starexec/WebContent/secure/files/{random name}.{format}
			// Create the file so we can use it
			String fileName = b.getName() + "_(" + UUID.randomUUID().toString() + ")" + format;
			File uniqueDir = new File(new File(R.STAREXEC_ROOT, R.DOWNLOAD_FILE_DIR), fileName);
			uniqueDir.createNewFile();
			ArchiveUtil.createArchive(new File(b.getPath()), uniqueDir, format);
			
			return fileName;
		}
		else {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "you do not have permission to download this benchmark.");
		}
    	
    	return null;
    }
    
    /**
	 * Processes a job pair's output to be downloaded. The output is archived in a format that is
	 * specified by the user, given a random name, and placed in a secure folder on the server.
	 * @param jp the job pair whose output is to be downloaded
	 * @param userId the id of the user making the download request
	 * @param format the user's preferred archive type
	 * @return the filename of the created archive
	 * @author Tyler Jensen
	 */
    private static String handlePairOutput(JobPair jp, int userId, String format, HttpServletResponse response) throws IOException {    	
    	Job j = Jobs.getShallow(jp.getJobId());

		// If the user can actually see the job the pair is apart of
		if (Permissions.canUserSeeJob(j.getId(), userId)) {
			// Path is /starexec/WebContent/secure/files/{random name}.{format}
			// Create the file so we can use it
			String fileName = UUID.randomUUID().toString() + format;
			File uniqueDir = new File(new File(R.STAREXEC_ROOT, R.DOWNLOAD_FILE_DIR), fileName);
			uniqueDir.createNewFile();
			
			// The job's output is expected to be in JOB_OUTPUT_DIR/{owner's ID}/{job id}/{pair id}
			String outputPath = String.format("%s/%d/%d/%d", R.JOB_OUTPUT_DIR, j.getUserId(), j.getId(), jp.getId());
			ArchiveUtil.createArchive(new File(outputPath), uniqueDir, format);
			
			return fileName;
		}
		else {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "you do not have permission to download this job pair's output.");
		}
    	
    	return null;
    }
    
    /**
     * Validates the download request to make sure the requested data is of the right format
     * 
     * @return true iff the request is valid
     * @author Skylar Stark
     */
    public static boolean validateRequest(HttpServletRequest request) {
    	try {
    		if (!Util.paramExists("type", request)
    			|| !Util.paramExists("id", request)) {
    			return false;
    		}
    		
    		if (!Validator.isValidInteger(request.getParameter("id"))) {
    			return false;
    		}
    		
    		// The requested type should be a solver, benchmark, or job pair output
    		if (!(request.getParameter("type").equals("solver") ||
    				request.getParameter("type").equals("bench") ||
    				request.getParameter("type").equals("jp_output"))) {
    			return false;
    		}
    		
    		return true;
    	} catch (Exception e) {
    		log.warn(e.getMessage(), e);
    	}
    	
    	return false;
    }
}
