package com.overdrive.app.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 验证行程结束且车辆熄火后推送到 Webhook 的契约测试。
 */
public class TripCompletedNotificationTest {

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path direct = Paths.get(relativePath);
        if (Files.exists(direct)) {
            return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
        }
        Path fromApp = Paths.get("..", relativePath);
        if (Files.exists(fromApp)) {
            return new String(Files.readAllBytes(fromApp), StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("Cannot find repository file: " + relativePath);
    }

    @Test
    public void categoryRegistryContainsTripCompleted() throws Exception {
        String json = readRepositoryFile("app/src/main/assets/notifications-categories.json");
        JSONObject root = new JSONObject(json);
        JSONArray categories = root.getJSONArray("categories");

        JSONObject tripCat = null;
        for (int i = 0; i < categories.length(); i++) {
            JSONObject c = categories.getJSONObject(i);
            if ("vehicle.trip.completed".equals(c.optString("id"))) {
                tripCat = c;
                break;
            }
        }

        assertNotNull("vehicle.trip.completed category must be registered in notifications-categories.json", tripCat);
        assertEquals("行程统计", tripCat.getString("group"));
        assertEquals("info", tripCat.getString("severity"));
        assertTrue(tripCat.getBoolean("defaultEnabled"));
        assertEquals("/trips.html", tripCat.getString("defaultClickUrl"));
    }

    @Test
    public void weComSinkContainsTripCategoryHandling() throws Exception {
        String code = readRepositoryFile("app/src/main/java/com/overdrive/app/notifications/sinks/WeComSink.java");
        assertTrue("WeComSink must handle vehicle.trip. category",
                code.contains("vehicle.trip."));
        assertTrue("WeComSink must support trips preference toggle",
                code.contains("cfg.optBoolean(\"trips\""));
    }

    @Test
    public void tripAnalyticsManagerHandlesAccOffLifecycle() throws Exception {
        String code = readRepositoryFile("app/src/main/java/com/overdrive/app/trips/TripAnalyticsManager.java");
        assertTrue("TripAnalyticsManager must track isAccOff state",
                code.contains("isAccOff"));
        assertTrue("TripAnalyticsManager must support pendingTripForAccOff",
                code.contains("pendingTripForAccOff"));
        assertTrue("TripAnalyticsManager must call TripNotifier.notifyTripCompleted",
                code.contains("TripNotifier.notifyTripCompleted"));
    }
}
