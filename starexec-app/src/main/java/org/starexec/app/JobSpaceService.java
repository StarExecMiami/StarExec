package org.starexec.app;

import com.google.gson.Gson;
import org.starexec.constants.R;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Spaces;
import org.starexec.data.security.JobSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobSpace;
import org.starexec.logger.StarLogger;
import org.starexec.data.to.JSTreeItem;

import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Service class for handling JobSpace related operations.
 * Refactored from RESTHelpers to improve separation of concerns and
 * testability.
 */
public class JobSpaceService {
    private static final StarLogger log = StarLogger.getLogger(JobSpaceService.class);
    private static final Gson gson = new Gson();

    /**
     * Retrieves job spaces for a given job and parent space.
     *
     * @param parentId      The ID of the parent job space.
     * @param jobId         The ID of the job.
     * @param makeSpaceTree Whether to return a tree structure (JSTreeItem) or a
     *                      list of JobSpaces.
     * @param userId        The ID of the user making the request.
     * @return A Response object containing the JSON result or an error status.
     */
    public Response getJobSpaces(int parentId, int jobId, boolean makeSpaceTree, int userId) {
        // Security Check
        ValidatorStatusCode status = JobSecurity.canUserSeeJob(jobId, userId);
        if (!status.isSuccess()) {
            // Return 404 Not Found to prevent IDOR enumeration (as per security review)
            // The status message is included in the body for debugging/logging on client if
            // needed,
            // but the status code 404 implies "Not Found" or "Access Denied"
            // indistinguishably.
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(gson.toJson(status))
                    .type("application/json")
                    .build();
        }

        List<JobSpace> subspaces = getSubspacesOrRootSpace(parentId, jobId);

        if (makeSpaceTree) {
            List<JSTreeItem> tree = toJobSpaceTree(subspaces);
            return Response.ok(gson.toJson(tree)).type("application/json").build();
        } else {
            return Response.ok(gson.toJson(subspaces)).type("application/json").build();
        }
    }

    private List<JobSpace> getSubspacesOrRootSpace(int parentId, int jobId) {
        List<JobSpace> subspaces = new ArrayList<>();
        if (parentId > 0) {
            subspaces = Spaces.getSubSpacesForJob(parentId, false);
        } else {
            // if the id given is 0, we want to get the root space
            Job j = Jobs.get(jobId);
            if (j != null) {
                JobSpace s = Spaces.getJobSpace(j.getPrimarySpace());
                if (s != null) {
                    subspaces.add(s);
                }
            }
        }
        return subspaces;
    }

    private List<JSTreeItem> toJobSpaceTree(List<JobSpace> jobSpaceList) {
        List<JSTreeItem> list = new LinkedList<>();
        for (JobSpace space : jobSpaceList) {
            String isOpen = Spaces.getCountInJobSpace(space.getId()) > 0 ? "closed" : "leaf";
            list.add(new JSTreeItem(space.getName(), space.getId(), isOpen, R.SPACE, space.getMaxStages()));
        }
        return list;
    }
}
