package com.overdrive.app.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppUpdaterStorageErrorTest {

    @Test
    public void reservesInstallHeadroomAndExplainsDiskFullFailures() {
        long mib = 1024L * 1024L;
        assertEquals(350L * mib, AppUpdater.requiredUpdateFreeBytes(0));
        assertEquals(428L * mib, AppUpdater.requiredUpdateFreeBytes(100L * mib));

        assertTrue(AppUpdater.userFacingInstallError(
                "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]")
                .startsWith("Not enough storage to update Overdrive."));
        assertEquals("Network failed",
                AppUpdater.userFacingInstallError("Network failed"));
    }
}
