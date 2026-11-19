package org.starexec.data.to.pipelines;

import org.starexec.data.to.Identifiable;
import org.starexec.data.to.Nameable;
import org.starexec.data.to.pipelines.PipelineDependency.PipelineInputType;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Class represents the top level of a solver pipeline
 *
 * @author Eric
 */

public class SolverPipeline extends Identifiable implements Nameable {
	private int userId;
	private String name;
	private List<PipelineStage> stages = null;
	private Timestamp uploadDate;
	/**
	 * The {@code pipeline_stages.stage_id} of the primary stage, and nothing else.
	 *
	 * <p>It used to double as that stage's 1-based ordinal before persistence, under accessors
	 * named for the ordinal while the field was named for the id, and the two meanings were
	 * written and read from different places. Which stage is primary before persistence is
	 * {@link PipelineStage#isPrimary()}, on the stage itself; this is meaningful only once the
	 * stages have ids, and is 0 until then.
	 */
	private int primaryStageId;

	public SolverPipeline() {
		stages = new ArrayList<>();
	}

	public int getUserId() {
		return userId;
	}

	public void setUserId(int id) {
		this.userId = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Timestamp getUploadDate() {
		return uploadDate;
	}

	public void setUploadDate(Timestamp uploadDate) {
		this.uploadDate = uploadDate;
	}

	public List<PipelineStage> getStages() {
		return stages;
	}

	public void setStages(List<PipelineStage> stages) {
		this.stages = stages;
	}

	public void addStage(PipelineStage stage) {
		this.stages.add(stage);
	}

	/**
	 * Returns the number of benchmark inputs expected by this pipeline. This method requires that all stages and
	 * dependencies are populated to work properly.
	 *
	 * @return
	 */
	public int getRequiredNumberOfInputs() {
		int inputs = 0;
		for (PipelineStage stage : stages) {
			for (PipelineDependency dep : stage.getDependencies()) {
				if (dep.getType() == PipelineInputType.BENCHMARK) {
					inputs = Math.max(inputs, dep.getDependencyId());
				}
			}
		}

		return inputs;
	}

	public boolean usesDependencies() {
		for (PipelineStage stage : stages) {
			if (!stage.getDependencies().isEmpty()) {
				return true;
			}
		}

		return false;
	}

	public int getPrimaryStageId() {
		return primaryStageId;
	}

	public void setPrimaryStageId(int primaryStageId) {
		this.primaryStageId = primaryStageId;
	}
}
