package com.overdrive.app.camera.dilink5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.camera.CameraProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.Test;

public class DiLink5PlatformTest {
    @Test
    public void selectionIsExplicitAndLegacyModesStayDisabled() {
        assertFalse(DiLink5Platform.isSelected());
        assertFalse(DiLink5Platform.isSelected("", ""));
        assertFalse(DiLink5Platform.isSelected("default", "auto"));
        assertFalse(DiLink5Platform.isSelected("dilink4", "auto"));
        assertTrue(DiLink5Platform.isSelected("dilink5", "auto"));
        assertFalse(DiLink5Platform.isSelected(
                "default", CameraProfiles.PROFILE_DILINK5_SEALION7));
    }

    @Test
    public void configuredModeNormalizationNeverDefaultsInvalidInput() {
        assertEquals("default",
                DiLink5Platform.normalizeConfiguredMode(" DEFAULT "));
        assertEquals("dilink4",
                DiLink5Platform.normalizeConfiguredMode("DiLink4"));
        assertEquals("dilink5",
                DiLink5Platform.normalizeConfiguredMode("DILINK5"));
        assertNull(DiLink5Platform.normalizeConfiguredMode(null));
        assertNull(DiLink5Platform.normalizeConfiguredMode(""));
        assertNull(DiLink5Platform.normalizeConfiguredMode("legacy"));
    }

    @Test
    public void panelControlUsesOnlyRuntimeFencedModes() {
        assertFalse(DiLink5Platform.isPanelControlMode(null));
        assertFalse(DiLink5Platform.isPanelControlMode("default"));
        assertTrue(DiLink5Platform.isPanelControlMode("dilink4"));
        assertTrue(DiLink5Platform.isPanelControlMode("dilink5"));
    }

    @Test
    public void stagedActiveModeWinsOnlyWithMatchingPendingTransition() {
        assertTrue(DiLink5Platform.isSameMode("DILINK4", "dilink4"));
        assertFalse(DiLink5Platform.isSameMode("default", "dilink4"));
        assertFalse(DiLink5Platform.isSameMode(null, "default"));
        assertTrue(DiLink5Platform.isActiveMode("default"));
        assertEquals("default",
                DiLink5Platform.effectiveMode("default", "dilink5"));
        assertEquals("dilink5",
                DiLink5Platform.effectiveMode("dilink5", "default"));
        assertEquals("dilink5",
                DiLink5Platform.effectiveMode("invalid", "dilink5"));
        assertEquals("default",
                DiLink5Platform.effectiveMode(
                        "dilink5", null, "default"));
        assertEquals("default",
                DiLink5Platform.effectiveMode(
                        "dilink5", "dilink5", "default"));
        assertEquals("default",
                DiLink5Platform.effectiveMode(
                        "default", "dilink5", "dilink5"));
        assertEquals("dilink5",
                DiLink5Platform.effectiveMode(
                        "dilink5", "default", "default"));
        String abortedDiLink5Transition = DiLink5Platform.effectiveMode(
                "dilink4", "dilink5", "dilink5");
        assertEquals("dilink4", abortedDiLink5Transition);
        assertTrue(DiLink5Platform.isDiLink4Mode(
                abortedDiLink5Transition));
        assertEquals("default",
                DiLink5Platform.effectiveMode(
                        "default", "dilink4", "dilink4"));
        assertEquals("default",
                DiLink5Platform.effectiveMode(
                        "default", "dilink4", "default"));
        assertEquals("dilink4",
                DiLink5Platform.effectiveMode(
                        "dilink4", null, "dilink4"));
        assertEquals("dilink4",
                DiLink5Platform.effectiveMode(
                        "dilink4", "default", "default"));
        assertEquals("dilink4",
                DiLink5Platform.effectiveMode(
                        "dilink4", "default", "dilink4"));
        assertEquals("default",
                DiLink5Platform.effectiveMode(
                        "default", null, "default"));
    }

    @Test
    public void validPendingTransitionCannotBeReplacedBeforeRestart() {
        assertTrue(DiLink5Platform.canStageMode(
                "default", "dilink4", "dilink4", "dilink4"));
        assertTrue(DiLink5Platform.canStageMode(
                "default", "dilink4", "dilink4", "default"));
        assertFalse(DiLink5Platform.canStageMode(
                "default", "dilink4", "dilink4", "dilink5"));
        assertTrue(DiLink5Platform.canStageMode(
                "default", null, "default", "dilink5"));
        assertTrue(DiLink5Platform.canStageMode(
                "default", "dilink4", "default", "dilink5"));
        assertFalse(DiLink5Platform.canStageMode(
                "default", null, "default", "invalid"));
    }

    @Test
    public void crossProcessGenerationFailsClosedForMissingOrCorruptMarkers()
            throws Exception {
        Path missing = Files.createTempDirectory("dilink5-mode")
                .resolve("missing");
        assertNull(DiLink5Platform.readModeGeneration(missing.toString()));

        Path marker = Files.createTempFile("dilink5-mode", ".txt");
        Files.write(marker, "dilink5\nnot-a-uuid\n".getBytes(StandardCharsets.UTF_8));
        assertNull(DiLink5Platform.readModeGeneration(marker.toString()));

        String generation = UUID.randomUUID().toString();
        Files.write(
                marker,
                ("dilink5\n" + generation + "\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(
                generation,
                DiLink5Platform.readModeGeneration(marker.toString()));
        assertTrue(DiLink5Platform.matchesModeGeneration(generation, generation));
        assertFalse(DiLink5Platform.matchesModeGeneration(
                generation, UUID.randomUUID().toString()));
        assertFalse(DiLink5Platform.matchesModeGeneration(generation, null));
        assertFalse(DiLink5Platform.matchesModeGeneration(null, generation));
    }
}
