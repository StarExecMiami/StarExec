package org.starexec.app;

import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.apache.log4j.Logger;
import org.starexec.constants.R;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Cluster;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Queues;
import org.starexec.data.database.Requests;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.Spaces;
import org.starexec.data.database.Users;
import org.starexec.data.security.JobSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.CommunityRequest;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.JobSpace;
import org.starexec.data.to.Permission;
import org.starexec.data.to.Queue;
import org.starexec.data.to.Solver;
import org.starexec.data.to.SolverComparison;
import org.starexec.data.to.SolverStats;
import org.starexec.data.to.Space;
import org.starexec.data.to.User;
import org.starexec.data.to.Website;
import org.starexec.data.to.WorkerNode;
import org.starexec.data.to.pipelines.JoblineStage;
import org.starexec.exceptions.StarExecDatabaseException;
import org.starexec.test.integration.TestResult;
import org.starexec.test.integration.TestSequence;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;
import org.starexec.util.dataStructures.TreeNode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.Expose;
import com.google.gson.Gson;

/**
 * Holds all helper methods and classes for our restful web services
 */
public class RESTHelpers {
	private static final Logger log = Logger.getLogger(RESTHelpers.class);
	private static final int PAGE_SPACE_EXPLORER = 1;
	private static final int PAGE_USER_DETAILS = 2;
	private static Gson gson = new Gson();

	// Job pairs and nodes aren't technically a primitive class according to how
	// we've discussed primitives, but to save time and energy I've included
	// them here as such
	public enum Primitive {
		JOB, USER, SOLVER, BENCHMARK, SPACE, JOB_PAIR, JOB_STATS, NODE
	}

	private static final String SEARCH_QUERY = "sSearch";
	private static final String SORT_DIRECTION = "sSortDir_0";
	private static final String SYNC_VALUE = "sEcho";
	private static final String SORT_COLUMN = "iSortCol_0";
	private static final String SORT_COLUMN_OVERRIDE = "sort_by";
	private static final String SORT_COLUMN_OVERRIDE_DIR = "sort_dir";

	private static final String STARTING_RECORD = "iDisplayStart";
	private static final String RECORDS_PER_PAGE = "iDisplayLength";
	private static final String TOTAL_RECORDS = "iTotalRecords";
	private static final String TOTAL_RECORDS_AFTER_QUERY = "iTotalDisplayRecords";

	private static final int EMPTY = 0;
	private static final int ASC = 0;
	private static final int DESC = 1;

	/**
	 * Takes in a list of spaces and converts it into a list of JSTreeItems
	 * suitable for being displayed on the client side with the jsTree plugin.
	 * 
	 * @param spaceList The list of spaces to convert
	 * @param userID The ID of the user making this request, which is used to tell whether nodes are leaves or not
	 * @return List of JSTreeItems to be serialized and sent to client
	 * @author Tyler Jensen
	 */
	protected static List<JSTreeItem> toSpaceTree(List<Space> spaceList,
			int userID) {
		List<JSTreeItem> list = new LinkedList<JSTreeItem>();
		for (Space space : spaceList) {
			JSTreeItem t;

			if (Spaces.getCountInSpace(space.getId(), userID, true) > 0) {
				t = new JSTreeItem(space.getName(), space.getId(), "closed",
						R.SPACE);
			} else {
				t = new JSTreeItem(space.getName(), space.getId(), "leaf",
						R.SPACE);
			}

			list.add(t);
		}

		return list;
	}

	/**
	 * Takes in a list of spaces and converts it into a list of JSTreeItems
	 * suitable for being displayed on the client side with the jsTree plugin.
	 * 
	 * @param jobSpaceList
	 * @return List of JSTreeItems to be serialized and sent to client
	 * @author Tyler Jensen
	 */
	protected static List<JSTreeItem> toJobSpaceTree(List<JobSpace> jobSpaceList) {
		List<JSTreeItem> list = new LinkedList<JSTreeItem>();

		for (JobSpace space : jobSpaceList) {
			JSTreeItem t;

			if (Spaces.getCountInJobSpace(space.getId()) > 0) {
				log.debug("the max stages for this job space is "+space.getMaxStages());
				t = new JSTreeItem(space.getName(), space.getId(), "closed",
						R.SPACE,space.getMaxStages());
				
			} else {
				log.debug("the max stages for this job space is "+space.getMaxStages());
				t = new JSTreeItem(space.getName(), space.getId(), "leaf",
						R.SPACE,space.getMaxStages());
			}
			
			list.add(t);
		}

		return list;
	}

	/**
	 * Takes in a list of worker nodes and converts it into a list of
	 * JSTreeItems suitable for being displayed on the client side with the
	 * jsTree plugin.
	 * 
	 * @param nodes
	 *            The list of worker nodes to convert
	 * @return List of JSTreeItems to be serialized and sent to client
	 * @author Tyler Jensen
	 */
	protected static List<JSTreeItem> toNodeList(List<WorkerNode> nodes) {
		List<JSTreeItem> list = new LinkedList<JSTreeItem>();

		for (WorkerNode n : nodes) {
			// Only take the first part of the host name, the full one is too
			// int to display on the client
			JSTreeItem t = new JSTreeItem(n.getName().split("\\.")[0],
					n.getId(), "leaf",
					n.getStatus().equals("ACTIVE") ? "enabled_node"
							: "disabled_node");
			list.add(t);
		}

		return list;
	}

	/**
	 * Takes in a list of queues and converts it into a list of JSTreeItems
	 * suitable for being displayed on the client side with the jsTree plugin.
	 * 
	 * @param queues
	 *            The list of queues to convert
	 * @return List of JSTreeItems to be serialized and sent to client
	 * @author Tyler Jensen
	 */
	protected static List<JSTreeItem> toQueueList(List<Queue> queues) {
		List<JSTreeItem> list = new LinkedList<JSTreeItem>();
		JSTreeItem t;
		for (Queue q : queues) {
			//status might be null, so we don't want a null pointer in that case
			String status=q.getStatus();
			if (status==null) {
				status="";
			}
			if (Queues.getNodes(q.getId()).size() > 0) {
				t = new JSTreeItem(q.getName(), q.getId(), "closed", 
						status.equals("ACTIVE") ? "active_queue"
						: "inactive_queue");
			} else {
				t = new JSTreeItem(q.getName(), q.getId(), "leaf",
						status.equals("ACTIVE") ? "active_queue"
						: "inactive_queue");
			}

			list.add(t);
		}

		return list;
	}

	/**
	 * Takes in a list of spaces (communities) and converts it into a list of
	 * JSTreeItems suitable for being displayed on the client side with the
	 * jsTree plugin.
	 * 
	 * @param communities
	 *            The list of communities to convert
	 * @return List of JSTreeItems to be serialized and sent to client
	 * @author Tyler Jensen
	 */
	protected static List<JSTreeItem> toCommunityList(List<Space> communities) {
		List<JSTreeItem> list = new LinkedList<JSTreeItem>();

		for (Space space : communities) {
			JSTreeItem t = new JSTreeItem(space.getName(), space.getId(),
					"leaf", R.SPACE);
			list.add(t);
		}

		return list;
	}

	/**
	 * Represents a node in jsTree tree with certain attributes used for
	 * displaying the node and obtaining information about the node.
	 * 
	 * @author Tyler Jensen
	 */
	@SuppressWarnings("unused")
	protected static class JSTreeItem {
		private String data;
		private JSTreeAttribute attr;
		private List<JSTreeItem> children;
		private String state;

		public JSTreeItem(String name, int id, String state, String type) {
			this.data = name;
			this.attr = new JSTreeAttribute(id, type);
			this.state = state;
			this.children = new LinkedList<JSTreeItem>();
		}
		
		public JSTreeItem(String name, int id, String state, String type, int maxStages) {
			this.data = name;
			this.attr = new JSTreeAttribute(id, type,maxStages);
			this.state = state;
			this.children = new LinkedList<JSTreeItem>();
		}

		public JSTreeItem(String name, int id, String state, String type, int maxStages, String cLass) {
			this.data = name;
			this.attr = new JSTreeAttribute(id, type,maxStages, cLass);
			this.state = state;
			this.children = new LinkedList<JSTreeItem>();
		}

		public List<JSTreeItem> getChildren() {
			return children;
		}

		public void addChild(JSTreeItem child) {
			children.add(child);
		}
	}

	/**
	 * An attribute of a jsTree node which holds the node's id so that it can be
	 * passed aint to other ajax methods.
	 * 
	 * @author Tyler Jensen
	 */
	@SuppressWarnings("unused")
	protected static class JSTreeAttribute {
		private int id;
		private String rel;
		private boolean global;
		private int defaultQueueId;
		private int maxStages;
		// called cLass to bypass Java's class keyword. gson will lowercase the L
		private String cLass;

		
		private void init(int id, String type,int maxStages, String cLass) {
			this.id = id;
			this.rel = type;
			this.maxStages=maxStages;
			this.cLass = cLass;
			if (type.equals("active_queue") || type.equals("inactive_queue")) {
				this.global = Queues.isQueueGlobal(id);
			}
			this.defaultQueueId = Cluster.getDefaultQueueId();
		}
		
		public JSTreeAttribute(int id, String type) {
			init(id,type,0, null);
		}
		
		public JSTreeAttribute(int id, String type,int maxStages) {
			init(id,type,maxStages, null);
		}

		public JSTreeAttribute(int id, String type,int maxStages, String cLass) {
			init(id,type,maxStages, cLass);
		}
	}

	/**
	 * Represents a space and a user's permission for that space. This is purely
	 * a helper class so we can easily read the information via javascript on
	 * the client.
	 * 
	 * @author Tyler Jensen & Todd Elvers
	 */
	protected static class SpacePermPair {
		@Expose
		private Space space;
		@Expose
		private Permission perm;

		public SpacePermPair(Space s, Permission p) {
			this.space = s;
			this.perm = p;
		}
	}

	/**
	 * Represents community details including the requesting user's permissions
	 * for the community aint with the community's leaders. Permissions are used
	 * so the client side can determine what actions a user can take on the
	 * community
	 * 
	 * @author Tyler Jensen
	 */
	protected static class CommunityDetails {
		@Expose
		private Space space;
		@Expose
		private Permission perm;
		@Expose
		private List<User> leaders;
		@Expose
		private List<Website> websites;
		
		@Expose
		private Boolean isMember;
		public CommunityDetails(Space s, Permission p, List<User> leaders,
				List<Website> websites,Boolean member) {
			this.space = s;
			this.perm = p;
			this.leaders = leaders;
			this.websites = websites;
			this.isMember = member;
		}
	}

	/**
	 * Represents permission details for a given space and user
	 * 
	 * @author Wyatt Kaiser
	 */
	protected static class PermissionDetails {
		@Expose
		private Permission perm;
		@Expose
		private Space space;
		@Expose
		private User user;
		@Expose
		private User requester;
		@Expose
		private boolean isCommunity;

		public PermissionDetails(Permission p, Space s, User u, User r, boolean c) {
			this.perm = p;
			this.space = s;
			this.user = u;
			this.requester = r;
			this.isCommunity = c;
		}
	}

	
	/**
	 * Validate the parameters of a request for a DataTable page
	 * 
	 * @param type the primitive type being queried for
	 * @param request the object containing the parameters to validate
	 * @return an attribute map containing the valid parameters parsed from the request object,<br>
	 *         or null if parameter validation fails
	 * @author Todd Elvers
	 */
	private static HashMap<String, Integer> getAttrMap(Primitive type, HttpServletRequest request) {
		HashMap<String, Integer> attrMap = new HashMap<String, Integer>();

		try {
			// Parameters from the DataTable object
			String iDisplayStart = (String) request.getParameter(STARTING_RECORD);
			String iDisplayLength = (String) request.getParameter(RECORDS_PER_PAGE);
			String sEcho = (String) request.getParameter(SYNC_VALUE);
			String iSortCol = (String) request.getParameter(SORT_COLUMN); 
			String sDir = (String) request.getParameter(SORT_DIRECTION);
			String sSearch = (String) request.getParameter(SEARCH_QUERY);
	       
			// Validates the starting record, the number of records per page,
			// and the sync value
			if (Util.isNullOrEmpty(iDisplayStart)
					|| Util.isNullOrEmpty(iDisplayLength)
					|| Util.isNullOrEmpty(sEcho)
					|| Integer.parseInt(iDisplayStart) < 0
					|| Integer.parseInt(sEcho) < 0) {
				return null;
			}

			if (Util.isNullOrEmpty(iSortCol)) {
				// Allow jobs datatable to have a sort column null, then set
				// the column to sort by column 5, which doesn't exist on the screen but represents the creation date
				if(type == Primitive.JOB){
					attrMap.put(SORT_COLUMN, 5);
				} else {
					return null;
				}
			} else {
				int sortColumnIndex = Integer.parseInt(iSortCol);
				attrMap.put(SORT_COLUMN, sortColumnIndex);
				switch (type) {
					case JOB:
						if (sortColumnIndex < 0 || sortColumnIndex > 5) return null;
						break;
					case JOB_PAIR:
						if (sortColumnIndex < 0 || sortColumnIndex > 8) return null;
						break;
					case JOB_STATS:
						if (sortColumnIndex < 0 || sortColumnIndex > 6) return null;
						break;
					case USER:
						if (sortColumnIndex < 0 || sortColumnIndex > 3) return null;
						break;
					case SOLVER:
						if (sortColumnIndex < 0 || sortColumnIndex > 2) return null;
						break;
					case BENCHMARK:
						if (sortColumnIndex < 0 || sortColumnIndex > 2) return null;
						break;
					case SPACE:
						if (sortColumnIndex < 0 || sortColumnIndex > 2) return null;
						break;
				}
			}
			
			//Validates that the sort direction is specified and valid
			if (Util.isNullOrEmpty(sDir)) {
				// Only permit the jobs table to have a null sorting direction;
				// this allows for jobs to be sorted initially on their creation date
				if (type == Primitive.JOB){
					attrMap.put(SORT_DIRECTION, DESC);
				} else {
					return null;
				}
			} else {
				if (sDir.contains("asc") || sDir.contains("desc")) {
					attrMap.put(SORT_DIRECTION, (sDir.equals("asc") ? ASC : DESC));
				} else {
					return null;
				}
			}
			// Depending on if the search/filter is empty or not, this will be 0 or 1
			if (Util.isNullOrEmpty(sSearch)) {
				attrMap.put(SEARCH_QUERY, EMPTY);
			} else {
				attrMap.put(SEARCH_QUERY, 1);
			}

			// The request is valid if it makes it this far;
			// Finish the validation by adding the remaining attributes to the
			// map
			attrMap.put(RECORDS_PER_PAGE, Integer.parseInt(iDisplayLength));
			attrMap.put(STARTING_RECORD, Integer.parseInt(iDisplayStart));
			attrMap.put(SYNC_VALUE, Integer.parseInt(sEcho));
			attrMap.put(TOTAL_RECORDS, EMPTY);
			attrMap.put(TOTAL_RECORDS_AFTER_QUERY, EMPTY);

			return attrMap;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}

		return null;
	}
	
