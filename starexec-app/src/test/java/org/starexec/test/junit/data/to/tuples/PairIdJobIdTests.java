package org.starexec.test.junit.data.to.tuples;

import org.junit.Test;
import org.starexec.data.to.tuples.PairIdJobId;
import org.junit.jupiter.api.Assertions;


public class PairIdJobIdTests {

    @Test
    public void equalityTest() {
        PairIdJobId first = new PairIdJobId(1, 1);
        PairIdJobId second = new PairIdJobId(1, 1);

        Assertions.assertEquals(first, second);
        Assertions.assertEquals(second, first);
    }

    @Test
    public void rightInequalityTest() {
        PairIdJobId first = new PairIdJobId(1, 2);
        PairIdJobId second = new PairIdJobId(1, 1);
        Assertions.assertNotEquals(first, second);
    }

    @Test
    public void leftInequalityTest() {
        PairIdJobId first = new PairIdJobId(2, 1);
        PairIdJobId second = new PairIdJobId(1, 1);
        Assertions.assertNotEquals(first, second);
    }

    @Test
    public void bothInequalityTest() {
        PairIdJobId first = new PairIdJobId(2, 2);
        PairIdJobId second = new PairIdJobId(1, 1);
        Assertions.assertNotEquals(first, second);
    }

    @Test
    public void hashTest() {
        PairIdJobId first = new PairIdJobId(1, 1);
        PairIdJobId second = new PairIdJobId(1, 1);
        Assertions.assertEquals(first.hashCode(), second.hashCode());
    }




}
