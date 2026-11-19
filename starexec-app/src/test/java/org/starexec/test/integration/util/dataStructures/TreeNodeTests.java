package org.starexec.test.integration.util.dataStructures;

import org.junit.jupiter.api.Assertions;
import org.starexec.logger.StarLogger;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;
import org.starexec.util.dataStructures.TreeNode;

public class TreeNodeTests extends TestSequence {
	private static final StarLogger log = StarLogger.getLogger(TreeNodeTests.class);

	@Override 
	protected String getTestName() {
		return "TreeNodeTests";
	}

	@Override
	protected void teardown() {
		// No body since this does not interact with the database.
	}

	@Override
	protected void setup() {
		// No body since this does not interact with the database.
	}

	@StarexecTest
	private void AddAndRemoveChildTest() {
		TreeNode<Integer> tree = new TreeNode<>(1);
		TreeNode<Integer> child = new TreeNode<>(2);
		Assertions.assertEquals(tree.getNumberOfChildren(), 0);
		tree.addChild(child);
		Assertions.assertEquals(tree.getNumberOfChildren(), 1);
		Assertions.assertEquals(tree.getChild(0), child);	
		Assertions.assertEquals(child.getParent(), tree);
		Assertions.assertNull(tree.getParent());
		Assertions.assertEquals(tree.removeChild(0), child);
		Assertions.assertNull(child.getParent());
		Assertions.assertEquals(tree.getNumberOfChildren(), 0);
	}

	@StarexecTest
	private void IteratorTest() {
		TreeNode<Integer> tree = new TreeNode<>(null);
		for (int i = 0; i < 50; i++) {
			tree.addChild(new TreeNode<>(i));
		}
		int i = 0;
		for (TreeNode<Integer> child : tree) {
			Assertions.assertEquals(child.getData(), Integer.valueOf(i));
			i++;
		}
	}

	@StarexecTest
	private void NullChildTest() {
		TreeNode<Integer> tree = new TreeNode<>(null);
		try {
			tree.addChild(null);
			Assertions.fail("addChild should have thrown a NullPointerException.");
		} catch (NullPointerException e) {
			// NullPointerException was thrown as expected.	
		}
	}

	@StarexecTest
	private void GetIndexFromReferenceTest() {
		final TreeNode<Integer> tree = new TreeNode<>(null);
		TreeNode<Integer> child = null;
		final int childWithReferenceIndex = 4;
		for (int i = 0; i < 10; i++) {
			if (i == childWithReferenceIndex) {
				child = new TreeNode<>(i);
				tree.addChild(child);
			} else {
				tree.addChild(new TreeNode<>(i));
			}
		}

		Assertions.assertTrue(tree.hasChild(child));

		Assertions.assertEquals(childWithReferenceIndex, tree.getIndexOfChild(child));
	}


	@StarexecTest
	private void OutOfBoundsTest() {
		TreeNode<Integer> tree = new TreeNode<>(null);
		assertIndexOutOfBoundsForTree(0, tree);
		tree.addChild(new TreeNode<>(0));
		assertIndexOutOfBoundsForTree(1, tree);
		assertValueOfDataAtIndexForTreeEquals(0, tree, 0);
	}

	private static void assertValueOfDataAtIndexForTreeEquals(int index, TreeNode<Integer> tree, int value) {
		try {
			Assertions.assertEquals(tree.getChild(index).getData(), Integer.valueOf(value));
		} catch (IndexOutOfBoundsException e) {
			Assertions.fail("Index "+index+" should not be out of bounds for tree.");
		}
	}

	private static void assertIndexOutOfBoundsForTree(int index, TreeNode<Integer> tree) {
		try {
			tree.getChild(index);
			Assertions.fail("getChild should have thrown a NullPointer exception.");
		} catch (IndexOutOfBoundsException e) {
			// getChild threw an exception as expected.
		}
	}
}
