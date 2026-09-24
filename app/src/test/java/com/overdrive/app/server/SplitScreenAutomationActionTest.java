package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.AutomationCategories;
import com.overdrive.app.automation.action.Action;
import com.overdrive.app.automation.action.Actions;
import com.overdrive.app.automation.action.ApiAction;
import com.overdrive.app.automation.type.Type;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

public class SplitScreenAutomationActionTest {

    @Test
    public void twoAppSplitActionExposesTwoAppSelectorsWithoutReplacingLegacyAction()
            throws Exception {
        Actions actions = new Actions();
        Action legacy = actions.getAction("openAppSplit");
        Action pair = actions.getAction("openAppsSplit");

        assertTrue(legacy instanceof ApiAction);
        assertTrue(pair instanceof ApiAction);
        List<Type> variables = ((ApiAction) pair).getVariables();
        assertEquals(2, variables.size());
        assertEquals("primaryPackage", variables.get(0).getLabel().getId());
        assertEquals("app", variables.get(0).toJson().getString("type"));
        assertEquals("secondaryPackage", variables.get(1).getLabel().getId());
        assertEquals("app", variables.get(1).toJson().getString("type"));
        assertEquals(AutomationCategories.SYSTEM,
                AutomationCategories.forId("openAppsSplit"));

        JSONObject valid = new JSONObject()
                .put("variables", new JSONObject()
                        .put("primaryPackage", "com.maps.app")
                        .put("secondaryPackage", "com.music.app"));
        AutomationAction parsed = pair.fromJson(valid);
        assertNotNull(parsed);
        assertEquals("com.maps.app", parsed.getVariables().get("primaryPackage"));

        JSONObject duplicate = new JSONObject()
                .put("variables", new JSONObject()
                        .put("primaryPackage", "com.maps.app")
                        .put("secondaryPackage", "com.maps.app"));
        assertNull(pair.fromJson(duplicate));
    }

    @Test
    public void launchEndpointRejectsTheSameAppBeforeDispatching() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(AppsApiHandler.handle(
                "POST",
                "/api/apps/launch",
                "{\"package\":\"com.maps.app\",\"secondaryPackage\":\"com.maps.app\",\"split\":true}",
                out));
        String response = new String(out.toByteArray(), StandardCharsets.UTF_8);
        JSONObject body = new JSONObject(response.substring(response.indexOf('{')));

        assertFalse(body.getBoolean("success"));
        assertEquals("Split-screen apps must be different", body.getString("error"));
    }

    @Test
    public void launchEndpointRequiresSplitModeForASecondApp() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(AppsApiHandler.handle(
                "POST",
                "/api/apps/launch",
                "{\"package\":\"com.maps.app\",\"secondaryPackage\":\"com.music.app\"}",
                out));
        String response = new String(out.toByteArray(), StandardCharsets.UTF_8);
        JSONObject body = new JSONObject(response.substring(response.indexOf('{')));

        assertFalse(body.getBoolean("success"));
        assertEquals("secondaryPackage requires split=true", body.getString("error"));
    }
}
