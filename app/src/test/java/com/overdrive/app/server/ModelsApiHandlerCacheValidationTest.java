package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/** Ensures APK/manifest updates cannot keep serving an old same-named GLB. */
public class ModelsApiHandlerCacheValidationTest {

    @Test
    public void cachedModelMustMatchManifestSizeAndSha256() throws Exception {
        Path model = Files.createTempFile("overdrive-model-cache", ".glb");
        try {
            byte[] bytes = "verified-model-v12".getBytes(StandardCharsets.UTF_8);
            Files.write(model, bytes);
            String sha = hex(MessageDigest.getInstance("SHA-256").digest(bytes));

            assertTrue(ModelsApiHandler.cacheMatchesManifest(
                    model.toFile(), bytes.length, sha));
            assertTrue(ModelsApiHandler.cacheMatchesManifest(
                    model.toFile(), bytes.length, ""));
            assertFalse(ModelsApiHandler.cacheMatchesManifest(
                    model.toFile(), bytes.length + 1L, sha));
            assertFalse(ModelsApiHandler.cacheMatchesManifest(
                    model.toFile(), bytes.length,
                    "0000000000000000000000000000000000000000000000000000000000000000"));
        } finally {
            Files.deleteIfExists(model);
        }
    }

    @Test
    public void everyBundledModelMatchesItsManifestMetadata() throws Exception {
        Path manifestPath = repositoryPath(
                "app/src/main/assets/web/shared/models/manifest.json");
        JSONObject manifest = new JSONObject(new String(
                Files.readAllBytes(manifestPath), StandardCharsets.UTF_8));
        JSONArray models = manifest.getJSONArray("models");

        for (int i = 0; i < models.length(); i++) {
            JSONObject entry = models.getJSONObject(i);
            if (!entry.optBoolean("bundled", false)) continue;

            Path model = manifestPath.getParent().resolve(entry.getString("file"));
            assertTrue("Missing bundled model: " + model, Files.isRegularFile(model));
            assertTrue(entry.getString("id"),
                    ModelsApiHandler.cacheMatchesManifest(
                            model.toFile(),
                            entry.getLong("sizeBytes"),
                            entry.getString("sha256")));
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
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
