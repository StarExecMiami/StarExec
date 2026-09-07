package org.starexec.test.unit.database;

import org.junit.Test;
import org.starexec.data.database.Pipelines;
import org.starexec.data.to.pipelines.PipelineStage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Which stage of a pipeline is the primary one.
 *
 * <p>Selection used to compare each stage's 1-based position against a field holding a
 * persisted stage id, so the stage it chose depended on how many stages other pipelines had
 * already stored. These cases fix the selection to the stages themselves.
 */
public class PrimaryStageSelectionTest {

	@Test
	public void theMarkedStageIsChosenWhereverItSits() {
		List<PipelineStage> stages = stages(4);
		stages.get(2).setPrimary(true);

		assertSame("the marked stage must be chosen", stages.get(2),
				Pipelines.selectPrimaryStage(stages));
	}

	@Test
	public void theFirstStageIsTheDefault() {
		List<PipelineStage> stages = stages(3);

		assertSame("with none marked, the first stage is primary", stages.get(0),
				Pipelines.selectPrimaryStage(stages));
		assertTrue("the default must be recorded on the stage, not just returned",
				stages.get(0).isPrimary());
	}

	/**
	 * The default must not silently promote a second stage. A pipeline whose primary is the
	 * first stage is indistinguishable from one that named it explicitly, which is the point.
	 */
	@Test
	public void theDefaultLeavesEveryOtherStageAlone() {
		List<PipelineStage> stages = stages(3);
		Pipelines.selectPrimaryStage(stages);

		assertTrue(stages.get(0).isPrimary());
		for (PipelineStage stage : stages.subList(1, stages.size())) {
			assertTrue("only one stage may be primary", !stage.isPrimary());
		}
	}

	@Test
	public void anExplicitFirstStageIsNotDisturbed() {
		List<PipelineStage> stages = stages(2);
		stages.get(0).setPrimary(true);

		assertSame(stages.get(0), Pipelines.selectPrimaryStage(stages));
	}

	@Test
	public void aSingleStagePipelineIsItsOwnPrimary() {
		List<PipelineStage> stages = stages(1);

		assertSame(stages.get(0), Pipelines.selectPrimaryStage(stages));
		assertTrue(stages.get(0).isPrimary());
	}

	/**
	 * Two primaries is a caller error, not something to resolve by picking one: the schema
	 * allows at most one, and a pipeline that claims two has no defensible answer.
	 */
	@Test
	public void twoPrimariesIsRefusedRatherThanResolved() {
		List<PipelineStage> stages = stages(3);
		stages.get(0).setPrimary(true);
		stages.get(2).setPrimary(true);

		assertNull(Pipelines.selectPrimaryStage(stages));
	}

	private List<PipelineStage> stages(int count) {
		List<PipelineStage> stages = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			PipelineStage stage = new PipelineStage();
			stage.setConfigId(100 + i);
			stages.add(stage);
		}
		return stages;
	}
}
