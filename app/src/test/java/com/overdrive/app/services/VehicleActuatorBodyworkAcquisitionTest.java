package com.overdrive.app.services;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Prevents mirror actuation from regressing to the wrong BYD device family or raw context. */
public class VehicleActuatorBodyworkAcquisitionTest {

    @Test
    public void mirrorPathUsesSettingCommandBeforeLegacyBodywork() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/VehicleActuatorService.java");
        int method = source.indexOf("private boolean setMirrorsFolded(boolean fold)");
        int nextMethod = source.indexOf(
                "private boolean setAutoExternalRearMirrorFollowUp", method);
        String mirrorPath = source.substring(method, nextMethod);

        int wrap = mirrorPath.indexOf("withBydPermissionBypass(");
        int acquire = mirrorPath.indexOf("BydDeviceHelper.getDevice(");
        int selectedRoute = mirrorPath.indexOf(
                "setDiLink5MirrorsFolded(bydContext, fold, value)");
        int exact = mirrorPath.indexOf("setAutoExternalRearMirrorFoldState");
        int setting = mirrorPath.indexOf(
                "BydConstants.MIRROR_FOLD_SETTING_DEVICE_CLASS");
        int legacy = mirrorPath.indexOf("setMirrorsFoldedViaLegacyBodywork(");
        assertTrue("mirror path must create the BYD permission context", wrap >= 0);
        assertTrue("permission context must be created before mirror acquisition",
                acquire > wrap);
        assertTrue("selected DiLink 5 must use its exact mirror setter",
                mirrorPath.contains("DiLink5Platform.isSelected()") && exact >= 0);
        assertTrue("exact DiLink 5 route must precede compatibility routes",
                selectedRoute >= 0 && selectedRoute < setting);
        assertTrue("Setting acquisition must receive the wrapped context",
                mirrorPath.contains(
                        "BydConstants.MIRROR_FOLD_SETTING_DEVICE_CLASS, bydContext"));
        assertTrue("missing Setting singleton must use the manager-level equivalent",
                mirrorPath.contains("BydDeviceHelper.callManagerSetInt("));
        assertTrue("manager fallback must target Setting device type 1023",
                mirrorPath.contains("BydConstants.MIRROR_FOLD_SETTING_DEVICE_TYPE,"));
        assertTrue("connected-model manual mirror feature must be used",
                mirrorPath.contains(
                        "BydFeatureIds.SETTING_OUTSIDE_REARVIEW_MIRROR_FOLD_SET"));
        assertTrue("1/2 command mapping must be used",
                mirrorPath.contains("BydConstants.mirrorFoldCommand(fold)"));
        assertTrue("legacy bodywork path must run only after the Setting route",
                legacy > setting);
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 6 && current != null;
                depth++, current = current.getParent()) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
