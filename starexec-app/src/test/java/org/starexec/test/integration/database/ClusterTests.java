package org.starexec.test.integration.database;

import org.junit.jupiter.api.Assertions;
import org.starexec.constants.R;
import org.starexec.data.database.Cluster;
import org.starexec.data.database.Queues;
import org.starexec.data.to.Queue;
import org.starexec.data.to.WorkerNode;
import org.starexec.test.TestUtil;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for org.starexec.data.database.Cluster.java
 * @author Eric
 *
 */
public class ClusterTests extends TestSequence {
	Queue allQueue=null;
	Queue emptyQueue = null; // in database, but not a part of the backend
	WorkerNode n=null;		 // first node in all.q, which must always have some node
	WorkerNode fakeNode = null; // in database, but not actually a part of the backend
	@Override
	protected String getTestName() {
		return "ClusterTests";
	}
	
	@StarexecTest
	private void getIdByNameTest() {
		Assertions.assertEquals(n.getId(),Cluster.getNodeIdByName(n.getName()));
	}
	
	@StarexecTest
	private void getNameByIdTest() {
		Assertions.assertEquals(n.getName(), Cluster.getNodeNameById(n.getId()));
	}
	
	@StarexecTest
	private void getAllNodesTest() {
		List<WorkerNode> nodes=Cluster.getAllNodes();
		Assertions.assertNotNull(nodes);
		List<Integer> nodeIds = new ArrayList<>();
		for (WorkerNode node : nodes){
			nodeIds.add(node.getId());
		}
		Assertions.assertTrue(nodeIds.contains(n.getId()));
		//should not contain an inactive node
		Assertions.assertFalse(nodeIds.contains(fakeNode.getId()));

	}
	
	@StarexecTest
	private void getQueueForNode() {
		Assertions.assertEquals(allQueue.getId(),Cluster.getQueueForNode(n.getId()).getId());
	}
	
	@StarexecTest
	private void getQueueForNodeNoQueueTest() {
		Assertions.assertNull(Cluster.getQueueForNode(fakeNode.getId()));

	}
	
	@StarexecTest
	private void getNodesForQueue() {
		List<WorkerNode> nodes = Cluster.getNodesForQueue(allQueue.getId());
		Assertions.assertNotNull(nodes);
		boolean containsNode=false;
		
		for (WorkerNode node : nodes) {
			if (node.getId()==n.getId()) {
				containsNode=true;
			}
		}
		Assertions.assertTrue(containsNode);
	}
	
	@StarexecTest
	private void setAllNodesStatusTest() {
		Assertions.assertTrue(Cluster.setNodeStatus(R.NODE_STATUS_INACTIVE));
		for (WorkerNode n : Cluster.getAllNodes()) {
			Assertions.assertEquals(R.NODE_STATUS_INACTIVE, n.getStatus());
		}
		Assertions.assertTrue(Cluster.setNodeStatus(R.NODE_STATUS_ACTIVE));
	}
	
	@StarexecTest
	private void getNodeDetailsTest() {
		WorkerNode node=Cluster.getNodeDetails(n.getId());
		Assertions.assertNotNull(node);
		Assertions.assertEquals(node.getName(),n.getName());
	}
	
	@StarexecTest
	private void setNodeStatusTest() {
		Assertions.assertTrue(Cluster.setNodeStatus(fakeNode.getName(), "fakestatus"));
		Assertions.assertEquals("fakestatus", Cluster.getNodeDetails(fakeNode.getId()).getStatus());
		Assertions.assertTrue(Cluster.setNodeStatus(fakeNode.getName(), R.NODE_STATUS_INACTIVE));
	}
	
	@StarexecTest
	private void getEmptyQueueJobsTest() {
		Assertions.assertEquals(0, Cluster.getJobsRunningOnQueue(emptyQueue.getId()).size());
	}
	
	@StarexecTest
	private void getNonAttachedNodesTest() {
		List<WorkerNode> nodes = Cluster.getNonAttachedNodes(emptyQueue.getId());
		Assertions.assertEquals(Cluster.getAllNodes().size(), nodes.size());
	}
	
	@StarexecTest
	private void getNonAttachedNodesExcludeNodeTest() {
		List<WorkerNode> nodes = Cluster.getNonAttachedNodes(allQueue.getId());
		// the node n should be excluded
		boolean found = false;
		for (WorkerNode node : nodes) {
			found = found || n.getId()==node.getId();
		}
		Assertions.assertFalse(found);
	}
	
	@StarexecTest
	private void getNodeIdByNameTest() {
		Assertions.assertEquals(fakeNode.getId(), Cluster.getNodeIdByName(fakeNode.getName()));
	}
	
	@StarexecTest
	private void getNodeIdByNameBadNodeTest() {
		Assertions.assertEquals(-1, Cluster.getNodeIdByName(TestUtil.getRandomAlphaString(50)));
	}

	@Override
	protected void setup() throws Exception {
		allQueue=Queues.get(Queues.getIdByName(R.DEFAULT_QUEUE_NAME));
		n=Queues.getNodes(allQueue.getId()).get(0);
		Cluster.addNodeIfNotExists("faketestnode");
		fakeNode = Cluster.getNodeDetails(Cluster.getNodeIdByName("faketestnode"));
		emptyQueue = Queues.get(Queues.add(TestUtil.getRandomAlphaString(50), 10, 10));
		
	}

	@Override
	protected void teardown() throws Exception {
		Cluster.deleteNode(fakeNode.getId());
		Queues.delete(emptyQueue.getId());
	}
	
}
