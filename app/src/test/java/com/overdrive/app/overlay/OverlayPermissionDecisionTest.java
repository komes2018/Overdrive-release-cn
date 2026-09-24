package com.overdrive.app.overlay;

import android.app.AppOpsManager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OverlayPermissionDecisionTest {

    @Test
    public void explicitAppOpsDenialOverridesFrameworkFalsePositive() {
        assertFalse(OverlayPermissionDecision.resolve(Boolean.FALSE, true));
    }

    @Test
    public void explicitAppOpsGrantOverridesFrameworkFalseNegative() {
        assertTrue(OverlayPermissionDecision.resolve(Boolean.TRUE, false));
    }

    @Test
    public void frameworkIsOnlyFallbackWhenAppOpsIsUnavailable() {
        assertTrue(OverlayPermissionDecision.resolve(null, true));
        assertFalse(OverlayPermissionDecision.resolve(null, false));
    }

    @Test
    public void ambiguousAppOpsModesDeferOnlyOnDiLink5() {
        assertNull(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_DEFAULT, true, 25));
        assertNull(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_FOREGROUND, true, 25));
        assertFalse(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_DEFAULT, false, 25));
        assertTrue(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_FOREGROUND, false, 25));
        assertTrue(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_ALLOWED, false, 25));
        assertFalse(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_IGNORED, true, 25));
        assertFalse(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_IGNORED, true, 26));
        assertFalse(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_IGNORED, false, 25));
        assertFalse(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_ERRORED, false, 25));
    }
}