	/**
	 * Validate the parameters of a request for a DataTable page
	 * 
	 * @param type
	 *            the primitive type being queried for
	 * @param request
	 *            the object containing the parameters to validate
	 * @return an attribute map containing the valid parameters parsed from the
	 *         request object,<br>
	 *         or null if parameter validation fails
	 * @author Todd Elvers
	 */
	private static HashMap<String, Integer> getAttrMapQueueReservation(
			HttpServletRequest request) {
		HashMap<String, Integer> attrMap = new HashMap<String, Integer>();

		try {
			// Parameters from the DataTable object
			String iDisplayStart = (String) request.getParameter(STARTING_RECORD);
			String iDisplayLength = (String) request.getParameter(RECORDS_PER_PAGE);
			String sEcho = (String) request.getParameter(SYNC_VALUE);
			String iSortCol = (String) request.getParameter(SORT_COLUMN); 
			String sDir = (String) request.getParameter(SORT_DIRECTION);
			String sSearch = (String) request.getParameter(SEARCH_QUERY);

			// Validates the starting record, the number of records per page,
			// and the sync value
			if (Util.isNullOrEmpty(iDisplayStart)
					|| Util.isNullOrEmpty(iDisplayLength)
					|| Util.isNullOrEmpty(sEcho)
					|| Integer.parseInt(iDisplayStart) < 0
					|| Integer.parseInt(sEcho) < 0) {
				return null;
			}

			if (Util.isNullOrEmpty(iSortCol)) {
				return null;
			} else {
				int sortColumnIndex = Integer.parseInt(iSortCol);
				attrMap.put(SORT_COLUMN, sortColumnIndex);
			}
			if (Util.isNullOrEmpty(sDir)) {
				attrMap.put(SORT_DIRECTION, DESC);
			} else {
				if (sDir.contains("asc") || sDir.contains("desc")) {
					attrMap.put(SORT_DIRECTION, (sDir.equals("asc") ? ASC
							: DESC));
				} else {
					return null;
				}
			}
			// Depending on if the search/filter is empty or not, this will be 0
			// or 1
			if (Util.isNullOrEmpty(sSearch)) {
				attrMap.put(SEARCH_QUERY, EMPTY);
			} else {
				attrMap.put(SEARCH_QUERY, 1);
			}

			// The request is valid if it makes it this far;
			// Finish the validation by adding the remaining attributes to the
			// map
			attrMap.put(RECORDS_PER_PAGE, Integer.parseInt(iDisplayLength));
			attrMap.put(STARTING_RECORD, Integer.parseInt(iDisplayStart));
			attrMap.put(SYNC_VALUE, Integer.parseInt(sEcho));
			attrMap.put(TOTAL_RECORDS, EMPTY);
			attrMap.put(TOTAL_RECORDS_AFTER_QUERY, EMPTY);

			return attrMap;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}

		return null;
	}

	/**
	 * Validate the parameters of a request for a DataTable page
	 * 
	 * @param type
	 *            the primitive type being queried for
	 * @param request
	 *            the object containing the parameters to validate
	 * @return an attribute map containing the valid parameters parsed from the
	 *         request object,<br>
	 *         or null if parameter validation fails
	 * @author Todd Elvers
	 */
	private static HashMap<String, Integer> getAttrMapCluster(String type,
			HttpServletRequest request) {
		HashMap<String, Integer> attrMap = new HashMap<String, Integer>();

		try {
			// Parameters from the DataTable object
			String iDisplayStart = (String) request.getParameter(STARTING_RECORD); 
			String iDisplayLength = (String) request.getParameter(RECORDS_PER_PAGE);
			String sEcho = (String) request.getParameter(SYNC_VALUE); 
			String iSortCol = (String) request.getParameter(SORT_COLUMN);
			String sDir = (String) request.getParameter(SORT_DIRECTION); 
			String sSearch = (String) request.getParameter(SEARCH_QUERY); 

			// Validates the starting record, the number of records per page,
			// and the sync value
			if (Util.isNullOrEmpty(iDisplayStart)
					|| Util.isNullOrEmpty(iDisplayLength)
					|| Util.isNullOrEmpty(sEcho)
					|| Integer.parseInt(iDisplayStart) < 0
					|| Integer.parseInt(sEcho) < 0) {
				return null;
			}

			if (Util.isNullOrEmpty(iSortCol)) {
				return null;
			} else {
				int sortColumnIndex = Integer.parseInt(iSortCol);
				attrMap.put(SORT_COLUMN, sortColumnIndex);
			}
			if (Util.isNullOrEmpty(sDir)) {
				attrMap.put(SORT_DIRECTION, DESC);
			} else {
				if (sDir.contains("asc") || sDir.contains("desc")) {
					attrMap.put(SORT_DIRECTION, (sDir.equals("asc") ? ASC
							: DESC));
				} else {
					return null;
				}
			}
			// Depending on if the search/filter is empty or not, this will be 0 or 1
			if (Util.isNullOrEmpty(sSearch)) {
				attrMap.put(SEARCH_QUERY, EMPTY);
			} else {
				attrMap.put(SEARCH_QUERY, 1);
			}

			// The request is valid if it makes it this far;
			// Finish the validation by adding the remaining attributes to the
			// map
			attrMap.put(RECORDS_PER_PAGE, Integer.parseInt(iDisplayLength));
			attrMap.put(STARTING_RECORD, Integer.parseInt(iDisplayStart));
			attrMap.put(SYNC_VALUE, Integer.parseInt(sEcho));
			attrMap.put(TOTAL_RECORDS, EMPTY);
			attrMap.put(TOTAL_RECORDS_AFTER_QUERY, EMPTY);

			return attrMap;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}

		return null;
	}

	
	
    /**
     * Add tag for the image representing a link that will popout.
     *
     * @param sb the StringBuilder to add the tag with.
     * 
     * @author Aaron Stump 
     */
    public static void addImg(StringBuilder sb) {
	sb.append("<img class=\"extLink\" src=\""+Util.docRoot("images/external.png\"/></a>"));
    }

	/**

	 * Returns the HTML representing a job pair's status
	 * 
	 * @param statType 'asc' or 'desc'
	 * @param value  a job pair's completePairs, pendingPairs, or errorPairs  variable
	 * @param percentage  a job pair's totalPairs variable
	 * @return HTML representing a job pair's status
	 * @author Todd Elvers
	 */
	public static String getPercentStatHtml(String statType, int value,
			Boolean percentage) {
		StringBuilder sb = new StringBuilder();
		sb.append("<p class=\"stat ");
		sb.append(statType);
		sb.append("\">");
		sb.append(value);
		if (percentage) {
			sb.append(" %");
		}
		sb.append("</p>");
		return sb.toString();
	}
	
	/**
	 * 
	 * @param type
	 *            either queue or node
	 * @param id
	 *            the id of the queue/node
	 * @param userId
	 *            the id of the user that is accessing the page
	 * 
	 * @return the next page of job_pairs for the cluster status page
	 * @author Wyatt Kaiser
	 */
	protected static JsonObject getNextDataTablesPageForClusterExplorer(String type, int id, int userId, HttpServletRequest request) {
		return getNextDataTablesPageCluster(type, id, userId, request);
	}

	protected static JsonObject getNextDataTablesPageForAdminExplorer(Primitive type, HttpServletRequest request) {
		return getNextDataTablesPageAdmin(type, request);
	}

	/**
	 * Gets the next page of entries for a DataTable object
	 * 
	 * @param type
	 *            the kind of primitives to query for
	 * @param id
	 *            either the id of the space to get the primitives from, or the
	 *            id of the job to get job pairs for
	 * @param request
	 *            the object containing all the DataTable parameters
	 * @return a JSON object representing the next page of primitives to return
	 *         to the client,<br>
	 *         or null if the parameters of the request fail validation
	 * @author Todd Elvers
	 */

	protected static JsonObject getNextDataTablesPageForSpaceExplorer(Primitive type, int id, HttpServletRequest request) {
		return getNextDataTablesPage(type, id, request, PAGE_SPACE_EXPLORER,false);
	}

	protected static JsonObject getNextDataTablesPageForUserDetails(Primitive type, int id, HttpServletRequest request, boolean recycled) {
		return getNextDataTablesPage(type, id, request, PAGE_USER_DETAILS, recycled);
	}
	
	/**
	 * Gets the next page of job pairs as a JsonObject in the gien jobSpaceId, with info populated from the given stage.
	 * 
	 * @param jobId The ID of the job
	 * @param jobSpaceId The ID of the job space
	 * @param request
	 * @param wallclock True to use wallclock time, false to use CPU time
	 * @param syncResults If true, excludes job pairs for which the benchmark has not been worked on by every solver in the space
	 * @param stageNumber If <=0, gets the primary stage
	 * @return
	 */
	public static JsonObject getNextDataTablesPageOfPairsInJobSpace(int jobId, int jobSpaceId,HttpServletRequest request, boolean wallclock, boolean syncResults, int stageNumber) {
		log.debug("beginningGetNextDataTablesPageOfPairsInJobSpace with stage = " +stageNumber);
		int totalJobPairs = Jobs.getJobPairCountInJobSpaceByStage(jobSpaceId,stageNumber);

		if (totalJobPairs>R.MAXIMUM_JOB_PAIRS) {
			//there are too many job pairs to display quickly, so just don't query for them
			JsonObject ob= new JsonObject();
			ob.addProperty("maxpairs", true);
			return ob;
		}
		HashMap<String,Integer> attrMap=RESTHelpers.getAttrMap(Primitive.JOB_PAIR,request);

		if (null==attrMap) {
			return null;
		}
        String sortOverride = request.getParameter(SORT_COLUMN_OVERRIDE);
        if (sortOverride!=null) {
        	attrMap.put(SORT_COLUMN, Integer.parseInt(sortOverride));
        	if (Boolean.parseBoolean(request.getParameter(SORT_COLUMN_OVERRIDE_DIR))) {
            	attrMap.put(SORT_DIRECTION, ASC);

        	} else {
            	attrMap.put(SORT_DIRECTION, ASC+1);

        	}
        }
		
    	log.debug("the new sort column is " +attrMap.get(SORT_COLUMN));

        
		List<JobPair> jobPairsToDisplay = new LinkedList<JobPair>();
		int totalPairsAfterQuery=0;
		// Retrieves the relevant Job objects to use in constructing the JSON to
		// send to the client
		int[] totals = new int[2];

		if (!syncResults) {
			//long a = System.currentTimeMillis();
			
			
			
			
			jobPairsToDisplay = Jobs.getJobPairsForNextPageInJobSpace(
	    			attrMap.get(STARTING_RECORD),						// Record to start at  
	    			attrMap.get(RECORDS_PER_PAGE), 						// Number of records to return
	    			attrMap.get(SORT_DIRECTION) == ASC ? true : false,	// Sort direction (true for ASC)
	    			attrMap.get(SORT_COLUMN), 							// Column sorted on
	    			request.getParameter(SEARCH_QUERY), 				// Search query
	    															
	    			jobSpaceId,
	    			stageNumber,
	    			wallclock,
	    			jobId
			);
			
		} else {
			log.debug("returning synchronized results");
			jobPairsToDisplay = Jobs.getSynchronizedJobPairsForNextPageInJobSpace(attrMap.get(STARTING_RECORD),
					attrMap.get(RECORDS_PER_PAGE), 
					attrMap.get(SORT_DIRECTION) == ASC ? true : false,
							attrMap.get(SORT_COLUMN),
							request.getParameter(SEARCH_QUERY),
							jobSpaceId, 
							wallclock,
							stageNumber,
							totals);
			totalJobPairs=totals[0];
			totalPairsAfterQuery=totals[1];
		}
		

		/**
    	 * Used to display the 'total entries' information at the bottom of the DataTable;
    	 * also indirectly controls whether or not the pagination buttons are toggle-able
    	 */
    	// If no search is provided, TOTAL_RECORDS_AFTER_QUERY = TOTAL_RECORDS
		if(attrMap.get(SEARCH_QUERY) == EMPTY){
			totalPairsAfterQuery=totalJobPairs;
    	} 
    	else {
    		totalPairsAfterQuery=Jobs.getJobPairCountInJobSpaceByStage(jobSpaceId, request.getParameter(SEARCH_QUERY),stageNumber);
    	}

	   return convertJobPairsToJsonObject(jobPairsToDisplay,totalJobPairs,totalPairsAfterQuery,attrMap.get(SYNC_VALUE),true,wallclock,0);
	}
	
	
	/**
	 * Gets the next page of Benchmarks that the given use can see. This includes Benchmarks the user owns,
	 * Benchmarks in public spaces, and Benchmarks in spaces the user is also in.
	 * @param userId
	 * @param request
	 * @return
	 */
	public static JsonObject getNextDataTablesPageOfBenchmarksByUser(int userId, HttpServletRequest request) {
		log.debug("called getNextDataTablesPageOfBenchmarksByUser");
		try {
			HashMap<String, Integer> attrMap = RESTHelpers.getAttrMap(
					Primitive.BENCHMARK, request);
			if (null == attrMap) {
				return null;
			}

			List<Benchmark> BenchmarksToDisplay = new LinkedList<Benchmark>();

			int totalComparisons;
			// Retrieves the relevant Job objects to use in constructing the JSON to
			// send to the client
			int[] totals = new int[2];
			BenchmarksToDisplay = Benchmarks.getBenchmarksForNextPageByUser(
							attrMap.get(STARTING_RECORD), // Record to start at
							attrMap.get(RECORDS_PER_PAGE), // Number of records to
															// return
							attrMap.get(SORT_DIRECTION) == ASC ? true : false, // Sort
																				// direction
																				// (true
																				// for
																				// ASC)
							attrMap.get(SORT_COLUMN), // Column sorted on
							request.getParameter(SEARCH_QUERY), // Search query
							userId, totals);
			
			totalComparisons = totals[0];

			/**
	    	* Used to display the 'total entries' information at the bottom of the DataTable;
	    	* also indirectly controls whether or not the pagination buttons are toggle-able
	    	*/
	    
	       attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totals[1]);
	    	
		   return convertBenchmarksToJsonObject(BenchmarksToDisplay,totalComparisons,attrMap.get(TOTAL_RECORDS_AFTER_QUERY),attrMap.get(SYNC_VALUE));
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		
		return null;
	}
	
	
	
	
	
	
	/**
	 * Gets the next page of solvers that the given use can see. This includes solvers the user owns,
	 * solvers in public spaces, and solvers in spaces the user is also in.
	 * @param userId
	 * @param request
	 * @return
	 */
	public static JsonObject getNextDataTablesPageOfSolversByUser(int userId, HttpServletRequest request) {
		
		try {
			HashMap<String, Integer> attrMap = RESTHelpers.getAttrMap(
					Primitive.SOLVER, request);
			if (null == attrMap) {
				return null;
			}

			List<Solver> solversToDisplay = new LinkedList<Solver>();

			int totalComparisons;
			// Retrieves the relevant Job objects to use in constructing the JSON to
			// send to the client
			int[] totals = new int[2];
			solversToDisplay = Solvers.getSolversForNextPageByUser(
							attrMap.get(STARTING_RECORD), // Record to start at
							attrMap.get(RECORDS_PER_PAGE), // Number of records to
															// return
							attrMap.get(SORT_DIRECTION) == ASC ? true : false, // Sort
																				// direction
																				// (true
																				// for
																				// ASC)
							attrMap.get(SORT_COLUMN), // Column sorted on
							request.getParameter(SEARCH_QUERY), // Search query
							userId, totals);
			
			totalComparisons = totals[0];

			/**
	    	* Used to display the 'total entries' information at the bottom of the DataTable;
	    	* also indirectly controls whether or not the pagination buttons are toggle-able
	    	*/
	    
	       attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totals[1]);
	    	
		   return convertSolversToJsonObject(solversToDisplay,totalComparisons,attrMap.get(TOTAL_RECORDS_AFTER_QUERY),attrMap.get(SYNC_VALUE));
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		
		return null;
	}
	
	
	/**
	 * Returns the next page of SolverComparison objects needed for a DataTables page in a job space
	 * @param jobId
	 * @param jobSpaceId
	 * @param configId1
	 * @param configId2
	 * @param request
	 * @param wallclock
	 * @return
	 */
	public static JsonObject getNextDataTablesPageOfSolverComparisonsInSpaceHierarchy(
			int jobId, int jobSpaceId, int configId1,int configId2, HttpServletRequest request, boolean wallclock, int stageNumber) {
		
		
		try {
			HashMap<String, Integer> attrMap = RESTHelpers.getAttrMap(
					Primitive.JOB_PAIR, request);
			if (null == attrMap) {
				return null;
			}

			List<SolverComparison> solverComparisonsToDisplay = new LinkedList<SolverComparison>();

			int totalComparisons;
			// Retrieves the relevant Job objects to use in constructing the JSON to
			// send to the client
			int[] totals = new int[2];
			solverComparisonsToDisplay = Jobs
					.getSolverComparisonsForNextPageByConfigInJobSpaceHierarchy(
							attrMap.get(STARTING_RECORD), // Record to start at
							attrMap.get(RECORDS_PER_PAGE), // Number of records to
															// return
							attrMap.get(SORT_DIRECTION) == ASC ? true : false, // Sort
																				// direction
																				// (true
																				// for
																				// ASC)
							attrMap.get(SORT_COLUMN), // Column sorted on
							request.getParameter(SEARCH_QUERY), // Search query
							jobId, // Parent space id
							jobSpaceId, configId1,configId2, totals,wallclock,stageNumber);
			
			totalComparisons = totals[0];

			/**
	    	* Used to display the 'total entries' information at the bottom of the DataTable;
	    	* also indirectly controls whether or not the pagination buttons are toggle-able
	    	*/
	    
	       attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totals[1]);
	    	
		   return convertSolverComparisonsToJsonObject(solverComparisonsToDisplay,totalComparisons,attrMap.get(TOTAL_RECORDS_AFTER_QUERY),attrMap.get(SYNC_VALUE),wallclock,stageNumber);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		
		return null;
		
	}

