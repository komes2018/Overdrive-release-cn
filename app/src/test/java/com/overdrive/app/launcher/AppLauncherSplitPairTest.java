package com.overdrive.app.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppLauncherSplitPairTest {

    @Test
    public void splitPairRequiresTwoDistinctValidPackages() {
        assertTrue(AppLauncher.isValidSplitPair("com.maps.app", "com.music.app"));
        assertFalse(AppLauncher.isValidSplitPair("com.maps.app", "com.maps.app"));
        assertFalse(AppLauncher.isValidSplitPair("com.maps.app", "bad package"));
    }

    @Test
    public void splitStackLookupIsScopedToDisplayAndPane() {
        String dump =
                "Display #10 (activities from top to bottom):\n"
                + "  Stack #9: type=standard mode=split-screen-secondary\n"
                + "    com.music.app/.ClusterActivity\n"
                + "Display #0 (activities from top to bottom):\n"
                + "  Stack #7: type=standard mode=split-screen-primary\n"
                + "    com.maps.app/.MainActivity\n"
                + "  Stack #6: type=home mode=split-screen-secondary\n"
                + "  Stack #8:\n"
                + "    mWindowingMode=split-screen-secondary\n"
                + "    mActivityType=standard\n"
                + "    com.music.app/.MainActivity\n";

        assertEquals(7, AppLauncher.splitStackIdInDump(
                dump, 0, 3, "com.maps.app"));
        assertEquals(8, AppLauncher.splitStackIdInDump(
                dump, 0, 4, "com.music.app"));
        assertEquals(-1, AppLauncher.splitStackIdInDump(
                dump, 0, 3, "com.music.app"));
        assertEquals(8, AppLauncher.splitStackIdInDump(
                dump, 0, 4, null));
        assertEquals(-1, AppLauncher.splitStackIdInDump(
                dump, 0, 5, "com.music.app"));
    }

    @Test
    public void splitStackLookupRejectsLastNonStandardStackBeforeNextDisplay() {
        String dump =
                "Display #0 (activities from top to bottom):\n"
                + "  Stack #6: type=home mode=split-screen-secondary\n"
                + "Display #1 (activities from top to bottom):\n"
                + "  Stack #9: type=standard mode=fullscreen\n";

        assertEquals(-1, AppLauncher.splitStackIdInDump(dump, 0, 4, null));
    }

    @Test
    public void splitStackLookupAcceptsNumericModesWithoutPrefixMatches() {
        String dump =
                "Display #0 (activities from top to bottom):\n"
                + "  Stack #6:\n"
                + "    mWindowingMode=31\n"
                + "    mActivityType=10\n"
                + "    com.maps.app/.WrongActivity\n"
                + "  Stack #7:\n"
                + "    mWindowingMode=3\n"
                + "    mActivityType=1\n"
                + "    com.maps.app/.MainActivity\n";

        assertEquals(7, AppLauncher.splitStackIdInDump(
                dump, 0, 3, "com.maps.app"));
    }

    @Test
    public void taskLookupAttributesAnExactPackageToItsTask() {
        String dump =
                "* TaskRecord{aaa #40 A=xcom.maps.app U=0}\n"
                + "    xcom.maps.app/.MainActivity\n"
                + "* TaskRecord{bbb #41 A=com.mapsxapp U=0}\n"
                + "    com.mapsxapp/.MainActivity\n"
                + "* Task{bbb #42 type=standard}\n"
                + "    com.maps.app/.MainActivity\n";

        assertEquals(42, AppLauncher.taskIdInDump(dump, "com.maps.app"));
        assertEquals(-1, AppLauncher.taskIdInDump(dump, "bad package"));
    }

    @Test
    public void taskLookupSeparatesConfirmedAbsenceFromLookupFailure() {
        String dumpWithTasks =
                "* Task{aaa #40 type=standard}\n"
                + "    com.other.app/.MainActivity\n";

        // Parsed dump with other tasks but none for the package: CONFIRMED absent.
        assertEquals(-1, AppLauncher.taskIdInDump(dumpWithTasks, "com.maps.app"));
        // Shell/dumpsys failure: unverifiable, never "absent".
        assertEquals(AppLauncher.TASK_LOOKUP_FAILED,
                AppLauncher.taskIdInDump(null, "com.maps.app"));
        // Output with ZERO task headers (AMS not up / permission error text):
        // does not look like a task dump, so also unverifiable.
        assertEquals(AppLauncher.TASK_LOOKUP_FAILED,
                AppLauncher.taskIdInDump(
                        "Can't find service: activity\n", "com.maps.app"));
    }

    @Test
    public void splitStackLookupDoesNotMatchAPackageNameSuffix() {
        String dump =
                "Display #0 (activities from top to bottom):\n"
                + "  Stack #6: type=standard mode=split-screen-primary\n"
                + "    xcom.maps.app/.WrongActivity\n"
                + "  Stack #7: type=standard mode=split-screen-primary\n"
                + "    com.maps.app/.MainActivity\n";

        assertEquals(7, AppLauncher.splitStackIdInDump(
                dump, 0, 3, "com.maps.app"));
    }

    @Test
    public void fullscreenLookupDoesNotReuseAnActiveSplitOrHomeStack() {
        String dump =
                "Display #1 (activities from top to bottom):\n"
                + "  Stack #9: type=standard mode=fullscreen\n"
                + "Display #0 (activities from top to bottom):\n"
                + "  Stack #8: type=standard mode=split-screen-secondary\n"
                + "  Stack #7: type=standard mode=split-screen-primary\n"
                + "  Stack #6: type=home mode=fullscreen\n"
                + "  Stack #5: type=standard mode=fullscreen\n";

        assertEquals(5, AppLauncher.fullscreenStackIdInDump(dump, 0));
        assertEquals(9, AppLauncher.fullscreenStackIdInDump(dump, 1));
    }

    @Test
    public void ghostLookupIgnoresASeedOnTheClusterDisplay() {
        String dump =
                "Display #1 (activities from top to bottom):\n"
                + "  Stack #9: type=standard mode=split-screen-primary\n"
                + "    com.overdrive.app/.launcher.AppLauncherGhostActivity\n"
                + "Display #0 (activities from top to bottom):\n"
                + "  Stack #7: type=standard mode=split-screen-primary\n"
                + "    com.overdrive.app/.launcher.AppLauncherGhostActivity\n";

        assertEquals(7, AppLauncher.ghostStackIdInDump(dump));
    }

    @Test
    public void secondaryGhostLookupRequiresTheOppositePaneOnDisplayZero() {
        String dump =
                "Display #1 (activities from top to bottom):\n"
                + "  Stack #9: type=standard mode=split-screen-secondary\n"
                + "    com.overdrive.app/.launcher.AppLauncherSecondaryGhostActivity\n"
                + "Display #0 (activities from top to bottom):\n"
                + "  Stack #7: type=standard mode=split-screen-primary\n"
                + "    com.overdrive.app/.launcher.AppLauncherSecondaryGhostActivity\n"
                + "  Stack #8: type=standard mode=split-screen-secondary\n"
                + "    com.overdrive.app/.launcher.AppLauncherSecondaryGhostActivity\n";

        assertEquals(8, AppLauncher.secondaryGhostStackIdInDump(dump));
    }
}
