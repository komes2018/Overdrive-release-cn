package com.overdrive.app.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DaemonLauncherProcessProbeTest {

    @Test
    public void cameraProbeRejectsZombieLeftByForceKill() {
        String killed =
                "S ARGS\n"
                + "Z byd_cam_daemon\n"
                + "S sh /data/local/tmp/start_cam_daemon.sh\n";
        String running =
                "S ARGS\n"
                + "S byd_cam_daemon\n";

        assertFalse(DaemonLauncher.processAliveInSnapshot(
                killed, "byd_cam_daemon"));
        assertTrue(DaemonLauncher.processAliveInSnapshot(
                running, "byd_cam_daemon"));
    }

    @Test
    public void sentryProbeStillExcludesAccSentry() {
        assertFalse(DaemonLauncher.processAliveInSnapshot(
                "S ARGS\nS acc_sentry_daemon\n", "sentry_daemon"));
        assertTrue(DaemonLauncher.processAliveInSnapshot(
                "S ARGS\nS sentry_daemon\n", "sentry_daemon"));
    }
}
