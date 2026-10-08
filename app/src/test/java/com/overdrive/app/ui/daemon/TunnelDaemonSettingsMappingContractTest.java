package com.overdrive.app.ui.daemon;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards tunnel daemon rows against opening another tunnel's settings. */
public class TunnelDaemonSettingsMappingContractTest {

    @Test
    public void eachTunnelTypeOpensItsOwnSettingsUi() throws IOException {
        String fragment = read(
                "app/src/main/java/com/overdrive/app/ui/fragment/DaemonsFragment.kt");

        assertTrue(fragment.contains(
                "DaemonType.ZROK_TUNNEL -> showZrokTokenDialog()"));
        assertTrue(fragment.contains(
                "DaemonType.TAILSCALE_TUNNEL -> showTailscaleSettingsDialog()"));
        assertTrue(fragment.contains(
                "DaemonType.WIREGUARD_TUNNEL -> showWireGuardSettingsDialog()"));
        assertTrue(fragment.contains(
                "DaemonType.CLOUDFLARED_TUNNEL -> {"));
        assertTrue(fragment.contains(
                "CloudflaredPaidConfig.showSettingsDialog(requireContext(), daemonsViewModel)"));

        assertTrue(fragment.contains(
                "inflate(R.layout.dialog_zrok_token, null)"));
        assertTrue(fragment.contains(
                "inflate(R.layout.dialog_tailscale_settings, null)"));

        String wireguard = read(
                "app/src/main/java/com/overdrive/app/ui/daemon/"
                        + "WireGuardSettingsDialog.kt");
        assertTrue(wireguard.contains(
                "inflate(R.layout.dialog_wireguard_settings, null)"));

        String cloudflare = read(
                "app/src/main/java/com/overdrive/app/config/CloudflaredPaidConfig.kt");
        assertTrue(cloudflare.contains(
                "inflate(R.layout.dialog_cloudflared_settings, null)"));
    }

    @Test
    public void daemonCardPassesTheBoundTypeToConfigureActions() throws IOException {
        String adapter = read(
                "app/src/main/java/com/overdrive/app/ui/adapter/DaemonAdapter.kt");

        for (String type : new String[] {
                "CLOUDFLARED_TUNNEL", "ZROK_TUNNEL", "TAILSCALE_TUNNEL", "WIREGUARD_TUNNEL"
        }) {
            assertTrue(adapter.contains("state.type == DaemonType." + type));
        }
        assertTrue(adapter.contains("onConfigureClick?.invoke(state.type)"));
        assertTrue(adapter.contains("onConfigureClick.invoke(state.type)"));
    }

    @Test
    public void eachTunnelTypeUsesItsMatchingController() throws IOException {
        String viewModel = read(
                "app/src/main/java/com/overdrive/app/ui/viewmodel/DaemonsViewModel.kt");

        assertTrue(viewModel.contains(
                "DaemonType.CLOUDFLARED_TUNNEL to cloudflaredController"));
        assertTrue(viewModel.contains(
                "DaemonType.ZROK_TUNNEL to zrokController"));
        assertTrue(viewModel.contains(
                "DaemonType.TAILSCALE_TUNNEL to tailscaleController"));
        assertTrue(viewModel.contains(
                "DaemonType.WIREGUARD_TUNNEL to wireguardController"));

        assertControllerType(
                "CloudflaredController.kt", "CLOUDFLARED_TUNNEL");
        assertControllerType("ZrokController.kt", "ZROK_TUNNEL");
        assertControllerType("TailscaleController.kt", "TAILSCALE_TUNNEL");
        assertControllerType("WireGuardController.kt", "WIREGUARD_TUNNEL");
    }

    @Test
    public void settingsPaneHostsTheCanonicalDaemonScreen() throws IOException {
        String settingsHost = read(
                "app/src/main/java/com/overdrive/app/ui/fragment/settings/"
                        + "SettingsDaemonsFragment.kt");

        assertTrue(settingsHost.contains("replace(HOST_ID, DaemonsFragment()"));
    }

    private static void assertControllerType(String file, String type)
            throws IOException {
        String controller = read(
                "app/src/main/java/com/overdrive/app/ui/daemon/" + file);
        assertTrue(controller.contains(
                "override val type = DaemonType." + type));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate),
                        StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError(
                "Could not locate repository file: " + relativePath);
    }
}
