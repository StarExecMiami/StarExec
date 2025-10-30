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
				pipe.setUserId(results.getInt("user_id"));
				pipe.setPrimaryStageNumber(results.getInt("primary_stage_id"));
				pipe.setStages(getStagesForPipeline(id, con));
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
			ps.execute();
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
	 * Adds a solver pipeline to the database, including adding all stages and dependencies present in the object
	 *
	 * @param pipe A fully populated solver pipeline object, including dependencies
	 * @return The ID of the pipeline object, or -1 on failure. The ID will also be set in the given pipeline object on
	 * success. All stage IDs will also be set
	 */
	public static int addPipelineToDatabase(SolverPipeline pipe) {
		Connection con = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;
		try {
			con = Common.getConnection();
			stmt = con.prepareStatement("SELECT AddPipeline(?,?)");
			stmt.setInt(1, pipe.getUserId());
			stmt.setString(2, pipe.getName());
			rs = stmt.executeQuery();
			rs.next();
			int id = rs.getInt(1);
			pipe.setId(id);

			int number = 1;
			for (PipelineStage stage : pipe.getStages()) {
				stage.setPipelineId(pipe.getId());
				if (number == pipe.getPrimaryStageNumber()) {
					stage.setPrimary(true);
				} else {
					stage.setPrimary(false);
				}
				addPipelineStageToDatabase(stage, con);
				number++;
			}


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
			ps.execute();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
	}
}
