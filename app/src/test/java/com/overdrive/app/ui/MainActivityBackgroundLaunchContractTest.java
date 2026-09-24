package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Pins system-driven MainActivity launches behind the user's foreground task. */
public class MainActivityBackgroundLaunchContractTest {

    @Test
    public void secondaryDisplayAndWarmLaunchesStayHeadless() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");

        assertTrue(source.contains("android.content.Intent(this, MainActivity::class.java).apply"));
        assertTrue(source.contains("action = intent.action"));
        assertTrue(source.contains("putExtras(intent)"));
        assertTrue(source.contains("putExtra(EXTRA_MINIMIZE_ON_START, true)"));
        assertTrue(source.contains("consumeHeadlessLaunchIntent(intent)"));
        assertTrue(source.contains("if (!headlessLaunch) maybeShowPinLock()"));
        assertTrue(source.contains("Cold system launch — minimizing to background"));
        assertTrue(source.contains("Warm system launch — minimizing to background"));
        assertFalse(source.contains("Any onNewIntent path is by definition the user"));

        assertTrue(
                source.indexOf("Cold system launch — minimizing to background")
                        < source.indexOf("setContentView(R.layout.activity_main_new)"));
        assertFalse(source.contains("android.content.Intent(intent).apply"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
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