	public static JsonObject getNextDataTablesPageOfPairsByConfigInSpaceHierarchy(
			int jobId, int jobSpaceId, int configId, HttpServletRequest request,String type, boolean wallclock, int stageNumber) {
		HashMap<String, Integer> attrMap = RESTHelpers.getAttrMap(
				Primitive.JOB_PAIR, request);
		if (null == attrMap) {
			return null;
		}

		List<JobPair> jobPairsToDisplay = new LinkedList<JobPair>();
        String sortOverride = request.getParameter(SORT_COLUMN_OVERRIDE);
        if (sortOverride!=null) {
        	attrMap.put(SORT_COLUMN, Integer.parseInt(sortOverride));
        	if (Boolean.parseBoolean(request.getParameter(SORT_COLUMN_OVERRIDE_DIR))) {
            	attrMap.put(SORT_DIRECTION, ASC);

        	} else {
            	attrMap.put(SORT_DIRECTION, ASC+1);

        	}
        }
        log.debug("the new sorting order is "+attrMap.get(SORT_COLUMN));
        
		int totalJobs;
		// Retrieves the relevant Job objects to use in constructing the JSON to
		// send to the client
		jobPairsToDisplay = Jobs
				.getJobPairsForNextPageByConfigInJobSpaceHierarchy(
						attrMap.get(STARTING_RECORD), // Record to start at
						attrMap.get(RECORDS_PER_PAGE), // Number of records to
														// return
						attrMap.get(SORT_DIRECTION) == ASC ? true : false, // Sort
																			// direction
																			// (true
																			// for
																			// ASC)
						attrMap.get(SORT_COLUMN), // Column sorted on
						request.getParameter(SEARCH_QUERY), // Search query
						jobId, // Parent space id
						jobSpaceId, configId,type,wallclock,stageNumber);
		
		totalJobs = Jobs.getCountOfJobPairsByConfigInJobSpaceHierarchy(jobSpaceId,configId, type,stageNumber);

		/**
    	* Used to display the 'total entries' information at the bottom of the DataTable;
    	* also indirectly controls whether or not the pagination buttons are toggle-able
    	*/
    
       attrMap.put(TOTAL_RECORDS_AFTER_QUERY, Jobs.getCountOfJobPairsByConfigInJobSpaceHierarchy(jobSpaceId,configId, type,request.getParameter(SEARCH_QUERY),stageNumber));
    	
	   return convertJobPairsToJsonObject(jobPairsToDisplay,totalJobs,attrMap.get(TOTAL_RECORDS_AFTER_QUERY),attrMap.get(SYNC_VALUE),true,wallclock,stageNumber);
	}

	/**
	 * Gets the next page of job_pair entries for a DataTable object on cluster
	 * Status page
	 * 
	 * @param type
	 *            either queue or node
	 * @param id
	 *            the id of the queue/node to get job pairs for
	 * @param request
	 *            the object containing all the DataTable parameters
	 * @return a JSON object representing the next page of primitives to return
	 *         to the client,<br>
	 *         or null if the parameters of the request fail validation
	 * @author Wyatt Kaiser
	 */

