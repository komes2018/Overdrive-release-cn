package com.overdrive.app.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class GenAiIncidentPacksTest {

    @Rule
    public final TemporaryFolder temporaryFolder =
            new TemporaryFolder();

    private String previousHome;
    private File home;

    @Before
    public void setUp() throws Exception {
        previousHome = System.getProperty(
                GenAiIncidentPacks.HOME_PROPERTY);
        home = temporaryFolder.newFolder("genai-home");
        System.setProperty(
                GenAiIncidentPacks.HOME_PROPERTY,
                home.getAbsolutePath());
    }

    @After
    public void tearDown() {
        if (previousHome == null) {
            System.clearProperty(
                    GenAiIncidentPacks.HOME_PROPERTY);
        } else {
            System.setProperty(
                    GenAiIncidentPacks.HOME_PROPERTY,
                    previousHome);
        }
    }

    @Test
    public void createsPrivacyFilteredImmutableFallbackPack()
            throws Exception {
        byte[] videoBytes =
                "finalized-video-payload".getBytes(
                        StandardCharsets.UTF_8);
        File video = writeRecording(videoBytes);
        writeSidecar(video);
        String recordingId =
                RecordingIdentity.fromFile(video).recordingId;

        JSONObject created =
                GenAiIncidentPacks.createResolved(
                        recordingId, video, null);

        JSONObject metadata =
                created.getJSONObject("metadata");
        String packId = metadata.getString("packId");
        assertEquals(recordingId,
                metadata.getString("recordingId"));
        assertEquals("sentry",
                metadata.getString("recordingType"));
        assertTrue(metadata.getBoolean("sidecarPresent"));
        assertTrue(metadata.getBoolean("previewIncluded"));
        assertEquals("evidence_only",
                metadata.getString("reportMode"));

        JSONObject evidence =
                created.getJSONObject("evidence");
        String providerContext = evidence.toString();
        assertFalse(providerContext.contains("12.345678"));
        assertFalse(providerContext.contains("77.123456"));
        assertFalse(providerContext.contains("actorId"));
        assertFalse(providerContext.contains("998877"));
        assertFalse(providerContext.contains("firstSeenWallMs"));
        assertFalse(providerContext.contains("displayName"));
        assertFalse(providerContext.contains("district"));
        assertFalse(providerContext.contains("hero-secret.jpg"));
        assertEquals("Bengaluru",
                evidence.getJSONObject("approximatePlace")
                        .getString("city"));
        assertFalse(evidence.getBoolean(
                "mediaSharedWithProvider"));

        JSONObject report = created.getJSONObject("report");
        assertEquals("evidence_only",
                report.getJSONObject("generation")
                        .getString("mode"));
        assertEquals("genai_unavailable",
                report.getJSONObject("generation")
                        .getString("reason"));
        assertEquals("event-1",
                report.getJSONArray("timeline")
                        .getJSONObject(0)
                        .getJSONArray("evidenceRefs")
                        .getString(0));

        JSONObject manifest =
                created.getJSONObject("manifest");
        assertEquals(GenAiIncidentPacks.sha256(videoBytes),
                manifest.getJSONObject("sourceVideo")
                        .getString("sha256"));
        assertFalse(manifest.getBoolean("signed"));
        assertEquals(64,
                created.getString("manifestSha256").length());

        File root = new File(home, "incident-packs");
        File packDirectory = new File(root, packId);
        assertTrue(packDirectory.isDirectory());
        File[] published = root.listFiles();
        assertNotNull(published);
        assertEquals(1, published.length);
        assertEquals(packId, published[0].getName());

        JSONObject listed = GenAiIncidentPacks.list();
        assertEquals(1, listed.getJSONArray("items").length());
        assertEquals(packId,
                listed.getJSONArray("items")
                        .getJSONObject(0).getString("packId"));
        assertEquals(packId,
                GenAiIncidentPacks.get(packId)
                        .getJSONObject("metadata")
                        .getString("packId"));

        assertTrue(GenAiIncidentPacks.delete(packId)
                .getBoolean("success"));
        assertEquals(0,
                GenAiIncidentPacks.list()
                        .getJSONArray("items").length());
        assertFalse(packDirectory.exists());
    }

    @Test
    public void zipUsesFixedEntriesAndIncludesOnlyUnchangedVideo()
            throws Exception {
        byte[] videoBytes =
                "stream-this-final-video".getBytes(
                        StandardCharsets.UTF_8);
        File video = writeRecording(videoBytes);
        writeSidecar(video);
        String recordingId =
                RecordingIdentity.fromFile(video).recordingId;
        JSONObject created =
                GenAiIncidentPacks.createResolved(
                        recordingId, video, null);
        String packId = created.getJSONObject("metadata")
                .getString("packId");
        File packDirectory = new File(
                new File(home, "incident-packs"), packId);

        ByteArrayOutputStream withoutVideo =
                new ByteArrayOutputStream();
        GenAiIncidentPacks.writeZip(
                packDirectory, null, false, withoutVideo);
        Map<String, byte[]> localEntries =
                unzip(withoutVideo.toByteArray());
        assertEquals(Arrays.asList(
                        "OverDrive-Incident-Report.pdf",
                        "evidence/incident-preview.jpg",
                        "README.txt",
                        "metadata.json",
                        "evidence/evidence.json",
                        "report/report.json",
                        "source/timeline.json",
                        "manifest.json",
                        "manifest.sha256"),
                new ArrayList<>(localEntries.keySet()));
        byte[] pdf =
                localEntries.get("OverDrive-Incident-Report.pdf");
        assertNotNull(pdf);
        String pdfText = new String(
                pdf, StandardCharsets.ISO_8859_1);
        assertTrue(pdfText.startsWith("%PDF-1.4"));
        assertTrue(pdfText.contains(
                "OverDrive Incident Evidence Pack"));
        assertTrue(pdfText.endsWith("%%EOF\n"));
        String readme = new String(
                localEntries.get("README.txt"),
                StandardCharsets.UTF_8);
        assertTrue(readme.contains(
                "OverDrive Incident Evidence Pack"));
        assertTrue(readme.contains(
                "Video: media/recording.mp4 (when included)"));
        assertTrue(readme.contains(
                "Representative frame: evidence/incident-preview.jpg"));
        assertTrue(readme.contains(
                "Peak detector confidence was 93%"));
        assertTrue(readme.contains(
                "Front camera recorded person activity"));
        assertFalse(readme.contains("12.345678"));
        assertArrayEquals(
                previewJpeg(),
                localEntries.get(
                        "evidence/incident-preview.jpg"));
        assertFalse(localEntries.containsKey(
                "media/recording.mp4"));

        ByteArrayOutputStream withVideo =
                new ByteArrayOutputStream();
        GenAiIncidentPacks.writeZip(
                packDirectory, video, true, withVideo);
        Map<String, byte[]> allEntries =
                unzip(withVideo.toByteArray());
        assertArrayEquals(videoBytes,
                allEntries.get("media/recording.mp4"));

        long originalMtime = video.lastModified();
        byte[] changed = new byte[videoBytes.length];
        Arrays.fill(changed, (byte) 'x');
        Files.write(video.toPath(), changed);
        assertTrue(video.setLastModified(originalMtime));
        ByteArrayOutputStream rejected =
                new ByteArrayOutputStream();
        GenAiIncidentPacks.PackException error =
                assertThrows(
                        GenAiIncidentPacks.PackException.class,
                        () -> GenAiIncidentPacks.writeZip(
                                packDirectory, video, true,
                                rejected));
        assertEquals("recording_changed", error.code);
        assertEquals(0, rejected.size());
    }

    @Test
    public void structuredReportAcceptsOnlyKnownEvidenceReferences()
            throws Exception {
        JSONObject evidence =
                GenAiIncidentPacks.buildModelContext(
                        sampleSidecar(), "sentry");
        JSONObject report = new JSONObject()
                .put("title", "Local evidence")
                .put("summary", "One sidecar event is present.")
                .put("timeline", new JSONArray()
                        .put(new JSONObject()
                                .put("atMs", 1200)
                                .put("label", "person")
                                .put("observation",
                                        "A person event is recorded.")
                                .put("evidenceRefs",
                                        new JSONArray()
                                                .put("event-1"))))
                .put("observations", new JSONArray()
                        .put(new JSONObject()
                                .put("text",
                                        "The sidecar contains one event.")
                                .put("confidence", "high")
                                .put("evidenceRefs",
                                        new JSONArray()
                                                .put("event-1"))))
                .put("unknowns", new JSONArray()
                        .put("Video content was not inspected."));

        JSONObject parsed =
                GenAiIncidentPacks.parseStructuredReport(
                        report.toString(), evidence);
        assertEquals("Local evidence",
                parsed.getString("title"));

        report.getJSONArray("timeline")
                .getJSONObject(0)
                .put("evidenceRefs",
                        new JSONArray().put("unknown-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GenAiIncidentPacks
                        .parseStructuredReport(
                                report.toString(), evidence));
    }

    @Test
    public void attachmentHeadersAreNoStoreAndSanitizeFilename()
            throws Exception {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        HttpResponse.sendAttachmentNoStoreHeaders(
                output, "application/zip",
                "incident\"\r\nInjected: yes.zip");

        String response = new String(
                output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(response.startsWith(
                "HTTP/1.1 200 OK\r\n"));
        assertTrue(response.contains(
                "\r\nContent-Type: application/zip\r\n"));
        assertTrue(response.contains(
                "\r\nCache-Control: no-store, no-cache, must-revalidate, private\r\n"));
        assertTrue(response.contains(
                "\r\nContent-Disposition: attachment; filename=\"incident_Injected: yes.zip\"\r\n"));
        assertFalse(response.contains(
                "\r\nInjected: yes.zip\r\n"));
        assertTrue(response.endsWith("\r\n\r\n"));
    }

    private File writeRecording(byte[] bytes) throws Exception {
        File recordings =
                temporaryFolder.newFolder("recordings-"
                        + System.nanoTime());
        File video = new File(
                recordings,
                "event_20260903_120000.mp4");
        Files.write(video.toPath(), bytes);
        return video;
    }

    private void writeSidecar(File video) throws Exception {
        String stem = video.getName().substring(
                0, video.getName().length() - 4);
        Files.write(
                new File(video.getParentFile(),
                        stem + ".json").toPath(),
                sampleSidecar().toString().getBytes(
                        StandardCharsets.UTF_8));
        Files.write(
                new File(video.getParentFile(),
                        "hero-secret.jpg").toPath(),
                previewJpeg());
    }

    private static byte[] previewJpeg() {
        return Base64.getDecoder().decode(
                "/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYyLjI4LjEwMQD/2wBDAAgKCgsKCw0NDQ0NDRAPEBAQEBAQEBAQEBASEhIVFRUSEhIQEBISFBQVFRcXFxUVFRUXFxkZGR4eHBwjIyQrKzP/xABLAAEBAAAAAAAAAAAAAAAAAAAACAEBAAAAAAAAAAAAAAAAAAAAABABAAAAAAAAAAAAAAAAAAAAABEBAAAAAAAAAAAAAAAAAAAAAP/AABEIAAIAAgMBIgACEQADEQD/2gAMAwEAAhEDEQA/AJ/AB//Z");
    }

    private JSONObject sampleSidecar() throws Exception {
        return new JSONObject()
                .put("version", 3)
                .put("durationMs", 8000)
                .put("layout", "dashcam")
                .put("heroThumbnail", "hero-secret.jpg")
                .put("events", new JSONArray()
                        .put(new JSONObject()
                                .put("start", 1200)
                                .put("end", 2600)
                                .put("type", "person")
                                .put("maxConf", 0.93)
                                .put("cameras",
                                        new JSONArray()
                                                .put("front"))))
                .put("actors", new JSONArray()
                        .put(new JSONObject()
                                .put("actorId", 998877)
                                .put("class", "person")
                                .put("classGroup", "PERSON")
                                .put("firstSeenWallMs",
                                        1_788_000_000_000L)
                                .put("lastSeenWallMs",
                                        1_788_000_001_000L)
                                .put("firstSeenMs", 1000)
                                .put("lastSeenMs", 3000)
                                .put("peakSeverity", "WARNING")
                                .put("peakProximity", "MID")
                                .put("trend", "STABLE")
                                .put("isStatic", false)
                                .put("peakConfidence", 0.93)
                                .put("peakCamera", "front")))
                .put("stats", new JSONObject()
                        .put("person", 1)
                        .put("personCount", 1)
                        .put("peakSeverity", "WARNING")
                        .put("peakProximity", "MID")
                        .put("peakSeverityMs", 2000))
                .put("geo", new JSONObject()
                        .put("start", new JSONObject()
                                .put("lat", 12.345678)
                                .put("lng", 77.123456)
                                .put("capturedAtMs",
                                        1_788_000_000_000L))
                        .put("place", new JSONObject()
                                .put("displayName",
                                        "Secret Street, Bengaluru")
                                .put("district",
                                        "Secret District")
                                .put("city", "Bengaluru")
                                .put("country", "India")
                                .put("countryCode", "IN")
                                .put("resolvedAtMs",
                                        1_788_000_100_000L)));
    }

    private static Map<String, byte[]> unzip(byte[] bytes)
            throws Exception {
        Map<String, byte[]> entries =
                new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream contents =
                        new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = zip.read(buffer)) >= 0) {
                    if (count > 0) {
                        contents.write(buffer, 0, count);
                    }
                }
                entries.put(entry.getName(),
                        contents.toByteArray());
                zip.closeEntry();
            }
        }
        return entries;
    }
}
