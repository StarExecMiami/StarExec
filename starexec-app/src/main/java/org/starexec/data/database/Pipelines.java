package org.starexec.data.database;

import org.starexec.data.to.pipelines.PipelineDependency;
import org.starexec.data.to.pipelines.PipelineDependency.PipelineInputType;
import org.starexec.data.to.pipelines.PipelineStage;
import org.starexec.data.to.pipelines.SolverPipeline;
import org.starexec.logger.StarLogger;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Class responsible for inserting and removing pipelines from the database
 *
 * @author Eric
 */
public class Pipelines {
	private static final StarLogger log = StarLogger.getLogger(Pipelines.class);

	/**
	 * Returns a list of dependencies that go with the pipeline stage with the given ID. Dependencies will be returned
	 * in order of their input_number.
	 *
	 * @param stageId The ID of a pipeline_stage
	 * @param con An open SQL connection to make the call on
	 * @return A list of all the dependencies for the given stage
	 */
	public static List<PipelineDependency> getDependenciesForStage(int stageId, Connection con) {
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			ps = con.prepareStatement("SELECT * FROM starexec.GetDependenciesForPipelineStage(?)");
			ps.setInt(1, stageId);
			results = ps.executeQuery();
			List<PipelineDependency> answers = new ArrayList<>();
			while (results.next()) {
				PipelineDependency dep = new PipelineDependency();
				dep.setStageId(stageId);
				dep.setDependencyId(results.getInt("input_id"));
				dep.setType(PipelineInputType.valueOf(results.getInt("input_type")));
				dep.setInputNumber(results.getInt("input_number"));
				answers.add(dep);
			}
			return answers;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(ps);
			Common.safeClose(results);
		}
		return null;
	}

	/**
	 * Returns all the dependencies that are associated with the given job pair, organized by stage number.
	 * Dependencies
	 * in each list are ordered by input number
	 *
	 * @param pairId The pair to get dependencies for.
	 * @param con The open connection make the call on.
	 * @return A HashMap that maps stage numbers to lists of pipeline dependencies.
	 */
	public static HashMap<Integer, List<PipelineDependency>> getDependenciesForJobPair(int pairId, Connection con) {
		PreparedStatement ps = null;
		ResultSet results = null;

		try {
			ps = con.prepareStatement("SELECT * FROM starexec.GetDependenciesForJobPair(?)");
			ps.setInt(1, pairId);
			results = ps.executeQuery();
			HashMap<Integer, List<PipelineDependency>> answers = new HashMap<>();
			while (results.next()) {
				PipelineDependency dep = new PipelineDependency();
				dep.setStageId(results.getInt("stage_id"));
				dep.setDependencyId(results.getInt("input_id"));
				dep.setType(PipelineInputType.valueOf(results.getInt("input_type")));
				dep.setInputNumber(results.getInt("input_number"));

				if (!answers.containsKey(dep.getStageId())) {
					answers.put(dep.getStageId(), new ArrayList<>());
				}

				answers.get(dep.getStageId()).add(dep);
			}
			return answers;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(ps);
			Common.safeClose(results);
		}
		return null;
	}

	/**
	 * Retrieves a list of stages for the given pipeline. Dependencies ARE populated
	 *
	 * @param pipeId The ID of the solver pipeline to get stages before
	 * @param con An open SQL connection to make the call on
	 * @return The list of pipeline stages
	 */
	public static List<PipelineStage> getStagesForPipeline(int pipeId, Connection con) {
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			ps = con.prepareStatement("SELECT * FROM starexec.GetStagesByPipelineId(?)");
			ps.setInt(1, pipeId);
			results = ps.executeQuery();
			List<PipelineStage> stages = new ArrayList<>();
			while (results.next()) {
				PipelineStage stage = new PipelineStage();
				stage.setPipelineId(pipeId);
				stage.setConfigId(results.getInt("config_id"));
				stage.setId(results.getInt("stage_id"));
				stage.setNoOp(results.getBoolean("is_noop"));
				stage.setDependencies(getDependenciesForStage(stage.getId(), con));
				stages.add(stage);
			}
			return stages;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(ps);
			Common.safeClose(results);
		}
		return null;
	}

	/**
	 * Gets a solver pipeline from the database, including all the pipeline's stages and dependencies
	 *
	 * @param id The ID of the pipeline
	 * @return The pipeline, with everything populated, or null on error
	 */
	public static SolverPipeline getFullPipeline(int id) {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetPipelineById(?)");
			ps.setInt(1, id);
			results = ps.executeQuery();
			if (results.next()) {
				SolverPipeline pipe = new SolverPipeline();
				pipe.setId(id);
				pipe.setName(results.getString("name"));
				pipe.setUploadDate(results.getTimestamp("uploaded"));
				pipe.setUserId(results.getInt("userId"));
				pipe.setPrimaryStageId(results.getInt("primaryStageId"));
				pipe.setStages(getStagesForPipeline(id, con));
				// GetStagesByPipelineId does not carry the primary flag -- the pipeline row
				// owns it -- so a reloaded pipeline would otherwise have every stage
				// unflagged, which is a different shape from one built from XML.
				if (pipe.getStages() != null) {
					for (PipelineStage stage : pipe.getStages()) {
						stage.setPrimary(stage.getId() == pipe.getPrimaryStageId());
					}
				}
				return pipe;
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
			Common.safeClose(results);
		}
		return null;
	}

	/**
	 * Adds the given pipeline dependency to the database. The stage associated with this dependency must already exist
	 *
	 * @param dep The dependency to add
	 * @param con An open SQL connection to make the call on
	 */
	public static void addDependencyToDatabase(PipelineDependency dep, Connection con) {
		PreparedStatement ps = null;
		try {
			ps = con.prepareStatement("SELECT starexec.AddPipelineDependency(?,?,?,?)");
			ps.setInt(1, dep.getStageId());
			ps.setInt(2, dep.getDependencyId());
			log.debug("adding dependency with type " + dep.getType());
			ps.setInt(3, dep.getType().getVal());
			ps.setInt(4, dep.getInputNumber());
			boolean hasResultSet = ps.execute();
			if (hasResultSet) {
				ResultSet rs = ps.getResultSet();
				while (rs.next()) {
					// consume the result set
				}
				Common.safeClose(rs);
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(ps);
		}
	}

	/**
	 * Adds a solver pipeline stage to the database, including adding all dependencies present in the object
	 *
	 * @param stage A fully populated solver pipeline object, including dependencies
	 * @param con An open SQL connection to make this call on
	 */
	public static void addPipelineStageToDatabase(PipelineStage stage, Connection con) {
		PreparedStatement stmt = null;
		ResultSet rs = null;
		try {
			stmt = con.prepareStatement("SELECT AddPipelineStage(?,?,?,?)");
			stmt.setInt(1, stage.getPipelineId());
			if (stage.isNoOp()) {
				stmt.setNull(2, java.sql.Types.INTEGER);
			} else {
				stmt.setInt(2, stage.getConfigId());
			}
			stmt.setBoolean(3, stage.isPrimary());
			stmt.setBoolean(4, stage.isNoOp());
			log.debug("trying to use the config id = " + stage.getConfigId());
			rs = stmt.executeQuery();
			rs.next();
			int id = rs.getInt(1);
			stage.setId(id);

			for (PipelineDependency dep : stage.getDependencies()) {
				dep.setStageId(stage.getId());
				addDependencyToDatabase(dep, con);
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(rs);
			Common.safeClose(stmt);
		}
	}

	/**
	 * The stage a pipeline's {@code primary_stage_id} must end up pointing at.
	 *
	 * <p>Which stage is primary is the stage's own flag. Persistence used to compare each
	 * stage's 1-based position against the pipeline's primary-stage field, which holds a
	 * persisted stage id -- so it selected whichever stage happened to sit at the position
	 * numerically equal to some earlier pipeline's stage id, or no stage at all.
	 *
	 * <p>Applies the default {@code batchJobSchema.xsd} documents -- if no stage is marked, the
	 * first one is primary -- marking the chosen stage so the flag and the pipeline agree. This
	 * is the one place every caller passes through, so no pipeline is persisted without one.
	 *
	 * @param stages a non-empty stage list
	 * @return the primary stage, or null if more than one stage claims it
	 */
	public static PipelineStage selectPrimaryStage(List<PipelineStage> stages) {
		PipelineStage primary = null;
		for (PipelineStage stage : stages) {
			if (stage.isPrimary()) {
				if (primary != null) {
					return null;
				}
				primary = stage;
			}
		}
		if (primary == null) {
			primary = stages.get(0);
			primary.setPrimary(true);
		}
		return primary;
	}

	/**
	 * Adds a solver pipeline to the database, including adding all stages and dependencies present in the object
	 *
	 * @param pipe A fully populated solver pipeline object, including dependencies
	 * @return The ID of the pipeline object, or -1 on failure. The ID will also be set in the given pipeline object on
	 * success. All stage IDs will also be set, as will the pipeline's primary stage ID
	 */
	public static int addPipelineToDatabase(SolverPipeline pipe) {
		Connection con = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;
		try {
			List<PipelineStage> stages = pipe.getStages();
			if (stages == null || stages.isEmpty()) {
				log.error("Refusing to persist pipeline '" + pipe.getName() + "' with no stages");
				return -1;
			}

			PipelineStage primary = selectPrimaryStage(stages);
			if (primary == null) {
				log.error("Refusing to persist pipeline '" + pipe.getName() +
				          "': more than one stage is marked primary");
				return -1;
			}

			con = Common.getConnection();
			stmt = con.prepareStatement("SELECT AddPipeline(?,?)");
			stmt.setInt(1, pipe.getUserId());
			stmt.setString(2, pipe.getName());
			rs = stmt.executeQuery();
			rs.next();
			int id = rs.getInt(1);
			pipe.setId(id);

			for (PipelineStage stage : stages) {
				stage.setPipelineId(pipe.getId());
				addPipelineStageToDatabase(stage, con);
			}
			// The field means the persisted id, so it is only writable once there is one.
			pipe.setPrimaryStageId(primary.getId());

			return id;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(rs);
			Common.safeClose(stmt);
		}

		return -1;
	}

	/**
	 * Returns all of the solver pipelines that are used in the given job.
	 *
	 * @param jobId The ID of the job to get all pipelines for
	 * @return A list of Solver Pipelines that are referenced by the job, or null on error
	 */
	public static List<SolverPipeline> getPipelinesByJob(int jobId) {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		List<Integer> pipeIds = new ArrayList<>();
		List<SolverPipeline> pipes = new ArrayList<>();
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetPipelineIdsByJob(?)");
			ps.setInt(1, jobId);
			results = ps.executeQuery();
			while (results.next()) {
				pipeIds.add(results.getInt("id"));
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return null;
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
			Common.safeClose(results);
		}
		for (Integer i : pipeIds) {
			pipes.add(Pipelines.getFullPipeline(i));
		}

		return pipes;
	}

	/**
	 * Deletes a pipeline from the database, including deletion of all stages and pipeline_dependencies entries
	 *
	 * @param pipelineId The ID of the pipeline being deleted
	 */
	public static void deletePipelineFromDatabase(int pipelineId) {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.DeletePipeline(?)");
			ps.setInt(1, pipelineId);
			boolean hasResultSet = ps.execute();
			if (hasResultSet) {
				ResultSet rs = ps.getResultSet();
				while (rs.next()) {
					// consume the result set
				}
				Common.safeClose(rs);
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
	}
}
