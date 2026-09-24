package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.AutomationCategories;
import com.overdrive.app.automation.action.Action;
import com.overdrive.app.automation.action.Actions;
import com.overdrive.app.automation.action.ApiAction;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Pins the parked-only IVI reboot action and its reboot-loop guard. */
public class IviRebootAutomationActionTest {

    @Test
    public void rebootIsAvailableInAutomationAndKeyMappingWithoutDuplicatingSteeringHeat()
            throws Exception {
        Action reboot = new Actions().getAction("iviReboot");
        assertTrue(reboot instanceof ApiAction);
        assertEquals(AutomationCategories.SYSTEM,
                AutomationCategories.forId("iviReboot"));

        String keymap = readRepositoryFile(
                "app/src/main/assets/web/shared/key-mapping.js");
        assertTrue(keymap.contains(
                "{ id: 'ivi_reboot', i18n: 'keymap.act_ivi_reboot', kind: 'api'"));
        assertTrue(keymap.contains(
                "path: '/api/system/ivi-reboot', body: '{\"confirm\":\"REBOOT\"}'"));
        assertTrue(keymap.contains(
                "{ id: 'steering_heat',   i18n: 'keymap.act_steering_heat'"));
        assertTrue(new Actions().getAction("steeringHeat") instanceof ApiAction);
    }

    @Test
    public void allowlistAdmitsOnlyTheExactRebootRoute() throws Exception {
        Method allowed = HttpServer.class.getDeclaredMethod(
                "isAutomationAllowed", String.class);
        allowed.setAccessible(true);

        assertTrue((Boolean) allowed.invoke(null, "/api/system/ivi-reboot"));
        assertFalse((Boolean) allowed.invoke(null, "/api/system/ivi-reboot/anything"));
    }

    @Test
    public void autonomousRebootCannotLoopAtStartupAndRequestsAreRateLimited() {
        long guard = VehicleControlApiHandler.IVI_REBOOT_GUARD_MS;

        assertEquals(guard,
                VehicleControlApiHandler.iviRebootCooldownRemainingMs(
                        true, "", -1L, "boot-a", 0L));
        assertEquals(1_000L,
                VehicleControlApiHandler.iviRebootCooldownRemainingMs(
                        true, "", -1L, "boot-a", guard - 1_000L));
        assertEquals(0L,
                VehicleControlApiHandler.iviRebootCooldownRemainingMs(
                        true, "", -1L, "boot-a", guard));

        assertEquals(guard - 30_000L,
                VehicleControlApiHandler.iviRebootCooldownRemainingMs(
                        false, "boot-a", 10_000L, "boot-a", 40_000L));
        assertEquals(0L,
                VehicleControlApiHandler.iviRebootCooldownRemainingMs(
                        false, "old-boot", 10_000L, "boot-a", 40_000L));
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