	private static JsonObject getNextDataTablesPageCluster(String type, int id, int userId, HttpServletRequest request) {
		try {
			// Parameter validation
			HashMap<String, Integer> attrMap = RESTHelpers.getAttrMapCluster(type,
					request);
			if (null == attrMap) {
				return null;
			}

			List<JobPair> jobPairsToDisplay = new LinkedList<JobPair>();
			int totalJobPairs = 0;

			if (type.equals("queue")) {
				// Retrieves the relevant Job objects to use in constructing the
				// JSON to send to the client
				jobPairsToDisplay = Queues.getJobPairsForNextClusterPage(
						attrMap.get(STARTING_RECORD), // Record to start at
						attrMap.get(RECORDS_PER_PAGE), // Number of records to
														// return
						attrMap.get(SORT_DIRECTION) == ASC ? true : false, // Sort
																			// direction
																			// (true
																			// for
																			// ASC)
						attrMap.get(SORT_COLUMN), // Column sorted on
						request.getParameter(SEARCH_QUERY), // Search query
						id, // Parent space id
						"queue" // It is a queue, not a node
				);
				
				
				totalJobPairs = Queues.getCountOfEnqueuedPairsShallow(id);
				/**
				 * Used to display the 'total entries' information at the bottom of
				 * the DataTable; also indirectly controls whether or not the
				 * pagination buttons are toggle-able
				 */
				// If no search is provided, TOTAL_RECORDS_AFTER_QUERY =
				// TOTAL_RECORDS
				if (attrMap.get(SEARCH_QUERY) == EMPTY) {
					attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalJobPairs);
				}
				// Otherwise, TOTAL_RECORDS_AFTER_QUERY < TOTAL_RECORDS
				else {
					attrMap.put(TOTAL_RECORDS_AFTER_QUERY, jobPairsToDisplay.size());
				}
				return convertJobPairsToJsonObjectCluster(jobPairsToDisplay,
						totalJobPairs, attrMap.get(TOTAL_RECORDS_AFTER_QUERY),
						attrMap.get(SYNC_VALUE), userId);

			} else if (type.equals("node")) {
				// Retrieves the relevant Job objects to use in constructing the
				// JSON to send to the client
				jobPairsToDisplay = Queues.getJobPairsForNextClusterPage(
						attrMap.get(STARTING_RECORD), // Record to start at
						attrMap.get(RECORDS_PER_PAGE), // Number of records to
														// return
						attrMap.get(SORT_DIRECTION) == ASC ? true : false, // Sort
																			// direction
																			// (true
																			// for
																			// ASC)
						attrMap.get(SORT_COLUMN), // Column sorted on
						request.getParameter(SEARCH_QUERY), // Search query
						id, // Parent space id
						"node" // It is a node, not a queue
				);
				totalJobPairs = Queues.getCountOfRunningPairsDetailed(id);
				/**
				 * Used to display the 'total entries' information at the bottom of
				 * the DataTable; also indirectly controls whether or not the
				 * pagination buttons are toggle-able
				 */
				// If no search is provided, TOTAL_RECORDS_AFTER_QUERY =
				// TOTAL_RECORDS
				if (attrMap.get(SEARCH_QUERY) == EMPTY) {
					attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalJobPairs);
				}
				// Otherwise, TOTAL_RECORDS_AFTER_QUERY < TOTAL_RECORDS
				else {
					attrMap.put(TOTAL_RECORDS_AFTER_QUERY, jobPairsToDisplay.size());
				}
				return convertJobPairsToJsonObjectCluster(jobPairsToDisplay,
						totalJobPairs, attrMap.get(TOTAL_RECORDS_AFTER_QUERY),
						attrMap.get(SYNC_VALUE), userId);
			}
			return null;
		} catch (Exception e) {
			log.error("getNextDataTablesPageCluster says "+e.getMessage(),e);
		}
		return null;
		
	}

	/**
	 * Gets the next page of entries for a DataTable Object (ALL regardless of
	 * space)
	 * 
	 * @param type
	 *            the kind of primitives to query for
	 * @param request
	 *            the object containing all the DataTable parameters
	 * @return a JSON object representing the next page of primitives to return
	 *         to the client, <br>
	 *         or null if the parameters of the request fail validation
	 * @author Wyatt Kaiser
	 */

	private static JsonObject getNextDataTablesPageAdmin(Primitive type, HttpServletRequest request) {
		// Parameter Validation
		HashMap<String, Integer> attrMap = RESTHelpers.getAttrMap(type, request);
		if (null == attrMap) {
			return null;
		}
		int currentUserId = SessionUtil.getUserId(request);

		switch (type) {
		case JOB:
			List<Job> jobsToDisplay = new LinkedList<Job>();

			int runningjobs = Jobs.getRunningJobCount();
			int pausedjobs = Jobs.getPausedJobCount();
			int totalJobs = runningjobs + pausedjobs;
			// Retrieves the relevant Job objects to use in constructing the
			// JSON to send to the client
			jobsToDisplay = Jobs.getJobsForNextPageAdmin(
					attrMap.get(STARTING_RECORD), // Record to start at
					attrMap.get(RECORDS_PER_PAGE), // Number of records to
													// return
					attrMap.get(SORT_DIRECTION) == ASC ? true : false, // Sort
																		// direction
																		// (true
																		// for
																		// ASC)
					attrMap.get(SORT_COLUMN), // Column sorted on
					request.getParameter(SEARCH_QUERY) // Search query
					);

			/**
			 * Used to display the 'total entries' information at the bottom of
			 * the DataTable; also indirectly controls whether or not the
			 * pagination buttons are toggle-able
			 */
			// If no search is provided, TOTAL_RECORDS_AFTER_QUERY =
			// TOTAL_RECORDS
			if (attrMap.get(SEARCH_QUERY) == EMPTY) {
				attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalJobs);
			}
			// Otherwise, TOTAL_RECORDS_AFTER_QUERY < TOTAL_RECORDS
			else {
				attrMap.put(TOTAL_RECORDS_AFTER_QUERY, jobsToDisplay.size());
			}
			return convertJobsToJsonObject(jobsToDisplay, totalJobs,
					attrMap.get(TOTAL_RECORDS_AFTER_QUERY),
					attrMap.get(SYNC_VALUE));
		case NODE:
			List<WorkerNode> nodesToDisplay = new LinkedList<WorkerNode>();
			int totalNodes = Cluster.getNodeCount();
			// Retrieves the relevant Node objects to use in constructing the
			// JSON to send to the client
			nodesToDisplay = Cluster.getNodesForNextPageAdmin(
					attrMap.get(STARTING_RECORD), // Record to start at
					attrMap.get(RECORDS_PER_PAGE), // Number of records to return
					attrMap.get(SORT_DIRECTION) == ASC ? true : false, // Sort direction (true for ASC)
					attrMap.get(SORT_COLUMN), //Column sorted on
					request.getParameter(SEARCH_QUERY) // Search query
					);
						
			/**
			 * Used to display the 'total entries' information at the bottom of
			 * the DataTable; also indirectly controls whether or not the
			 * pagination buttons are toggle-able
			 */
			// If no search is provided, TOTAL_RECORDS_AFTER_QUERY =
			// TOTAL_RECORDS
			if (attrMap.get(SEARCH_QUERY) == EMPTY) {
				attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalNodes);
			}
			// Otherwise, TOTAL_RECORDS_AFTER_QUERY < TOTAL_RECORDS
			else {
				attrMap.put(TOTAL_RECORDS_AFTER_QUERY, nodesToDisplay.size());
			}

			return convertNodesToJsonObject(nodesToDisplay, totalNodes, attrMap.get(TOTAL_RECORDS_AFTER_QUERY), attrMap.get(SYNC_VALUE));

		case USER:
			List<User> usersToDisplay = new LinkedList<User>();
			int totalUsers = Users.getCount();
			// Retrieves the relevant User objects to use in constructing the
			// JSON to send to the client
			usersToDisplay = Users.getUsersForNextPageAdmin(
					attrMap.get(STARTING_RECORD), // Record to start at
					attrMap.get(RECORDS_PER_PAGE), // Number of records to
													// return
					attrMap.get(SORT_DIRECTION) == ASC ? true : false, // Sort
																		// direction
																		// (true
																		// for
																		// ASC)
					attrMap.get(SORT_COLUMN), // Column sorted on
					request.getParameter(SEARCH_QUERY) // Search query
					);

			/**
			 * Used to display the 'total entries' information at the bottom of
			 * the DataTable; also indirectly controls whether or not the
			 * pagination buttons are toggle-able
			 */
			// If no search is provided, TOTAL_RECORDS_AFTER_QUERY =
			// TOTAL_RECORDS
			if (attrMap.get(SEARCH_QUERY) == EMPTY) {
				attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalUsers);
			}
			// Otherwise, TOTAL_RECORDS_AFTER_QUERY < TOTAL_RECORDS
			else {
				attrMap.put(TOTAL_RECORDS_AFTER_QUERY, usersToDisplay.size());
			}

			return convertUsersToJsonObject(usersToDisplay, totalUsers, attrMap.get(TOTAL_RECORDS_AFTER_QUERY), attrMap.get(SYNC_VALUE), currentUserId);

		}
		return null;
	}



	/**
	 * Gets the next page of entries for a DataTable object
	 * 
	 * @param type
	 *            the kind of primitives to query for
	 * @param id
	 *            either the id of the space to get the primitives from, or the
	 *            id of the job to get job pairs for
	 * @param request
	 *            the object containing all the DataTable parameters
	 * @param forPage
	 *            An integer code indicating what the results are for. 1: space
	 *            explorer 2: user details
	 * @return a JSON object representing the next page of primitives to return
	 *         to the client,<br>
	 *         or null if the parameters of the request fail validation
	 * @author Todd Elvers
	 */

	private static JsonObject getNextDataTablesPage(Primitive type, int id, HttpServletRequest request, int forPage,boolean recycled) {
		// Parameter validation
	    HashMap<String, Integer> attrMap = RESTHelpers.getAttrMap(type, request);
	    if(null == attrMap){
	    	return null;
	    }
		int currentUserId = SessionUtil.getUserId(request);

		switch (type) {
		case JOB:
			List<Job> jobsToDisplay = new LinkedList<Job>();

			int totalJobs;
			// Retrieves the relevant Job objects to use in constructing the
			// JSON to send to the client
			if (forPage == PAGE_SPACE_EXPLORER) {
				jobsToDisplay = Jobs.getJobsForNextPage(
						attrMap.get(STARTING_RECORD), // Record to start at
						attrMap.get(RECORDS_PER_PAGE), // Number of records to
														// return
						attrMap.get(SORT_DIRECTION) == ASC ? true : false, // Sort
																			// direction
																			// (true
																			// for
																			// ASC)
						attrMap.get(SORT_COLUMN), // Column sorted on
						request.getParameter(SEARCH_QUERY), // Search query
						id // Parent space id
						);
				totalJobs = Jobs.getCountInSpace(id);
				if (attrMap.get(SEARCH_QUERY) == EMPTY) {
					attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalJobs);
				} else {
					attrMap.put(
							TOTAL_RECORDS_AFTER_QUERY,
							Jobs.getCountInSpace(id,
									request.getParameter(SEARCH_QUERY)));
				}
			} else {
				jobsToDisplay = Jobs.getJobsByUserForNextPage(
						attrMap.get(STARTING_RECORD), // Record to start at
						attrMap.get(RECORDS_PER_PAGE), // Number of records to
														// return
						attrMap.get(SORT_DIRECTION) == ASC ? true : false, // Sort
																			// direction
																			// (true
																			// for
																			// ASC)
						attrMap.get(SORT_COLUMN), // Column sorted on
						request.getParameter(SEARCH_QUERY), // Search query
						id // User id
						);
				totalJobs = Jobs.getJobCountByUser(id);
				if (attrMap.get(SEARCH_QUERY) == EMPTY) {
					attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalJobs);
				} else {
					attrMap.put(
							TOTAL_RECORDS_AFTER_QUERY,
							Jobs.getJobCountByUser(id,
									request.getParameter(SEARCH_QUERY)));
				}
			}

			// If no search is provided, TOTAL_RECORDS_AFTER_QUERY = TOTAL_RECORDS

			JsonObject answer = convertJobsToJsonObject(jobsToDisplay, totalJobs,attrMap.get(TOTAL_RECORDS_AFTER_QUERY), attrMap.get(SYNC_VALUE));
			return answer;
		    	
		case USER:
    		List<User> usersToDisplay = new LinkedList<User>();
    		int totalUsersInSpace = Users.getCountInSpace(id);
    		
    		// Retrieves the relevant User objects to use in constructing the JSON to send to the client
    		usersToDisplay = Users.getUsersForNextPage(
    				attrMap.get(STARTING_RECORD),						// Record to start at  
    				attrMap.get(RECORDS_PER_PAGE), 						// Number of records to return
    				attrMap.get(SORT_DIRECTION) == ASC ? true : false,	// Sort direction (true for ASC)
    				attrMap.get(SORT_COLUMN), 							// Column sorted on
    				request.getParameter(SEARCH_QUERY), 				// Search query
    				id													// Parent space id 
			);
    		
    		
    		/**
	    	 * Used to display the 'total entries' information at the bottom of the DataTable;
	    	 * also indirectly controls whether or not the pagination buttons are toggle-able
	    	 */
	    	// If no search is provided, TOTAL_RECORDS_AFTER_QUERY = TOTAL_RECORDS
	    	if(attrMap.get(SEARCH_QUERY) == EMPTY){
	    		attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalUsersInSpace);
	    	} 
	    	// Otherwise, TOTAL_RECORDS_AFTER_QUERY < TOTAL_RECORDS 
	    	else {
	    		attrMap.put(TOTAL_RECORDS_AFTER_QUERY, Users.getCountInSpace(id,request.getParameter(SEARCH_QUERY)));
	    	}
	    	
	    	return convertUsersToJsonObject(usersToDisplay,totalUsersInSpace,attrMap.get(TOTAL_RECORDS_AFTER_QUERY),attrMap.get(SYNC_VALUE),currentUserId);
	    	
		    	
	    case SOLVER:
    		List<Solver> solversToDisplay = new LinkedList<Solver>();
    		
    		int totalSolvers=0;
    		// Retrieves the relevant Solver objects to use in constructing the JSON to send to the client
    		if (forPage==PAGE_SPACE_EXPLORER) {
    			solversToDisplay = Solvers.getSolversForNextPage(
	    				attrMap.get(STARTING_RECORD),						// Record to start at  
	    				attrMap.get(RECORDS_PER_PAGE), 						// Number of records to return
	    				attrMap.get(SORT_DIRECTION) == ASC ? true : false,	// Sort direction (true for ASC)
	    				attrMap.get(SORT_COLUMN), 							// Column sorted on
	    				request.getParameter(SEARCH_QUERY), 				// Search query
	    				id													// Parent space id 
				);
    			totalSolvers =  Solvers.getCountInSpace(id);
    			if(attrMap.get(SEARCH_QUERY) == EMPTY){
		    		attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalSolvers);
		    	} 
		    	// Otherwise, TOTAL_RECORDS_AFTER_QUERY < TOTAL_RECORDS 
		    	else {
		    		attrMap.put(TOTAL_RECORDS_AFTER_QUERY, Solvers.getCountInSpace(id,request.getParameter(SEARCH_QUERY)));
		    	}
    		} else {
    			solversToDisplay=Solvers.getSolversByUserForNextPage(		    				attrMap.get(STARTING_RECORD),						// Record to start at  
	    				attrMap.get(RECORDS_PER_PAGE), 						// Number of records to return
	    				attrMap.get(SORT_DIRECTION) == ASC ? true : false,	// Sort direction (true for ASC)
	    				attrMap.get(SORT_COLUMN), 							// Column sorted on
	    				request.getParameter(SEARCH_QUERY), 				// Search query
	    				id,
	    				recycled											//whether to get recycled solvers
	    		);
    			if (!recycled) {
    				totalSolvers =  Solvers.getSolverCountByUser(id);
    			} else {
    				totalSolvers=Solvers.getRecycledSolverCountByUser(id);
    			}
    			
    			// If no search is provided, TOTAL_RECORDS_AFTER_QUERY = TOTAL_RECORDS
		    	if(attrMap.get(SEARCH_QUERY) == EMPTY){
		    		attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalSolvers);
		    	} 
		    	// Otherwise, TOTAL_RECORDS_AFTER_QUERY < TOTAL_RECORDS 
		    	else {
		    		if (!recycled) {
		    			attrMap.put(TOTAL_RECORDS_AFTER_QUERY, Solvers.getSolverCountByUser(id,request.getParameter(SEARCH_QUERY)));
		    		} else {
		    			attrMap.put(TOTAL_RECORDS_AFTER_QUERY, Solvers.getRecycledSolverCountByUser(id,request.getParameter(SEARCH_QUERY)));
		    		}
		    		
		    		
		    	}
    		}

    		
		    return convertSolversToJsonObject(solversToDisplay, totalSolvers,attrMap.get(TOTAL_RECORDS_AFTER_QUERY),attrMap.get(SYNC_VALUE));
	    	
		    	
	    case BENCHMARK:
	    	
	    	List<Benchmark> benchmarksToDisplay = new LinkedList<Benchmark>();
	    	int totalBenchmarks=0;
	    	 String sortOverride = request.getParameter(SORT_COLUMN_OVERRIDE);
	         if (sortOverride!=null) {
	         	attrMap.put(SORT_COLUMN, Integer.parseInt(sortOverride));
	         	if (Boolean.parseBoolean(request.getParameter(SORT_COLUMN_OVERRIDE_DIR))) {
	             	attrMap.put(SORT_DIRECTION, ASC);

	         	} else {
	             	attrMap.put(SORT_DIRECTION, ASC+1);

	         	}
	         }
	    	// Retrieves the relevant Benchmark objects to use in constructing the JSON to send to the client
	    	if (forPage==PAGE_SPACE_EXPLORER) {
	    		benchmarksToDisplay = Benchmarks.getBenchmarksForNextPage(
	    				attrMap.get(STARTING_RECORD),						// Record to start at  
	    				attrMap.get(RECORDS_PER_PAGE), 						// Number of records to return
	    				attrMap.get(SORT_DIRECTION) == ASC ? true : false,	// Sort direction (true for ASC)
	    				attrMap.get(SORT_COLUMN), 							// Column sorted on
	    				request.getParameter(SEARCH_QUERY),			 		// Search query
	    				id												// Parent space id 
				);	

	    		totalBenchmarks = Benchmarks.getCountInSpace(id);
	    		// If no search is provided, TOTAL_RECORDS_AFTER_QUERY = TOTAL_RECORDS
		    	if(attrMap.get(SEARCH_QUERY) == EMPTY){
		    		attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalBenchmarks);
		    	} 
		    	else {
		    		attrMap.put(TOTAL_RECORDS_AFTER_QUERY, Benchmarks.getCountInSpace(id,request.getParameter(SEARCH_QUERY)));
		    	}

	    	} else {
	    		benchmarksToDisplay = Benchmarks.getBenchmarksByUserForNextPage(
	    				attrMap.get(STARTING_RECORD),						// Record to start at  
	    				attrMap.get(RECORDS_PER_PAGE), 						// Number of records to return
	    				attrMap.get(SORT_DIRECTION) == ASC ? true : false,	// Sort direction (true for ASC)
	    				attrMap.get(SORT_COLUMN), 							// Column sorted on
	    				request.getParameter(SEARCH_QUERY),			 		// Search query
	    				id,												// Parent space id 
	    				recycled
				);
	    		if (!recycled) {
	    			totalBenchmarks = Benchmarks.getBenchmarkCountByUser(id);
	    		} else {
	    			totalBenchmarks=Benchmarks.getRecycledBenchmarkCountByUser(id);
	    		}
	    		
	    		// If no search is provided, TOTAL_RECORDS_AFTER_QUERY = TOTAL_RECORDS
		    	if(attrMap.get(SEARCH_QUERY) == EMPTY){
		    		attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalBenchmarks);
		    	} 
		    	else {
		    		if (!recycled) {
			    		attrMap.put(TOTAL_RECORDS_AFTER_QUERY, Benchmarks.getBenchmarkCountByUser(id,request.getParameter(SEARCH_QUERY)));
		    		} else {
			    		attrMap.put(TOTAL_RECORDS_AFTER_QUERY, Benchmarks.getBenchmarkCountByUser(id,request.getParameter(SEARCH_QUERY)));
		    		}
		    	}
	    	}
		    return convertBenchmarksToJsonObject(benchmarksToDisplay,totalBenchmarks,attrMap.get(TOTAL_RECORDS_AFTER_QUERY),attrMap.get(SYNC_VALUE));
	    	
	    case SPACE:
	    	List<Space> spacesToDisplay = new LinkedList<Space>();
	    	
    		int userId = SessionUtil.getUserId(request);
    		int totalSubspacesInSpace = Spaces.getCountInSpace(id, userId,false);
    		
	    	// Retrieves the relevant Benchmark objects to use in constructing the JSON to send to the client
	    	spacesToDisplay = Spaces.getSpacesForNextPage(
    				attrMap.get(STARTING_RECORD),						// Record to start at  
    				attrMap.get(RECORDS_PER_PAGE), 						// Number of records to return
    				attrMap.get(SORT_DIRECTION) == ASC ? true : false,	// Sort direction (true for ASC)
    				attrMap.get(SORT_COLUMN), 							// Column sorted on
    				request.getParameter(SEARCH_QUERY), 				// Search query
    				id,											// Parent space id 
    				userId												// Id of user making request
			);
	    	
	    	
	    	/**
	    	 * Used to display the 'total entries' information at the bottom of the DataTable;
	    	 * also indirectly controls whether or not the pagination buttons are toggle-able
	    	 */
	    	// If no search is provided, TOTAL_RECORDS_AFTER_QUERY = TOTAL_RECORDS
	    	if(attrMap.get(SEARCH_QUERY) == EMPTY){
	    		attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalSubspacesInSpace);
	    	} 
	    	// Otherwise, TOTAL_RECORDS_AFTER_QUERY < TOTAL_RECORDS 
	    	else {
	    		attrMap.put(TOTAL_RECORDS_AFTER_QUERY, Spaces.getCountInSpace(id, userId, request.getParameter(SEARCH_QUERY)));
	    	}
	    	return convertSpacesToJsonObject(spacesToDisplay,totalSubspacesInSpace,attrMap.get(TOTAL_RECORDS_AFTER_QUERY),attrMap.get(SYNC_VALUE),id);
		}
		return null;
	}
	
	/**
	 * Generate the HTML for the next DataTable page of entries
	 */
	public static JsonObject convertJobPairsToJsonObjectCluster(List<JobPair> pairs, int totalRecords, int totalRecordsAfterQuery, int syncValue, int userId) {
		JsonArray dataTablePageEntries = new JsonArray();
		for(JobPair j : pairs){
			StringBuilder sb = new StringBuilder();
			String hiddenJobPairId;
			
			// Create the hidden input tag containing the jobpair id
			sb.append("<input type=\"hidden\" value=\"");
			sb.append(j.getId());
			sb.append("\" name=\"pid\"/>");
			hiddenJobPairId = sb.toString();
			
			// Create the job link
			//Job job = Jobs.get(j.getJobId());
    		sb = new StringBuilder();
    		sb.append("<a href=\""+Util.docRoot("secure/details/job.jsp?id="));
    		sb.append(j.getJobId());
    		sb.append("\" target=\"_blank\">");
    		sb.append(j.getOwningJob().getName());
    		RESTHelpers.addImg(sb);
    		sb.append(hiddenJobPairId);
			String jobLink = sb.toString();
			
			//Create the User Link
    		sb = new StringBuilder();
			String hiddenUserId;
			
			User user=j.getOwningUser();
			
			
			
			// Create the hidden input tag containing the user id
			if(user.getId() == userId) {
				sb.append("<input type=\"hidden\" value=\"");
				sb.append(user.getId());
				sb.append("\" name=\"currentUser\" id=\"uid"+user.getId()+"\" prim=\"user\"/>");
				hiddenUserId = sb.toString();
			} else {
				sb.append("<input type=\"hidden\" value=\"");
				sb.append(user.getId());
				sb.append("\" id=\"uid"+user.getId()+"\" prim=\"user\"/>");
				hiddenUserId = sb.toString();
			}
    		sb = new StringBuilder();
    		sb.append("<a href=\""+Util.docRoot("secure/details/user.jsp?id="));
    		sb.append(user.getId());
    		sb.append("\" target=\"_blank\">");
    		sb.append(user.getFullName());
    		RESTHelpers.addImg(sb);
    		sb.append(hiddenUserId);
			String userLink = sb.toString();
			
    		// Create the benchmark link
    		sb = new StringBuilder();
    		sb.append("<a href=\""+Util.docRoot("secure/details/benchmark.jsp?id="));
    		sb.append(j.getBench().getId());
    		sb.append("\" target=\"_blank\">");
    		sb.append(j.getBench().getName());
    		RESTHelpers.addImg(sb);
    		sb.append(hiddenJobPairId);
			String benchLink = sb.toString();
			
			// Create the solver link
    		sb = new StringBuilder();
    		sb.append("<a href=\""+Util.docRoot("secure/details/solver.jsp?id="));
    		sb.append(j.getPrimarySolver().getId());
    		sb.append("\" target=\"_blank\">");
    		sb.append(j.getPrimarySolver().getName());
    		RESTHelpers.addImg(sb);
			String solverLink = sb.toString();
			
			// Create the configuration link
    		sb = new StringBuilder();
    		sb.append("<a  href=\""+Util.docRoot("secure/details/configuration.jsp?id="));
    		sb.append(j.getPrimarySolver().getConfigurations().get(0).getId());
    		sb.append("\" target=\"_blank\">");
    		sb.append(j.getPrimarySolver().getConfigurations().get(0).getName());
    		RESTHelpers.addImg(sb);
			String configLink = sb.toString();
			
			String path = j.getPath();
			
			// Create an object, and inject the above HTML, to represent an entry in the DataTable
			JsonArray entry = new JsonArray();
    		entry.add(new JsonPrimitive(jobLink));
    		entry.add(new JsonPrimitive(userLink));
    		entry.add(new JsonPrimitive(benchLink));
    		entry.add(new JsonPrimitive(solverLink));
    		entry.add(new JsonPrimitive(configLink));
    		entry.add(new JsonPrimitive(path));
    		dataTablePageEntries.add(entry);
    	}
		
	    	JsonObject nextPage=new JsonObject();
	    	 // Build the actual JSON response object and populated it with the created data
		    nextPage.addProperty(SYNC_VALUE, syncValue);
		    nextPage.addProperty(TOTAL_RECORDS, totalRecords);
		    nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY, totalRecordsAfterQuery);
		    nextPage.add("aaData", dataTablePageEntries);
		    
		    // Return the next DataTable page
	    	return nextPage;
		}
	
	public static JsonObject convertSolverComparisonsToJsonObject(List<SolverComparison> comparisons, int totalRecords, int totalRecordsAfterQuery, int syncValue, boolean useWallclock, int stageNumber) {
		/**
		 * Generate the HTML for the next DataTable page of entries
		 */
		JsonArray dataTablePageEntries = new JsonArray();
		for(SolverComparison c : comparisons){
    		
    		
    		// Create the benchmark link and append the hidden input element
    		StringBuilder sb = new StringBuilder();
    		sb.append("<a title=\"");
    		sb.append("\" href=\""+Util.docRoot("secure/details/benchmark.jsp?id="));
    		sb.append(c.getBenchmark().getId());
    		sb.append("\" target=\"_blank\">");
    		sb.append(c.getBenchmark().getName());
    		RESTHelpers.addImg(sb);
			String benchLink = sb.toString();

			// Create an object, and inject the above HTML, to represent an
			// entry in the DataTable
			JsonArray entry = new JsonArray();
    		entry.add(new JsonPrimitive(benchLink));
    		
    		
    		if (useWallclock) {
    			double displayWC1 = Math.round(c.getFirstPair().getStageFromNumber(stageNumber).getWallclockTime()*100)/100.0;		
    			double displayWC2 =  Math.round(c.getSecondPair().getStageFromNumber(stageNumber).getWallclockTime()*100)/100.0;
    			double displayDiff =  Math.round(c.getWallclockDifference(stageNumber)*100)/100.0;		

        		entry.add(new JsonPrimitive(displayWC1 + " s"));
        		entry.add(new JsonPrimitive(displayWC2 + " s"));
        		entry.add(new JsonPrimitive(displayDiff + " s"));

    		} else {
    			double display1 = Math.round(c.getFirstPair().getStageFromNumber(stageNumber).getCpuTime()*100)/100.0;		
    			double display2 =  Math.round(c.getSecondPair().getStageFromNumber(stageNumber).getCpuTime()*100)/100.0;
    			double displayDiff =  Math.round(c.getCpuDifference(stageNumber)*100)/100.0;		

        		entry.add(new JsonPrimitive(display1 + " s"));
        		entry.add(new JsonPrimitive(display2 + " s"));
        		entry.add(new JsonPrimitive(displayDiff + " s"));
    		}
    		
    		entry.add(new JsonPrimitive(c.getFirstPair().getStageFromNumber(stageNumber).getStarexecResult()));    	
    		entry.add(new JsonPrimitive(c.getSecondPair().getStageFromNumber(stageNumber).getStarexecResult()));    		
    		if (c.doResultsMatch(stageNumber)) {
        		entry.add(new JsonPrimitive(1));    		
    		} else {
        		entry.add(new JsonPrimitive(0));    		

    		}
    		dataTablePageEntries.add(entry);
    	}
	    	JsonObject nextPage=new JsonObject();
	    	 // Build the actual JSON response object and populated it with the created data
		    nextPage.addProperty(SYNC_VALUE, syncValue);
		    nextPage.addProperty(TOTAL_RECORDS, totalRecords);
		    nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY, totalRecordsAfterQuery);
		    nextPage.add("aaData", dataTablePageEntries);
		    
		    // Return the next DataTable page
	    	return nextPage;
		}
	
	
	
	/**
	 * Given a list of job pairs, creates a JsonObject that can be used to populate a datatable client-side
	 * @param pairs The pairs that will be the rows of the table
	 * @param totalRecords The total number of records in the table (not the same as the size of pairs)
	 * @param totalRecordsAfterQuery The total number of records in the table after a given search query was applied
	 * (if no search query, this should be the same as totalRecords)
	 * @param syncValue An integer value possibly given by the datatable to keep the client and server synchronized.
	 * Given a list of job pairs, creates a JsonObject that can be used to
	 * populate a datatable client-side
	 * 
	 * @param pairs
	 *            The pairs that will be the rows of the table
	 * @param totalRecords
	 *            The total number of records in the table (not the same as the
	 *            size of pairs)
	 * @param totalRecordsAfterQuery
	 *            The total number of records in the table after a given search
	 *            query was applied (if no search query, this should be the same
	 *            as totalRecords)
	 * @param syncValue
	 *            An integer value possibly given by the datatable to keep the
	 *            client and server synchronized. If one isn't present, any
	 *            integer
	 * @return A JsonObject that can be used to populate a datatable
	 * @author Eric Burns
	 */
	public static JsonObject convertJobPairsToJsonObject(List<JobPair> pairs, int totalRecords, int totalRecordsAfterQuery, int syncValue, boolean includeConfigAndSolver, boolean useWallclock, int stageNumber) {
		/**
		 * Generate the HTML for the next DataTable page of entries
		 */
		JsonArray dataTablePageEntries = new JsonArray();
		String solverLink=null;
		String configLink=null;
		for(JobPair jp : pairs){
			JoblineStage stage=jp.getStageFromNumber(stageNumber);
    		StringBuilder sb = new StringBuilder();
			String hiddenJobPairId;

			// Create the hidden input tag containing the jobpair id
			sb.append("<input type=\"hidden\" value=\"");
			sb.append(jp.getId());
			sb.append("\" name=\"pid\"/>");
			hiddenJobPairId = sb.toString();
    		
    		// Create the benchmark link and append the hidden input element
    		sb = new StringBuilder();
    		sb.append("<a title=\"");
    		sb.append("\" href=\""+Util.docRoot("secure/details/benchmark.jsp?id="));
    		sb.append(jp.getBench().getId());
    		sb.append("\" target=\"_blank\">");
    		sb.append(jp.getBench().getName());
    		RESTHelpers.addImg(sb);
    		sb.append(hiddenJobPairId);
			String benchLink = sb.toString();

			if (includeConfigAndSolver) {
				// Create the solver link
	    		sb = new StringBuilder();
	    		sb.append("<a title=\"");
	    		sb.append("\" href=\""+Util.docRoot("secure/details/solver.jsp?id="));
	    		sb.append(stage.getSolver().getId());
	    		sb.append("\" target=\"_blank\">");
	    		sb.append(stage.getSolver().getName());
	    		RESTHelpers.addImg(sb);
				solverLink = sb.toString();
				
				// Create the configuration link
	    		sb = new StringBuilder();
	    		sb.append("<a title=\"");
	    		sb.append("\" href=\""+Util.docRoot("secure/details/configuration.jsp?id="));
	    		sb.append(stage.getSolver().getConfigurations().get(0).getId());
	    		sb.append("\" target=\"_blank\">");
	    		sb.append(stage.getSolver().getConfigurations().get(0).getName());
	    		RESTHelpers.addImg(sb);
				configLink = sb.toString();
			}


			// Create the status field
    		sb = new StringBuilder();
    		sb.append("<a title=\"");
    		sb.append(stage.getStatus().getDescription());
    		sb.append("\">");
    		sb.append(stage.getStatus().getStatus()+" ("+stage.getStatus().getCode().getVal()+")");
    		sb.append("</a>");
			String status = sb.toString();

			// Create an object, and inject the above HTML, to represent an
			// entry in the DataTable
			JsonArray entry = new JsonArray();
    		entry.add(new JsonPrimitive(benchLink));
    		if (includeConfigAndSolver) {
    			entry.add(new JsonPrimitive(solverLink));
        		entry.add(new JsonPrimitive(configLink));
    		}
    		
    		entry.add(new JsonPrimitive(status));
    		if (useWallclock) {
    			double displayWC = Math.round(stage.getWallclockTime()*100)/100.0;		    	
        		
        		entry.add(new JsonPrimitive(displayWC + " s"));
    		} else {
    			double displayCpu = Math.round(stage.getCpuTime()*100)/100.0;		    	
        		
        		entry.add(new JsonPrimitive(displayCpu + " s"));
    		}
    		
    		entry.add(new JsonPrimitive(stage.getStarexecResult()));    		
    		dataTablePageEntries.add(entry);
    	}
	    	JsonObject nextPage=new JsonObject();
	    	 // Build the actual JSON response object and populated it with the created data
		    nextPage.addProperty(SYNC_VALUE, syncValue);
		    nextPage.addProperty(TOTAL_RECORDS, totalRecords);
		    nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY, totalRecordsAfterQuery);
		    nextPage.add("aaData", dataTablePageEntries);
		    
		    // Return the next DataTable page
	    	return nextPage;
		}

	/**
	 * Given a list of jobs, creates a JsonObject that can be used to populate a
	 * datatable client-side
	 * 
	 * @param jobs
	 *            The jobs that will be the rows of the table
	 * @param totalRecords
	 *            The total number of records in the table (not the same as the
	 *            size of pairs)
	 * @param totalRecordsAfterQuery
	 *            The total number of records in the table after a given search
	 *            query was applied (if no search query, this should be the same
	 *            as totalRecords)
	 * @param syncValue
	 *            An integer value possibly given by the datatable to keep the
	 *            client and server synchronized. If one isn't present, any
	 *            integer
	 * @param forPage
	 *            An integer code indicating what web page this JsonObject will
	 *            be used for
	 * @return A JsonObject that can be used to populate a datatable
	 * @author Eric Burns
	 */
	public static JsonObject convertJobsToJsonObject(List<Job> jobs,
			int totalRecords, int totalRecordsAfterQuery, int syncValue) {
		/**
		 * Generate the HTML for the next DataTable page of entries
		 */
		JsonArray dataTablePageEntries = new JsonArray();
		for (Job job : jobs) {
			StringBuilder sb = new StringBuilder();
			String hiddenJobId;
			
			// Create the hidden input tag containing the job id
			sb.append("<input type=\"hidden\" value=\"");
			sb.append(job.getId());
			sb.append("\" prim=\"job\" userId=\""+job.getUserId()+"\"  deleted=\""+job.isDeleted()+"\"/>");
			hiddenJobId = sb.toString();

			// Create the job "details" link and append the hidden input element
			sb = new StringBuilder();
			sb.append("<a href=\"" + Util.docRoot("secure/details/job.jsp?id="));
			sb.append(job.getId());
			sb.append("\" target=\"_blank\">");
			sb.append(job.getName());
			RESTHelpers.addImg(sb);
			sb.append(hiddenJobId);
			String jobLink = sb.toString();

			String status = job.getLiteJobPairStats().get("pendingPairs") > 0 ? "incomplete" : "complete";
			if (Jobs.isSystemPaused()) {
				status = "global pause";
			}
			if (Jobs.isJobPaused(job.getId())) {
				status = "paused";
			}
			if (Jobs.isJobKilled(job.getId())) {
				status = "killed";
			}

			// Create an object, and inject the above HTML, to represent an
			// entry in the DataTable
			JsonArray entry = new JsonArray();
			entry.add(new JsonPrimitive(jobLink));
			entry.add(new JsonPrimitive(status));
			entry.add(new JsonPrimitive(getPercentStatHtml("asc", job
					.getLiteJobPairStats().get("completionPercentage"),
					true)));
			entry.add(new JsonPrimitive(getPercentStatHtml("static", job
					.getLiteJobPairStats().get("totalPairs"), false)));
			entry.add(new JsonPrimitive(getPercentStatHtml("desc", job
					.getLiteJobPairStats().get("errorPercentage"), true)));
			
			entry.add(new JsonPrimitive(job.getCreateTime().toString()));

			dataTablePageEntries.add(entry);
		}
		JsonObject nextPage = new JsonObject();
		// Build the actual JSON response object and populated it with the
		// created data
		nextPage.addProperty(SYNC_VALUE, syncValue);
		nextPage.addProperty(TOTAL_RECORDS, totalRecords);
		nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY, totalRecordsAfterQuery);
		nextPage.add("aaData", dataTablePageEntries);

		// Return the next DataTable page
		return nextPage;
	}

	/*
	public static String getSpaceOverviewGraphPath(int jobSpaceId, boolean logX, boolean logY, List<Integer> configIds, int stageNumber) {
		String chartPath = null;
		if (configIds.size()<=R.MAXIMUM_SOLVER_CONFIG_PAIRS) {
			chartPath=Statistics.makeSpaceOverviewChart(jobSpaceId,logX,logY,configIds,stageNumber);
			if (chartPath.equals("big")) {
				return gson.toJson(ERROR_TOO_MANY_JOB_PAIRS);
			}
		} else {
			return gson.toJson(ERROR_TOO_MANY_SOLVER_CONFIG_PAIRS);
		}

		log.debug("chartPath = "+chartPath);
		return chartPath == null ? gson.toJson(ERROR_DATABASE) : chartPath;
	}
	*/

	/**

	 * Generate the HTML for the next DataTable page of entries
	 * Given a list of users, creates a JsonObject that can be used to populate
	 * a datatable client-side
	 * 
	 * @param users The users that will be the rows of the table
	 * @param totalRecords The total number of records in the table (not the same as the size of pairs)
	 * @param totalRecordsAfterQuery The total number of records in the table after a given search
	 *            query was applied (if no search query, this should be the same
	 *            as totalRecords)
	 * @param syncValue An integer value possibly given by the datatable to keep the
	 *            client and server synchronized. If one isn't present, any integer
	 * @param currentUserId the ID of the user making the request for this datatable
	 * @return A JsonObject that can be used to populate a datatable
	 * @author Eric Burns
	 */
	public static JsonObject convertUsersToJsonObject(List<User> users, int totalRecords, int totalRecordsAfterQuery, int syncValue, int currentUserId) {
		/**
		 * Generate the HTML for the next DataTable page of entries
		 */
		JsonArray dataTablePageEntries = new JsonArray();
		for (User user : users) {
			StringBuilder sb = new StringBuilder();
			String hiddenUserId;

			// Create the hidden input tag containing the user id
			if (user.getId() == currentUserId) {
				sb.append("<input type=\"hidden\" value=\"");
				sb.append(user.getId());
				sb.append("\" name=\"currentUser\" id=\"uid" + user.getId() + "\" prim=\"user\"/>");
				hiddenUserId = sb.toString();
			} else {
				sb.append("<input type=\"hidden\" value=\"");
				sb.append(user.getId());
				sb.append("\" id=\"uid" + user.getId() + "\" prim=\"user\"/>");
				hiddenUserId = sb.toString();
			}

			// Create the user "details" link and append the hidden input
			// element
			sb = new StringBuilder();
			sb.append("<a href=\"" + Util.docRoot("secure/details/user.jsp?id="));
			sb.append(user.getId());
			sb.append("\" target=\"_blank\">");
			sb.append(user.getFullName());
			RESTHelpers.addImg(sb);
			sb.append(hiddenUserId);
			String userLink = sb.toString();

			sb = new StringBuilder();
			sb.append("<a href=\"mailto:");
			sb.append(user.getEmail());
			sb.append("\">");
			sb.append(user.getEmail());
			RESTHelpers.addImg(sb);
			String emailLink = sb.toString();

			sb = new StringBuilder();
			sb.append("<input type=\"button\" onclick=\"editPermissions(" + user.getId() + ")\" value=\"Edit\"/>");
			String permissionButton = sb.toString();
			
			// Create an object, and inject the above HTML, to represent an
			// entry in the DataTable
			JsonArray entry = new JsonArray();
			entry.add(new JsonPrimitive(userLink));
			entry.add(new JsonPrimitive(user.getInstitution()));
			entry.add(new JsonPrimitive(emailLink));
			entry.add(new JsonPrimitive(permissionButton));
			
			String suspendButton = "";
			if (Users.isAdmin(user.getId()) || Users.isUnauthorized(user.getId())) {
				suspendButton = "N/A";
			} else if (Users.isSuspended(user.getId())) {
				sb = new StringBuilder();
				sb.append("<input type=\"button\" onclick=\"reinstateUser(" + user.getId() + ")\" value=\"Reinstate\"/>");
				suspendButton = sb.toString();
			} else if (Users.isNormalUser(user.getId())) {
				sb = new StringBuilder();
				sb.append("<input type=\"button\" onclick=\"suspendUser(" + user.getId() + ")\" value=\"Suspend\"/>");
				suspendButton = sb.toString();
			}
			entry.add(new JsonPrimitive(suspendButton));

			String subscribeButton = "";
			if (Users.isUnauthorized(user.getId())) {
				subscribeButton = "N/A";
			} else if (user.isSubscribedToReports()) {
				subscribeButton = "<input type=\"button\" onclick=\"unsubscribeUserFromReports(" + user.getId() + ")\" value=\"Unsubscribe\"/>";
			} else {
				subscribeButton = "<input type=\"button\" onclick=\"subscribeUserToReports(" + user.getId() + ")\" value=\"Subscribe\"/>";
			}
			entry.add(new JsonPrimitive(subscribeButton));

			String developerButton = "";
			if (Users.isAdmin(user.getId()) || Users.isUnauthorized(user.getId()) || Users.isSuspended(user.getId())) {
				developerButton = "N/A";
			} else if (Users.isDeveloper(user.getId())) {
				developerButton = "<input type=\"button\" onclick=\"suspendDeveloperStatus("+user.getId()+")\"value=\"Suspend\"/>";
			} else {
				developerButton = "<input type=\"button\" onclick=\"grantDeveloperStatus("+user.getId()+")\"value=\"Grant\"/>";
			}
			entry.add(new JsonPrimitive(developerButton));


			dataTablePageEntries.add(entry);
		}
		JsonObject nextPage = new JsonObject();
		// Build the actual JSON response object and populated it with the
		// created data
		nextPage.addProperty(SYNC_VALUE, syncValue);
	    if(totalRecords < 0) {
	    	nextPage.addProperty(TOTAL_RECORDS, 0); // accounts for when there are no users except for public user (-1 result)
	    } else {
	    	nextPage.addProperty(TOTAL_RECORDS, totalRecords);
	    }
		nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY, totalRecordsAfterQuery);
		nextPage.add("aaData", dataTablePageEntries);

		// Return the next DataTable page
		return nextPage;
	}
	
	
	/**

	 * Generate the HTML for the next DataTable page of entries
	 * Given a list of TestSequences, creates a JsonObject that can be used to populate
	 * a datatable client-side
	 * 
	 * @param TestSequences The tests that will be the rows of the table
	 * @param totalRecords The total number of records in the table (not the same as the size of pairs)
	 * @param totalRecordsAfterQuery The total number of records in the table after a given search
	 *            query was applied (if no search query, this should be the same
	 *            as totalRecords)
	 * @param syncValue An integer value possibly given by the datatable to keep the
	 *            client and server synchronized. If one isn't present, any integer
	 * @param currentUserId the ID of the user making the request for this datatable
	 * @return A JsonObject that can be used to populate a datatable
	 * @author Eric Burns
	 */
	public static JsonObject convertTestSequencesToJsonObject(List<TestSequence> tests, int totalRecords, int totalRecordsAfterQuery, int syncValue) {
		/**
		 * Generate the HTML for the next DataTable page of entries
		 */
		JsonArray dataTablePageEntries = new JsonArray();
		for (TestSequence test : tests) {
			// Create an object, and inject the above HTML, to represent an
			// entry in the DataTable
			JsonArray entry = new JsonArray();
			StringBuilder sb=new StringBuilder();
			sb.append("<a name=\""+test.getName()+"\" href=\""+Util.docRoot("secure/admin/testResults.jsp?sequenceName="));
    		sb.append(test.getName());
    		sb.append("\" target=\"_blank\">");
    		sb.append(test.getName());
    		RESTHelpers.addImg(sb);
			
			entry.add(new JsonPrimitive(sb.toString()));
			entry.add(new JsonPrimitive(test.getTestCount()));
			entry.add(new JsonPrimitive(test.getTestsPassed()));
			entry.add(new JsonPrimitive(test.getTestsFailed()));
			entry.add(new JsonPrimitive(test.getStatus().getStatus()));
			entry.add(new JsonPrimitive(test.getErrorTrace()));
			dataTablePageEntries.add(entry);
		}
		JsonObject nextPage = new JsonObject();
		// Build the actual JSON response object and populated it with the
		// created data
		nextPage.addProperty(SYNC_VALUE, syncValue);
	    if(totalRecords < 0) {
	    	nextPage.addProperty(TOTAL_RECORDS, 0); // accounts for when there are no users except for public user (-1 result)
	    } else {
	    	nextPage.addProperty(TOTAL_RECORDS, totalRecords);
	    }
		nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY, totalRecordsAfterQuery);
		nextPage.add("aaData", dataTablePageEntries);

		// Return the next DataTable page
		return nextPage;
	}
	
	/**

	 * Generate the HTML for the next DataTable page of entries
	 * Given a HashMap mapping the names of tests to messages, creates a JsonObject that can be used to populate
	 * a datatable client-side
	 * 
	 * @param tests A HashMap of tests, where each test will be a row of a table
	 * @param totalRecords The total number of records in the table (not the same as the size of pairs)
	 * @param totalRecordsAfterQuery The total number of records in the table after a given search
	 *            query was applied (if no search query, this should be the same
	 *            as totalRecords)
	 * @param syncValue An integer value possibly given by the datatable to keep the
	 *            client and server synchronized. If one isn't present, any integer
	 * @param currentUserId the ID of the user making the request for this datatable
	 * @return A JsonObject that can be used to populate a datatable
	 * @author Eric Burns
	 */
	public static JsonObject convertTestResultsToJsonObject(List<TestResult> tests, int totalRecords, int totalRecordsAfterQuery, int syncValue) {
		/**
		 * Generate the HTML for the next DataTable page of entries
		 */
		JsonArray dataTablePageEntries = new JsonArray();
		for (TestResult test : tests) {
			// Create an object, and inject the above HTML, to represent an
			// entry in the DataTable
			
			JsonArray entry = new JsonArray();
			entry.add(new JsonPrimitive(test.getName()));
			entry.add(new JsonPrimitive(test.getStatus().getStatus()));
			//replacing newlines with HTML line breaks
			entry.add(new JsonPrimitive(test.getAllMessages().replace("\n", "<br/>")));
			entry.add(new JsonPrimitive(test.getErrorTrace()));
			entry.add(new JsonPrimitive(test.getTime()));
			dataTablePageEntries.add(entry);
		}
		JsonObject nextPage = new JsonObject();
		// Build the actual JSON response object and populated it with the
		// created data
		nextPage.addProperty(SYNC_VALUE, syncValue);
	    if(totalRecords < 0) {
	    	nextPage.addProperty(TOTAL_RECORDS, 0); // accounts for when there are no users except for public user (-1 result)
	    } else {
	    	nextPage.addProperty(TOTAL_RECORDS, totalRecords);
	    }
	    
		nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY, totalRecordsAfterQuery);
		nextPage.add("aaData", dataTablePageEntries);

		// Return the next DataTable page
		return nextPage;
	}

	/**
	 * Given a list of spaces, creates a JsonObject that can be used to populate
	 * a datatable client-side
	 * 
	 * @param spaces
	 *            The spaces that will be the rows of the table
	 * @param totalRecords
	 *            The total number of records in the table (not the same as the
	 *            size of pairs)
	 * @param totalRecordsAfterQuery
	 *            The total number of records in the table after a given search
	 *            query was applied (if no search query, this should be the same
	 *            as totalRecords)
	 * @param syncValue
	 *            An integer value possibly given by the datatable to keep the
	 *            client and server synchronized. If one isn't present, any
	 *            integer
	 * @return A JsonObject that can be used to populate a datatable
	 * @author Eric Burns
	 */
	public static JsonObject convertSpacesToJsonObject(List<Space> spaces,
			int totalRecords, int totalRecordsAfterQuery, int syncValue, int id) {
		/**
		 * Generate the HTML for the next DataTable page of entries
		 */
		JsonArray dataTablePageEntries = new JsonArray();
		for (Space space : spaces) {
			StringBuilder sb = new StringBuilder();
			String hiddenSpaceId;

			// Create the hidden input tag containing the space id
			sb.append("<input type=\"hidden\" value=\"");
			sb.append(space.getId());
			sb.append("\" prim=\"space\" />");
			hiddenSpaceId = sb.toString();

			// Create the space "details" link and append the hidden input
			// element
			sb = new StringBuilder();
			sb.append("<a class=\"spaceLink\" onclick=\"openSpace(");
			sb.append(id);
			sb.append(",");
			sb.append(space.getId());
			sb.append(")\">");
			sb.append(space.getName());
			RESTHelpers.addImg(sb);
			sb.append(hiddenSpaceId);
			String spaceLink = sb.toString();

			// Create an object, and inject the above HTML, to represent an
			// entry in the DataTable
			JsonArray entry = new JsonArray();
			entry.add(new JsonPrimitive(spaceLink));
			entry.add(new JsonPrimitive(space.getDescription()));

			dataTablePageEntries.add(entry);
		}

    	JsonObject nextPage=new JsonObject();
    	 // Build the actual JSON response object and populated it with the created data
	    nextPage.addProperty(SYNC_VALUE, syncValue);
	    log.debug("TOTAL RECORDS = " + totalRecords);
	    nextPage.addProperty(TOTAL_RECORDS, totalRecords);
	    nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY, totalRecordsAfterQuery);
	    nextPage.add("aaData", dataTablePageEntries);
	    
	    // Return the next DataTable page
    	return nextPage;
	}

	/**
	 * Given a list of solvers, creates a JsonObject that can be used to
	 * populate a datatable client-side
	 * 
	 * @param solvers
	 *            The solvers that will be the rows of the table
	 * @param totalRecords
	 *            The total number of records in the table (not the same as the
	 *            size of pairs)
	 * @param totalRecordsAfterQuery
	 *            The total number of records in the table after a given search
	 *            query was applied (if no search query, this should be the same
	 *            as totalRecords)
	 * @param syncValue
	 *            An integer value possibly given by the datatable to keep the
	 *            client and server synchronized. If one isn't present, any
	 *            integer
	 * @return A JsonObject that can be used to populate a datatable
	 * @author Eric Burns
	 */
	public static JsonObject convertSolversToJsonObject(List<Solver> solvers,
			int totalRecords, int totalRecordsAfterQuery, int syncValue) {
		/**
		 * Generate the HTML for the next DataTable page of entries
		 */
		JsonArray dataTablePageEntries = new JsonArray();
		for (Solver solver : solvers) {
			StringBuilder sb = new StringBuilder();

			// Create the hidden input tag containing the solver id
			sb.append("<input type=\"hidden\" value=\"");
			sb.append(solver.getId());
			sb.append("\" prim=\"solver\" userId=\""+solver.getUserId()+"\" deleted=\""+solver.isDeleted()+"\" recycled=\""+solver.isRecycled()+"\"/>");
			String hiddenSolverId = sb.toString();

			// Create the solver "details" link and append the hidden input
			// element
			sb = new StringBuilder();
			sb.append("<a href=\""
					+ Util.docRoot("secure/details/solver.jsp?id="));
			sb.append(solver.getId());
			sb.append("\" target=\"_blank\">");
			sb.append(solver.getName());
			RESTHelpers.addImg(sb);
			sb.append(hiddenSolverId);
			String solverLink = sb.toString();

			// Create an object, and inject the above HTML, to represent an
			// entry in the DataTable
			JsonArray entry = new JsonArray();
			entry.add(new JsonPrimitive(solverLink));
			entry.add(new JsonPrimitive(solver.getDescription()));
			entry.add(new JsonPrimitive(solver.getType().toString().toLowerCase()));
			dataTablePageEntries.add(entry);
		}

		JsonObject nextPage = new JsonObject();
		// Build the actual JSON response object and populated it with the
		// created data
		nextPage.addProperty(SYNC_VALUE, syncValue);
		nextPage.addProperty(TOTAL_RECORDS, totalRecords);
		nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY, totalRecordsAfterQuery);
		nextPage.add("aaData", dataTablePageEntries);

		// Return the next DataTable page
		return nextPage;
	}

	/**
	 * Given a list of benchmarks, creates a JsonObject that can be used to
	 * populate a datatable client-side
	 * 
	 * @param benchmarks
	 *            The benchmarks that will be the rows of the table
	 * @param totalRecords
	 *            The total number of records in the table (not the same as the
	 *            size of pairs)
	 * @param totalRecordsAfterQuery
	 *            The total number of records in the table after a given search
	 *            query was applied (if no search query, this should be the same
	 *            as totalRecords)
	 * @param syncValue
	 *            An integer value possibly given by the datatable to keep the
	 *            client and server synchronized. If one isn't present, any
	 *            integer
	 * @return A JsonObject that can be used to populate a datatable
	 * @author Eric Burns
	 */
	public static JsonObject convertBenchmarksToJsonObject(
			List<Benchmark> benchmarks, int totalRecords,
			int totalRecordsAfterQuery, int syncValue) {
		/**
		 * Generate the HTML for the next DataTable page of entries
		 */
		JsonArray dataTablePageEntries = new JsonArray();
		for (Benchmark bench : benchmarks) {
			
			StringBuilder sb = new StringBuilder();
			
			// Create the hidden input tag containing the benchmark id
			sb.append("<input name=\"bench\" type=\"hidden\" value=\"");
			sb.append(bench.getId());
			sb.append("\" prim=\"benchmark\" userId=\""+bench.getUserId()+"\"  deleted=\""+bench.isDeleted()+"\" recycled=\""+bench.isRecycled()+"\"/>");
			String hiddenBenchId = sb.toString();

			// Create the benchmark "details" link and append the hidden input
			// element
			sb = new StringBuilder();
			sb.append("<a title=\"");
			// Set the tooltip to be the benchmark's description
			sb.append(bench.getDescription());
			sb.append("\" href=\""
					+ Util.docRoot("secure/details/benchmark.jsp?id="));
			sb.append(bench.getId());
			sb.append("\" target=\"_blank\">");
			sb.append(bench.getName());
			RESTHelpers.addImg(sb);
			sb.append(hiddenBenchId);
			String benchLink = sb.toString();
			// Create the benchmark type tag
			sb = new StringBuilder();
			sb.append("<span title=\"");
			// Set the tooltip to be the benchmark type's description
			sb.append(bench.getType().getDescription());
			sb.append("\">");
			sb.append(bench.getType().getName());
			sb.append("</span>");
			String typeSpan = sb.toString();

			// Create an object, and inject the above HTML, to represent an
			// entry in the DataTable
			JsonArray entry = new JsonArray();
			entry.add(new JsonPrimitive(benchLink));
			entry.add(new JsonPrimitive(typeSpan));

			dataTablePageEntries.add(entry);
		}
		JsonObject nextPage = new JsonObject();
		// Build the actual JSON response object and populated it with the
		// created data
		nextPage.addProperty(SYNC_VALUE, syncValue);
		nextPage.addProperty(TOTAL_RECORDS, totalRecords);
		nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY, totalRecordsAfterQuery);
		nextPage.add("aaData", dataTablePageEntries);
		// Return the next DataTable page
		return nextPage;
	}

	/**
	 * Given a list of SolverStats, creates a JsonObject that can be used to
	 * populate a datatable client-side
	 * 
	 * @param stats
	 *            The SolverStats that will be the rows of the table
	 * @param totalRecords
	 *            The total number of records in the table (not the same as the
	 *            size of pairs)
	 * @param totalRecordsAfterQuery
	 *            The total number of records in the table after a given search
	 *            query was applied (if no search query, this should be the same
	 *            as totalRecords)
	 * @param syncValue
	 *            An integer value possibly given by the datatable to keep the
	 *            client and server synchronized. If one isn't present, any
	 *            integer
	 * @return A JsonObject that can be used to populate a datatable
	 * @author Eric Burns
	 */

	public static JsonObject convertSolverStatsToJsonObject(
			List<SolverStats> stats, int totalRecords,
			int totalRecordsAfterQuery, int syncValue, int spaceId, int jobId, boolean shortFormat, boolean wallTime) {
		/**
		 * Generate the HTML for the next DataTable page of entries
		 */
		JsonArray dataTablePageEntries = new JsonArray();
		for (SolverStats js : stats) {
			StringBuilder sb = new StringBuilder();

			// Create the solver link
			sb = new StringBuilder();
			sb.append("<a title=\"");
			sb.append(js.getSolver().getName());
			sb.append("\" href=\""
					+ Util.docRoot("secure/details/solver.jsp?id="));

			sb.append(js.getSolver().getId());
			sb.append("\" target=\"_blank\">");
			sb.append(js.getSolver().getName());
			RESTHelpers.addImg(sb);
			String solverLink = sb.toString();
			
			// create the configuraiton link
			sb = new StringBuilder();
			sb.append("<a class=\"configLink\" title=\"");
			sb.append(js.getConfiguration().getName());
			sb.append("\" href=\""
					+ Util.docRoot("secure/details/configuration.jsp?id="));
			sb.append(js.getConfiguration().getId());
			sb.append("\" target=\"_blank\" id=\"");
			sb.append(js.getConfiguration().getId());
			sb.append("\">");
			sb.append(js.getConfiguration().getName());
			RESTHelpers.addImg(sb);
			String configLink = sb.toString();
			if (!shortFormat) {
				
				
				sb = new StringBuilder();
				sb.append("<a href=\""
						+ Util.docRoot("secure/details/pairsInSpace.jsp?type=solved&sid="
								+ spaceId + "&configid="
								+ js.getConfiguration().getId() + "&id=" + jobId+"&stagenum="+js.getStageNumber()));
				sb.append("\" target=\"_blank\" >");
				sb.append(js.getCorrectOverCompleted());
				RESTHelpers.addImg(sb);
				String solvedLink = sb.toString();
				
				
				sb = new StringBuilder();
				sb.append("<a href=\""
						+ Util.docRoot("secure/details/pairsInSpace.jsp?type=wrong&sid="
								+ spaceId + "&configid="
								+ js.getConfiguration().getId() + "&id=" + jobId+"&stagenum="+js.getStageNumber()));
				sb.append("\" target=\"_blank\" >");
				sb.append(js.getIncorrectJobPairs());
				RESTHelpers.addImg(sb);
				String wrongLink = sb.toString();
				
				sb = new StringBuilder();
				sb.append("<a href=\""
						+ Util.docRoot("secure/details/pairsInSpace.jsp?type=resource&sid="
								+ spaceId + "&configid="
								+ js.getConfiguration().getId() + "&id=" + jobId+"&stagenum="+js.getStageNumber()));
				sb.append("\" target=\"_blank\" >");
				sb.append(js.getResourceOutJobPairs());
				RESTHelpers.addImg(sb);
				String resourceLink = sb.toString();
				
				sb = new StringBuilder();
				sb.append("<a href=\""
						+ Util.docRoot("secure/details/pairsInSpace.jsp?type=failed&sid="
								+ spaceId + "&configid="
								+ js.getConfiguration().getId() + "&id=" + jobId+"&stagenum="+js.getStageNumber()));
				sb.append("\" target=\"_blank\" >");
				sb.append(js.getFailedJobPairs());
				RESTHelpers.addImg(sb);
				String failedLink = sb.toString();
				
				sb = new StringBuilder();
				sb.append("<a href=\""
						+ Util.docRoot("secure/details/pairsInSpace.jsp?type=unknown&sid="
								+ spaceId + "&configid="
								+ js.getConfiguration().getId() + "&id=" + jobId+"&stagenum="+js.getStageNumber()));
				sb.append("\" target=\"_blank\" >");
				sb.append(js.getUnknown());
				RESTHelpers.addImg(sb);
				String unknownLink = sb.toString();
				
				sb = new StringBuilder();
				sb.append("<a href=\""
						+ Util.docRoot("secure/details/pairsInSpace.jsp?type=incomplete&sid="
								+ spaceId + "&configid="
								+ js.getConfiguration().getId() + "&id=" + jobId+"&stagenum="+js.getStageNumber()));
				sb.append("\" target=\"_blank\" >");
				sb.append(js.getIncompleteJobPairs());
				RESTHelpers.addImg(sb);
				String incompleteLink = sb.toString();
				
				// Create an object, and inject the above HTML, to represent an
				// entry in the DataTable
				JsonArray entry = new JsonArray();
				entry.add(new JsonPrimitive(solverLink));
				entry.add(new JsonPrimitive(configLink));
				entry.add(new JsonPrimitive(solvedLink));
				entry.add(new JsonPrimitive(wrongLink));
				entry.add(new JsonPrimitive(resourceLink));
				entry.add(new JsonPrimitive(failedLink));
				entry.add(new JsonPrimitive(unknownLink));
				entry.add(new JsonPrimitive(incompleteLink));
				if (wallTime) {
					
					entry.add(new JsonPrimitive(Math.round(js.getWallTime()*100)/100.0));
				} else {
					entry.add(new JsonPrimitive(Math.round(js.getCpuTime()*100)/100.0));
				}
				dataTablePageEntries.add(entry);
			} else {
				
				// Create an object, and inject the above HTML, to represent an
				// entry in the DataTable
				JsonArray entry = new JsonArray();
				entry.add(new JsonPrimitive(solverLink));
				entry.add(new JsonPrimitive(configLink));
				entry.add(new JsonPrimitive((js.getCorrectJobPairs()) +" / "+js.getCompleteJobPairs() ));
				if (wallTime) {
					entry.add(new JsonPrimitive(js.getWallTime()));
				} else {
					entry.add(new JsonPrimitive(js.getCpuTime()));
				}
				dataTablePageEntries.add(entry);
			}
			
		}

		JsonObject nextPage = new JsonObject();

		// Build the actual JSON response object and populated it with the
		// created data
		nextPage.addProperty(SYNC_VALUE, syncValue);
		nextPage.addProperty(TOTAL_RECORDS, totalRecords);
		nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY, totalRecordsAfterQuery);
		nextPage.add("aaData", dataTablePageEntries);

		// Return the next DataTable page
		return nextPage;
	}

	public static Map<Integer, String> getJobSpaceIdToSolverStatsJsonMap(int jobId, List<JobSpace> jobSpaces, int stageNumber, boolean wallclock) {
		Map<Integer, String> jobSpaceIdToSolverStatsJsonMap = new HashMap<>();
		for (JobSpace jobSpace : jobSpaces) {
			int jobSpaceId = jobSpace.getId();
			List<SolverStats> stats=Jobs.getAllJobStatsInJobSpaceHierarchy(jobId, jobSpaceId, stageNumber);
			JsonObject solverStatsJson = 
				RESTHelpers.convertSolverStatsToJsonObject(stats, stats.size(), stats.size(),1,jobSpaceId,jobId,true,wallclock);
			if (solverStatsJson != null) {
				jobSpaceIdToSolverStatsJsonMap.put(jobSpaceId, gson.toJson(solverStatsJson));
			}
		}
		return jobSpaceIdToSolverStatsJsonMap;
	}


	public static Map<Integer, String> getJobSpaceIdToSubspaceJsonMap(int jobId, List<JobSpace> jobSpaces) {
		Map<Integer, String> jobSpaceIdToSubspaceJsonMap = new HashMap<>();
		for (JobSpace jobSpace : jobSpaces) {
			String subspaceJson = RESTHelpers.getJobSpacesJson(jobSpace.getId(), jobId, false, Users.getAdmins().get(0).getId());
			jobSpaceIdToSubspaceJsonMap.put(jobSpace.getId(), subspaceJson);
		}
		return jobSpaceIdToSubspaceJsonMap;
	}

	public static String getJobSpacesTreeJson(int parentId, int jobId, int userId) {
		ValidatorStatusCode status=JobSecurity.canUserSeeJob(jobId,userId);
		if (!status.isSuccess()) {
			String output = gson.toJson(status);
			log.debug("User cannot see job, getJobSpacesJson output: "+output);
			return output;
		}

		List<JSTreeItem> subspaces = new ArrayList<JSTreeItem>();
		buildFullJsTree(jobId, subspaces);

		return gson.toJson(subspaces);
	}


	/**
	 * Builds the entire JS tree for a jobspace rather than just a single level.
	 * This method is needed for the local job page since we can't send GET requests
	 * for single levels.
	 * @author Albert Giegerich
	 */
	private static void buildFullJsTree(int jobId, List<JSTreeItem> root) {
		buildFullJsTreeHelper(0, jobId, root, true);
	}

	/**
	 * Helper method for buildFullJsTree
	 * @see org.starexec.app.RESTHelpers#buildFullJsTree
	 */
	private static void buildFullJsTreeHelper(int parentId, int jobId, List<JSTreeItem> root, boolean firstRecursion) {
		List<JobSpace> subspaces = new ArrayList<>();
		if (parentId>0) {
			subspaces = Spaces.getSubSpacesForJob(parentId,false);
		} else {
			//if the id given is 0, we want to get the root space
			Job j=Jobs.get(jobId);
			JobSpace s=Spaces.getJobSpace(j.getPrimarySpace());
			subspaces.add(s);
		}

		String className = (firstRecursion ? "rootNode" : null);

		for (JobSpace js : subspaces) {
			JSTreeItem node = null;
			if (Spaces.getCountInJobSpace(js.getId()) > 0) {
				node = new JSTreeItem(js.getName(), js.getId(), "closed", R.SPACE,js.getMaxStages(), className);
			} else {
				node = new JSTreeItem(js.getName(), js.getId(), "leaf",R.SPACE,js.getMaxStages(), className);
			}
			root.add(node);
			buildFullJsTreeHelper(js.getId(), jobId, node.getChildren(), false);
		}
	}

	public static String getJobSpacesJson(int parentId, int jobId, boolean makeSpaceTree, int userId) {	
		log.debug("got here with jobId= "+jobId+" and parent space id = "+parentId);
		List<JobSpace> subspaces=new ArrayList<JobSpace>();
		log.debug("getting job spaces for panels");
		//don't populate the subspaces if the user can't see the job
		ValidatorStatusCode status=JobSecurity.canUserSeeJob(jobId,userId);
		if (!status.isSuccess()) {
			String output = gson.toJson(status);
			log.debug("User cannot see job, getJobSpacesJson output: "+output);
			return output;
		}
		log.debug("got a request for parent space = "+parentId);
		if (parentId>0) {
			subspaces=Spaces.getSubSpacesForJob(parentId,false);
		} else {
			//if the id given is 0, we want to get the root space
			Job j=Jobs.get(jobId);
			JobSpace s=Spaces.getJobSpace(j.getPrimarySpace());
			subspaces.add(s);
		}
		
		log.debug("making next tree layer with "+subspaces.size()+" spaces");
		if (makeSpaceTree) {
			String output = gson.toJson(RESTHelpers.toJobSpaceTree(subspaces));
			log.debug("makeSpaceTree is true, getJobSpacesJson output: "+output);
			return output;
		} else {
			String output = gson.toJson(subspaces);
			log.debug("makeSpaceTree is false, getJobSpacesJson output: "+output);
			return output;
		}
	}

	public static JsonObject convertCommunityRequestsToJsonObject(List<CommunityRequest> requests, int totalRecords, int syncValue, int currentUserId) {
		/**
		 * Generate the HTML for the next DataTable page of entries
		 */
		JsonArray dataTablePageEntries = new JsonArray();
		for (CommunityRequest req : requests) {
			
			String hiddenUserId;
			StringBuilder sb = new StringBuilder();

			User user = Users.get(req.getUserId());
			// Create the hidden input tag containing the user id
			if (user.getId() == currentUserId) {
				sb.append("<input type=\"hidden\" value=\"");
				sb.append(user.getId());
				sb.append("\" name=\"currentUser\" id=\"uid" + user.getId()
						+ "\" prim=\"user\"/>");
				hiddenUserId = sb.toString();
			} else {
				sb.append("<input type=\"hidden\" value=\"");
				sb.append(user.getId());
				sb.append("\" id=\"uid" + user.getId()
						+ "\" prim=\"user\"/>");
				hiddenUserId = sb.toString();
			}
			// Create the user "details" link and append the hidden input
			// element
			sb = new StringBuilder();
			sb.append("<a href=\""
					+ Util.docRoot("secure/details/user.jsp?id="));
			sb.append(user.getId());
			sb.append("\" target=\"_blank\">");
			sb.append(user.getFullName());
			RESTHelpers.addImg(sb);
			sb.append(hiddenUserId);
			String userLink = sb.toString();
			
			//Community/space
			sb = new StringBuilder();
			Space space = Spaces.get(req.getCommunityId());
			sb = new StringBuilder();
			sb.append("<input type=\"hidden\" value=\"");
			sb.append(space.getId());
			sb.append("\" prim=\"space\" />");
			String hiddenSpaceId = sb.toString();
			// Create the space "details" link and append the hidden input
			// element
			sb = new StringBuilder();
			sb.append("<a class=\"spaceLink\" onclick=\"openSpace(");
			sb.append(space.getParentSpace());
			sb.append(",");
			sb.append(space.getId());
			sb.append(")\">");
			sb.append(space.getName());
			RESTHelpers.addImg(sb);
			sb.append(hiddenSpaceId);
			String spaceLink = sb.toString();

			sb = new StringBuilder();
			sb.append("<input class=\"acceptRequestButton\" type=\"button\" data-code=\""+req.getCode()+"\" value=\"Approve\" />");
			String approveButton = sb.toString();

			sb = new StringBuilder();
			sb.append("<input type=\"button\" class=\"declineRequestButton\""
					+ "data-code=\""+req.getCode()+"\" value=\"Decline\"/>");
			String declineButton = sb.toString();

			// Create an object, and inject the above HTML, to represent an
			// entry in the DataTable
			JsonArray entry = new JsonArray();
			entry.add(new JsonPrimitive(userLink));
			entry.add(new JsonPrimitive(spaceLink));
			entry.add(new JsonPrimitive(req.getMessage()));
			entry.add(new JsonPrimitive(approveButton));
			entry.add(new JsonPrimitive(declineButton));
			
			dataTablePageEntries.add(entry);

		}
		JsonObject nextPage = new JsonObject();
		// Build the actual JSON response object and populated it with the
		// created data
		nextPage.addProperty(SYNC_VALUE, syncValue);
		nextPage.addProperty(TOTAL_RECORDS, totalRecords);
		nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY, totalRecords);
		nextPage.add("aaData", dataTablePageEntries);

		// Return the next DataTable page
		return nextPage;
	}
	
	
	private static JsonObject convertNodesToJsonObject(List<WorkerNode> nodes, int totalRecords, int totalRecordsAfterQuery, int syncValue) {
		JsonArray dataTablePageEntries = new JsonArray();

		for(WorkerNode n : nodes) {			
			// Create an object, and inject the above HTML, to represent an
			// entry in the DataTable
			JsonArray entry = new JsonArray();
			entry.add(new JsonPrimitive(n.getName()));
			entry.add(new JsonPrimitive(n.getStatus()));

			dataTablePageEntries.add(entry);
		}
		JsonObject nextPage = new JsonObject();
		// Build the actual JSON response object and populated it with the
		// created data
		nextPage.addProperty(SYNC_VALUE, syncValue);
		nextPage.addProperty(TOTAL_RECORDS, totalRecords);
		nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY, totalRecordsAfterQuery);
		nextPage.add("aaData", dataTablePageEntries);

		// Return the next DataTable page
		return nextPage;
	}

	private static void logSpaceTree(TreeNode<Space> tree) {
		logSpaceTreeHelper(tree, "");
	}

	private static void logSpaceTreeHelper(TreeNode<Space> tree, String indent) {
		StringBuilder childrenMessage = new StringBuilder();
		childrenMessage.append(tree.getData().getName() + ": ");
		for (TreeNode<Space> child : tree) {
			childrenMessage.append(child.getData().getName() + " ");
		}

		log.debug(indent + "Descendants of space " + childrenMessage.toString()); 

		for (TreeNode<Space> child : tree) {
			logSpaceTreeHelper(child, indent+"    ");
		}
	}




	/**
	 * Get the result table for all the jobs in a space.
	 * 
	 * @param spaceId
	 *            The id of the space for which the result table is generated
	 * @param request
	 *            The http request
	 * @return JsonObject containing the result of the query
	 * @throws Exception
	 * @author Ruoyu Zhang
	 */
	public static JsonObject getResultTable(int spaceId,
			HttpServletRequest request) throws Exception {
		// Parameter validation

		HashMap<String, Integer> attrMap = RESTHelpers.getAttrMap(
				Primitive.JOB, request);
		if (null == attrMap) {
			return null;
		}

		JsonObject nextPage = new JsonObject(); // JSON object representing next
												// page for client's DataTable
												// object
		JsonArray dataTablePageEntries = null; // JSON array containing the
												// DataTable primitive entries

		List<Job> jobsToDisplay = new LinkedList<Job>();
		int totalJobsInSpace = Jobs.getCountInSpace(spaceId);

		// Retrieves the relevant Job objects to use in constructing the JSON to
		// send to the client
		jobsToDisplay = Jobs.getJobsForNextPage(0, 20, true, 1, "", spaceId);

		/**
		 * Used to display the 'total entries' information at the bottom of the
		 * DataTable; also indirectly controls whether or not the pagination
		 * buttons are toggle-able
		 */
		// If no search is provided, TOTAL_RECORDS_AFTER_QUERY = TOTAL_RECORDS
		if (attrMap.get(SEARCH_QUERY) == EMPTY) {
			attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalJobsInSpace);
		}
		// Otherwise, TOTAL_RECORDS_AFTER_QUERY < TOTAL_RECORDS
		else {
			attrMap.put(TOTAL_RECORDS_AFTER_QUERY, jobsToDisplay.size());
		}
		attrMap.put(TOTAL_RECORDS, totalJobsInSpace);

		/**
		 * Generate the HTML for the next DataTable page of entries
		 */

		dataTablePageEntries = new JsonArray();
		for (Job job : jobsToDisplay) {
			StringBuilder sb = new StringBuilder();
			String hiddenJobId;

			// Create the hidden input tag containing the job id
			sb.append("<input type=\"hidden\" value=\"");
			sb.append(job.getId());
			sb.append("\" prim=\"job\"/>");
			hiddenJobId = sb.toString();

			// Create the job "details" link and append the hidden input element
			sb = new StringBuilder();
			sb.append("<a href=\"" + Util.docRoot("secure/details/job.jsp?id="));
			sb.append(job.getId());
			sb.append("\" target=\"_blank\">");
			sb.append(job.getName());
			RESTHelpers.addImg(sb);
			sb.append(hiddenJobId);
			String jobLink = sb.toString();

			Integer score = job.getLiteJobPairStats().get("totalPairs");

			// Create an object, and inject the above HTML, to represent an
			// entry in the DataTable
			JsonArray entry = new JsonArray();
			entry.add(new JsonPrimitive(jobLink));
			entry.add(new JsonPrimitive(score));
			entry.add(new JsonPrimitive(score));

			dataTablePageEntries.add(entry);
		}

		nextPage.addProperty(SYNC_VALUE, attrMap.get(SYNC_VALUE));
		nextPage.addProperty(TOTAL_RECORDS, attrMap.get(TOTAL_RECORDS));
		nextPage.addProperty(TOTAL_RECORDS_AFTER_QUERY,
				attrMap.get(TOTAL_RECORDS_AFTER_QUERY));
		nextPage.add("aaData", dataTablePageEntries);
		return nextPage;
	}
	/**
	 * Gets all pending community requests for a given community.
	 * @param request The http request.
	 * @param communityId The community to get pending requests for.
	 * @return the new json object or null on error.
	 * @author Albert Giegerich
	 */
	public static JsonObject getNextDataTablesPageForPendingCommunityRequestsForCommunity(HttpServletRequest httpRequest, int communityId) {

		// Parameter Validation
		HashMap<String, Integer> attrMap = RESTHelpers.getAttrMapQueueReservation(httpRequest);
		if (attrMap == null) {
			return null;
		}

		int totalRequests = 0; 
		List<CommunityRequest> requests = null;
		try {
			requests = Requests.getPendingCommunityRequestsForCommunity(
						attrMap.get(STARTING_RECORD),
						attrMap.get(RECORDS_PER_PAGE),
						communityId
						);
			totalRequests = Requests.getCommunityRequestCountForCommunity(communityId);
		} catch (StarExecDatabaseException e) {
			log.error("Could not successfully get community requests for community with id="+communityId, e);
			return null;
		}


		return setupAttrMapAndConvertRequestsToJson(requests, totalRequests, attrMap, httpRequest);
	}

	/**
	 * Gets all pending community requests. 
	 * @param request The http request.
	 * @author Albert Giegerich
	 */
	public static JsonObject getNextDataTablesPageForPendingCommunityRequests(HttpServletRequest httpRequest) {
		// Parameter Validation
		HashMap<String, Integer> attrMap = RESTHelpers.getAttrMapQueueReservation(httpRequest);
		if (attrMap == null) {
			return null;
		}

		int totalRequests = 0; 
		List<CommunityRequest> requests = null;
		try {
			requests = Requests.getPendingCommunityRequests(
					attrMap.get(STARTING_RECORD),
					attrMap.get(RECORDS_PER_PAGE)
					);
			totalRequests = Requests.getCommunityRequestCount();
		} catch (StarExecDatabaseException e) {
			log.error("Could not successfully get community requests for all communities.", e);
			return null;
		}

		return setupAttrMapAndConvertRequestsToJson(requests, totalRequests, attrMap, httpRequest);
	}

	/**
	 * Provides an abstraction so the same code can be used when we want to get all pending community requests or
	 * just requests for a given community.
	 * @param request The http request.
	 * @param getAllCommunityRequests True if we want all community requests, false if we only want ones for a specific community.
	 * @param communityId The community to get pending requests for. Ignored if getAllCommunityRequests is false.
	 * @author Unknown, Albert Giegerich
	 */
	private static JsonObject setupAttrMapAndConvertRequestsToJson(List<CommunityRequest> requests, 
			int totalRequests, HashMap<String, Integer> attrMap, HttpServletRequest httpRequest) {
		/**
		 * Used to display the 'total entries' information at the bottom of the
		 * DataTable; also indirectly controls whether or not the pagination
		 * buttons are toggle-able
		 */
		// If no search is provided, TOTAL_RECORDS_AFTER_QUERY = TOTAL_RECORDS
		if (attrMap.get(SEARCH_QUERY) == EMPTY) {
			attrMap.put(TOTAL_RECORDS_AFTER_QUERY, totalRequests);
		}
		// Otherwise, TOTAL_RECORDS_AFTER_QUERY < TOTAL_RECORDS
		else {
			attrMap.put(TOTAL_RECORDS_AFTER_QUERY, requests.size());
		}
		int currentUserId = SessionUtil.getUserId(httpRequest);
		return convertCommunityRequestsToJsonObject(requests, totalRequests, attrMap.get(SYNC_VALUE), currentUserId);
	}
}
