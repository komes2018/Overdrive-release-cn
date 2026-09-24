package com.overdrive.app.overlay;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Pins the app-locale override for the plain recording-status Service. */
public class StatusOverlayLocaleContractTest {

    @Test
    public void statusOverlayUsesAppLocaleAndRefreshesAfterLanguageSelection()
            throws Exception {
        String service = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/overlay/StatusOverlayService.java");
        String picker = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/dialog/LanguagePickerDialog.kt");

        assertTrue(service.contains(
                "AppCompatDelegate.getApplicationLocales()"));
        assertTrue(service.contains(
                "cfg.setLocales(new android.os.LocaleList(appLocales.get(0)))"));
        assertTrue(picker.contains(
                "StatusOverlayService.refreshTheme(context)"));
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
