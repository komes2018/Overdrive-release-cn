package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Guards the camera-first Live dashboard contract for both the embedded BYD
 * WebView and the remote web UI.
 */
public class LiveViewDashboardAssetTest {

    @Test
    public void camerasAreTheDefaultWithAllViewAutoStarted() throws Exception {
        String liveView = readWebAsset("local/live-view.html");

        assertTrue(liveView.contains(
                "<div class=\"tab-panel active\" id=\"panelCameras\">"));
        assertTrue(liveView.contains("activeTab: 'cameras'"));
        assertTrue(liveView.contains("BYD.stream.currentViewMode = 0;"));
        assertTrue(liveView.contains("await BYD.stream.selectCamera(0);"));
        assertTrue(liveView.indexOf("await loadSavedStreamQuality();")
                < liveView.indexOf("await BYD.stream.selectCamera(0);"));
    }

    @Test
    public void selectorAndLocationShareAnExplicitUtilityRail() throws Exception {
        String liveView = readWebAsset("local/live-view.html");

        assertTrue(liveView.contains("id=\"liveUtilityRail\""));
        assertTrue(liveView.contains("id=\"locationCard\""));
        assertTrue(liveView.contains("id=\"locationExpandButton\""));
        assertTrue(liveView.contains("data-i18n=\"liveview.expand_map\""));
        assertTrue(liveView.contains("#panelCameras .live-utility-rail"));
        assertTrue(liveView.contains("flex: 0 0 28%;"));
        assertFalse(liveView.contains("id=\"miniPreview\""));
        assertFalse(liveView.contains("getElementById('miniPreviewContent')"));
        assertFalse(liveView.contains("id=\"panelMap\""));
    }

    @Test
    public void expandedMapPreservesTheCameraDashboardUnderneath() throws Exception {
        String liveView = readWebAsset("local/live-view.html");
        String english = readWebAsset("i18n/en.json");

        assertTrue(liveView.contains("panel.classList.toggle('map-expanded', expanded)"));
        assertTrue(liveView.contains("BYD.map.map.invalidateSize()"));
        assertTrue(liveView.contains("BYD.liveLayout.setMapExpanded(false)"));
        assertTrue(liveView.contains("class=\"location-close-button\""));
        assertTrue(liveView.contains("data-i18n-attr=\"aria-label:liveview.back_to_cameras\""));
        assertTrue(liveView.contains("M20 11v2H8l5.5 5.5-1.42 1.42L4.16 12l8.92-7.92L13.5 5.5 8 11h12z"));
        assertFalse(liveView.contains("data-i18n=\"common.back\""));
        assertTrue(english.contains("\"expand_map\": \"Expand map\""));
        assertTrue(english.contains("\"back_to_cameras\": \"Back to cameras\""));
    }

    @Test
    public void portraitPhonesKeepCameraLabelsVisibleWithoutADoubleSafeArea() throws Exception {
        String liveView = readWebAsset("local/live-view.html");

        assertTrue(liveView.contains(
                ".live-view-page .live-tabs-view { padding-bottom: 0; }"));
        assertTrue(liveView.contains(
                "padding-bottom: calc(12px + var(--safe-bottom, 0px));"));
        assertTrue(liveView.contains(
                "@media (max-width: 768px) and (orientation: portrait)"));
        assertTrue(liveView.contains(
                "flex: 0 0 calc(220px + var(--safe-bottom, 0px));"));
        assertTrue(liveView.contains("flex: 0 0 58%;"));
        assertTrue(liveView.contains("padding: 4px 8px 0;"));
        assertTrue(liveView.contains("width: 124px;"));
        assertTrue(liveView.contains(
                "bottom: calc(16px + var(--safe-bottom, 0px));"));
    }

    private static String readWebAsset(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path fromModule = current.resolve("src/main/assets/web").resolve(relativePath);
        if (Files.exists(fromModule)) {
            return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
        }
        Path fromRepository = current.resolve("app/src/main/assets/web").resolve(relativePath);
        if (Files.exists(fromRepository)) {
            return new String(Files.readAllBytes(fromRepository), StandardCharsets.UTF_8);
        }
        throw new AssertionError("Could not locate web asset: " + relativePath);
    }
}
