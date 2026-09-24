package com.overdrive.app.ui.vehicle;

import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Keeps the dashboard and Vehicle Control lite art in sync with the model manifest. */
public class VehicleArtCoverageTest {

    @Test
    public void everyManifestVehicleHasNonFallbackArt() throws Exception {
        JSONObject manifest = new JSONObject(new String(
                Files.readAllBytes(repositoryPath(
                        "app/src/main/assets/web/shared/models/manifest.json")),
                StandardCharsets.UTF_8));
        JSONArray models = manifest.getJSONArray("models");
        int fallback = VehicleArt.INSTANCE.drawableFor("__unknown_vehicle__");

        for (int i = 0; i < models.length(); i++) {
            String id = models.getJSONObject(i).getString("id");
            assertTrue("Missing static vehicle art for " + id,
                    VehicleArt.INSTANCE.drawableFor(id) != fallback);
        }
    }

    @Test
    public void m6RenderIsBundledForBothArtConsumers() throws Exception {
        Path render = repositoryPath(
                "app/src/main/res/drawable-nodpi/vehicle_m6.webp");
        assertTrue(Files.size(render) > 10_000L);
    }

    @Test
    public void atto3EvoRenderIsBundledForBothArtConsumers() throws Exception {
        Path render = repositoryPath(
                "app/src/main/res/drawable-nodpi/vehicle_atto3_evo.webp");
        assertTrue(Files.size(render) > 10_000L);
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
