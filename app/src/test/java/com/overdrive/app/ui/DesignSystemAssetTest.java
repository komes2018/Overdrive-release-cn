package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.Test;

/** Static contracts for the native and embedded-web design system. */
public class DesignSystemAssetTest {

    @Test
    public void nativeTokensAreSemanticAndThemeAware() throws IOException {
        String light = readRepositoryFile("app/src/main/res/values/colors_m3.xml");
        String dark = readRepositoryFile("app/src/main/res/values-night/colors_m3.xml");
        String theme = readRepositoryFile("app/src/main/res/values/themes_overdrive.xml");
        String nightTheme =
                readRepositoryFile("app/src/main/res/values-night/themes_overdrive.xml");

        assertTrue(light.contains(
                "<color name=\"md_sys_color_primary\">@color/md_sys_color_primary_light</color>"));
        assertTrue(dark.contains(
                "<color name=\"md_sys_color_primary\">@color/md_sys_color_primary_dark</color>"));
        assertFalse(theme.matches("(?s).*@color/md_sys_color_[a-z_]+_light.*"));
        assertFalse(nightTheme.matches("(?s).*@color/md_sys_color_[a-z_]+_dark.*"));
        assertTrue(theme.contains(
                "<item name=\"textAppearanceHeadlineSmall\">"
                        + "@style/TextAppearance.Overdrive.HeadlineSmall</item>"));
        assertTrue(nightTheme.contains(
                "<item name=\"textAppearanceHeadlineSmall\">"
                        + "@style/TextAppearance.Overdrive.HeadlineSmall</item>"));
    }

    @Test
    public void lightPrimaryMeetsNormalTextContrast() throws IOException {
        String colors = readRepositoryFile("app/src/main/res/values/colors_m3.xml");
        String primary = colorValue(colors, "md_sys_color_primary_light");
        String background = colorValue(colors, "md_sys_color_background_light");

        assertTrue("light primary contrast must be at least 4.5:1",
                contrast(primary, background) >= 4.5);
    }

    @Test
    public void sharedShapesAndTypographyStayRestrained() throws IOException {
        String dimensions =
                readRepositoryFile("app/src/main/res/values/dimens_overdrive.xml");
        String typography =
                readRepositoryFile("app/src/main/res/values/themes_overdrive.xml");

        assertTrue(dimensions.contains(
                "<dimen name=\"card_radius_standard\">8dp</dimen>"));
        assertTrue(dimensions.contains("<dimen name=\"card_radius_hero\">8dp</dimen>"));
        assertTrue(dimensions.contains(
                "<dimen name=\"card_radius_accent\">8dp</dimen>"));
        assertFalse(typography.contains("android:letterSpacing\">-"));
        assertTrue(typography.contains("<item name=\"android:minHeight\">48dp</item>"));
    }

    @Test
    public void webAssetsUseLocalTypeAndAccessibleInteractionBaselines() throws IOException {
        Path web = locate("app/src/main/assets/web");
        try (Stream<Path> files = Files.walk(web)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".css")
                            || path.toString().endsWith(".html"))
                    .forEach(path -> {
                        String text;
                        try {
                            text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                        } catch (IOException error) {
                            throw new AssertionError(error);
                        }
                        assertFalse(path + " must not load remote fonts",
                                text.contains("fonts.googleapis.com")
                                        || text.contains("fonts.gstatic.com"));
                        assertFalse(path + " must not use negative tracking",
                                text.matches("(?s).*letter-spacing:\\s*-[0-9.]+(?:em|px).*"));
                    });
        }

        String tokens =
                readRepositoryFile("app/src/main/assets/web/shared/design-tokens.css");
        String styles = readRepositoryFile("app/src/main/assets/web/shared/styles.css");
        String tabs = readRepositoryFile("app/src/main/assets/web/shared/app-tabs.css");
        String themePicker = readRepositoryFile("app/src/main/assets/web/shared/theme.js");
        assertTrue(tokens.contains("--family-sans: system-ui"));
        assertTrue(styles.contains("button:focus,"));
        assertTrue(styles.contains("min-height: 48px"));
        assertTrue(tabs.contains("height: 48px"));
        assertTrue(tabs.contains("html[data-android-embed=\"1\"] .bottom-tabs"));
        assertTrue(tabs.contains("html[data-app-shell=\"1\"] .bottom-tabs"));
        assertFalse(tabs.contains("78px + var(--safe-bottom"));
        assertTrue(themePicker.contains("'   width: 48px; height: 48px; border-radius: 50%;'"));
        assertTrue(styles.contains(":root[data-reduced-motion=\"true\"]"));
        assertTrue(styles.contains("@media (prefers-reduced-motion: reduce)"));
    }

    @Test
    public void webMapsUseKeylessThemeAwareOsmTiles() throws IOException {
        String theme = readRepositoryFile("app/src/main/assets/web/shared/theme.js");
        String safeLocations =
                readRepositoryFile("app/src/main/assets/web/shared/safe-locations.js");
        String webView = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt");

        assertTrue(theme.contains("https://tile.openstreetmap.de/{z}/{x}/{y}.png"));
        assertTrue(theme.contains("saturate(.62) brightness(1.04) contrast(.93)"));
        assertTrue(theme.contains(
                "invert(.91) hue-rotate(180deg) brightness(.66) contrast(.92) saturate(.68)"));
        assertTrue(theme.contains("OpenStreetMap contributors"));
        assertFalse(theme.contains("basemaps.cartocdn.com"));
        assertTrue(safeLocations.contains("BYD.theme.attachMapTiles(this.map);"));
        assertTrue(webView.contains("url.contains(\"tile.openstreetmap.de\")"));
        assertFalse(webView.contains("basemaps.cartocdn.com"));
    }

    @Test
    public void collapsedSidebarGroupsLeaveTheFlexLayout() throws IOException {
        String shell =
                readRepositoryFile("app/src/main/assets/web/shared/app-shell.css");

        assertTrue(shell.contains(
                ".sidebar-nav [hidden] {\n    display: none !important;\n}"));
        assertFalse(shell.contains(
                ".sidebar-nav [hidden] {\n    display: flex !important;"));
    }

    @Test
    public void directionalUiVectorsMirrorInRtl() throws IOException {
        for (String vector : new String[]{"ic_back.xml", "ic_chevron_left.xml",
                "ic_chevron_right.xml"}) {
            String xml = readRepositoryFile("app/src/main/res/drawable/" + vector);
            assertTrue(vector + " must mirror in RTL", xml.contains(
                    "android:autoMirrored=\"true\""));
        }
    }

    private static String colorValue(String xml, String name) {
        String prefix = "<color name=\"" + name + "\">#";
        int start = xml.indexOf(prefix);
        if (start < 0) throw new AssertionError("Missing color " + name);
        start += prefix.length();
        int end = xml.indexOf('<', start);
        return xml.substring(start, end);
    }

    private static double contrast(String first, String second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(String hex) {
        int value = Integer.parseInt(hex, 16);
        double red = channel((value >> 16) & 0xff);
        double green = channel((value >> 8) & 0xff);
        double blue = channel(value & 0xff);
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    private static double channel(int value) {
        double normalized = value / 255.0;
        return normalized <= 0.04045
                ? normalized / 12.92
                : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path file = locate(relativePath);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static Path locate(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.exists(direct)) return direct;

            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.exists(fromModule)) return fromModule;
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository path: " + relativePath);
    }
}
