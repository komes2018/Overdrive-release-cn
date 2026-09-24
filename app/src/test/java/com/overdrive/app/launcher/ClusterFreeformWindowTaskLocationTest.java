package com.overdrive.app.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class ClusterFreeformWindowTaskLocationTest {

    @Test
    public void wrongDisplayTaskWinsOverAnExistingTargetTask() {
        ClusterFreeformWindow.TaskLocation selected =
                ClusterFreeformWindow.selectTaskLocation(
                        Arrays.asList(
                                new ClusterFreeformWindow.TaskLocation(true, 11, 3),
                                new ClusterFreeformWindow.TaskLocation(true, 22, 0)),
                        3);

        assertTrue(selected.known);
        assertEquals(22, selected.taskId);
        assertEquals(0, selected.displayId);
    }

    @Test
    public void unknownDisplayIsConservativeAndAbsenceIsKnown() {
        ClusterFreeformWindow.TaskLocation unknownDisplay =
                ClusterFreeformWindow.selectTaskLocation(
                        Arrays.asList(
                                new ClusterFreeformWindow.TaskLocation(true, 11, 3),
                                new ClusterFreeformWindow.TaskLocation(true, 22, -1)),
                        3);
        assertEquals(22, unknownDisplay.taskId);
        assertEquals(-1, unknownDisplay.displayId);

        ClusterFreeformWindow.TaskLocation absent =
                ClusterFreeformWindow.selectTaskLocation(
                        Collections.emptyList(), 3);
        assertTrue(absent.known);
        assertEquals(-1, absent.taskId);

        ClusterFreeformWindow.TaskLocation unavailable =
                ClusterFreeformWindow.selectTaskLocation(null, 3);
        assertFalse(unavailable.known);
    }
}
