package com.overdrive.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards model-specific body-paint targeting in both Three.js renderers. */
public class VehiclePaintHintAssetTest {

    @Test
    public void dolphinHintTargetsBodyShellAndExcludesWheelAccent() throws Exception {
        JSONObject dolphin = model("dolphin");
        String hint = dolphin.getString("paintMeshHint");

        assertEquals("bodypaint_a", hint);
        assertTrue("bodypaint_A_CarPaint_1_0 CarPaint_1"
                .toLowerCase(java.util.Locale.ROOT).contains(hint));
        assertFalse("bodypaint_rim_RIM_Color_0 RIM_Color"
                .toLowerCase(java.util.Locale.ROOT).contains(hint));
    }

    @Test
    public void bothThreeJsRenderersHonorManifestPaintHints() throws Exception {
        String vehicleControl = read(
                "app/src/main/assets/web/shared/vehicle-control.js");
        String evCard = read(
                "app/src/main/assets/web/shared/ev-card-3d.js");
        String serviceWorker = read(
                "app/src/main/assets/web/local/sw.js");

        assertTrue(vehicleControl.contains("modelEntry.paintMeshHint"));
        assertTrue(vehicleControl.contains(
                "paintName.indexOf(paintMeshHint) >= 0"));

        assertTrue(evCard.contains("self._loadGlb(url, gen, modelEntry)"));
        assertTrue(evCard.contains("modelEntry.paintMeshHint"));
        assertTrue(evCard.contains(
                "paintName.indexOf(paintMeshHint) < 0"));

        assertTrue(serviceWorker.contains(
                "const CACHE_VERSION = 'overdrive-3d-v4';"));
    }

    @Test
    public void otherKnownDarkOrScopedModelsDeclarePaintHints() throws Exception {
        assertEquals("bodypaint", model("sealion7").getString("paintMeshHint"));
        assertEquals("mk_body", model("atto2").getString("paintMeshHint"));
        assertEquals("carpaint", model("m6").getString("paintMeshHint"));
        assertEquals("body", model("atto3-evo").getString("paintMeshHint"));
    }

    private static JSONObject model(String id) throws Exception {
        JSONObject manifest = new JSONObject(read(
                "app/src/main/assets/web/shared/models/manifest.json"));
        JSONArray models = manifest.getJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i);
            if (id.equals(model.getString("id"))) return model;
        }
        throw new AssertionError("Missing model: " + id);
    }

    private static String read(String relativePath) throws Exception {
        return new String(
                Files.readAllBytes(repositoryPath(relativePath)),
                StandardCharsets.UTF_8);
    }

    private static Path repositoryPath(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;

            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) return fromModule;
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
