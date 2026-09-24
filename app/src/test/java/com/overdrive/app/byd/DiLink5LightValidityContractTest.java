package com.overdrive.app.byd;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class DiLink5LightValidityContractTest {

    @Test
    public void producerReadersCallbacksAndConsumersUseLightValidity()
            throws Exception {
        String collector = read(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        assertTrue(collector.contains(
                "if (diLink5) b.lightKnownMask(BydVehicleData.LIGHT_KNOWN_NONE);"));
        assertTrue(collector.contains(
                "b.markLightKnown(BydVehicleData.LIGHT_KNOWN_LOW_BEAM);"));
        assertTrue(collector.contains(
                "b.markLightKnown(BydVehicleData.LIGHT_KNOWN_HIGH_BEAM);"));
        assertTrue(collector.contains(
                "b.markLightKnown(BydVehicleData.LIGHT_KNOWN_FRONT_FOG);"));
        assertTrue(collector.contains(
                "b.markLightKnown(BydVehicleData.LIGHT_KNOWN_REAR_FOG);"));
        assertTrue(collector.contains(
                "BydVehicleData.LIGHT_KNOWN_TURN_HAZARD"));
        assertTrue(collector.contains(
                "b.markLightKnown(BydVehicleData.LIGHT_KNOWN_DRL);"));
        assertTrue(collector.contains(
                "next.autoWiperState(BydVehicleData.UNAVAILABLE);"));
        assertTrue(collector.contains(
                "next.wiperState(BydVehicleData.UNAVAILABLE);"));
        assertTrue(collector.contains(
                ".markLightKnown(\n"
                        + "                                        BydVehicleData.LIGHT_KNOWN_DRL)"));

        String event = read(
                "app/src/main/java/com/overdrive/app/automation/condition/BydEvent.java");
        assertTrue(event.contains(
                "data.isLightKnown(BydVehicleData.LIGHT_KNOWN_LOW_BEAM)"));
        assertTrue(event.contains(
                "data.isLightKnown(BydVehicleData.LIGHT_KNOWN_TURN_HAZARD)"));

        String overlay = read(
                "app/src/main/java/com/overdrive/app/telemetry/OverlayBitmapRenderer.java");
        assertTrue(overlay.contains(
                "if (lowBeamKnown) drawBeamGlyph"));
        assertTrue(overlay.contains(
                "if (highBeamKnown) drawBeamGlyph"));
    }

    private static String read(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent()
                    .resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
