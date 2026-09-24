package com.overdrive.app.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ClusterCastForegroundTest {

    @Test
    public void resumedPackageIsScopedToTheExactDisplay() {
        String dump =
                "Display #30 (activities from top to bottom):\n"
                + "  topResumedActivity=ActivityRecord{a u0 com.wrong/.MainActivity t1}\n"
                + "Display #3 (activities from top to bottom):\n"
                + "  RootTask #122\n"
                + "    mResumedActivity: ActivityRecord{b u0 "
                + "com.telenav.app.arp/com.telenav.arp.module.map.MainActivity t2}\n"
                + "Display #0 (activities from top to bottom):\n"
                + "  mResumedActivity: ActivityRecord{c u0 com.launcher/.Home t3}\n";

        assertEquals(
                "com.telenav.app.arp",
                ClusterCast.resumedPackageInActivityDump(dump, 3));
    }

    @Test
    public void parserExposesAnOemSameDisplayTakeover() {
        String dump =
                "Display #3 (activities from top to bottom):\n"
                + "  topResumedActivity=ActivityRecord{a u0 "
                + "com.byd.launchermap/.automap.meter.MeterActivity t122}\n"
                + "  Task{b #121 type=standard visible=true}\n"
                + "    com.telenav.app.arp/.MainActivity\n";

        assertEquals(
                "com.byd.launchermap",
                ClusterCast.resumedPackageInActivityDump(dump, 3));
    }

    @Test
    public void unreadableStateNeverBecomesAPlacementOnlySuccess() {
        String noResumedMarker =
                "Display #3 (activities from top to bottom):\n"
                + "  Task{b #121 type=standard visible=true}\n";
        assertNull(
                ClusterCast.resumedPackageInActivityDump(
                        noResumedMarker, 3));

        String explicitNull =
                "Display #3 (activities from top to bottom):\n"
                + "  mResumedActivity: null\n";
        assertEquals(
                "",
                ClusterCast.resumedPackageInActivityDump(explicitNull, 3));
    }
}
