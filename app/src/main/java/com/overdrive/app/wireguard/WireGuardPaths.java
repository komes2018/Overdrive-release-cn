package com.overdrive.app.wireguard;

import com.overdrive.app.util.DaemonStorage;

/**
 * File locations and constants shared by the WireGuard launcher, controller and
 * the Telegram/web paths.
 *
 * <p>Permissions: HOME 0711, CONFIG 0600 (holds the private key), STATUS and
 * PROXY_FLAG 0644.
 */
public final class WireGuardPaths {
    private WireGuardPaths() {}

    public static final String HOME = DaemonStorage.rebase("/data/local/tmp/.wireguard");
    public static final String CONFIG = HOME + "/wg0.conf";
    public static final String STATUS = HOME + "/status.json";
    public static final String LOG = HOME + "/wireguard.log";
    public static final String BINARY = HOME + "/wgproxy";
    public static final String VERSION_FILE = HOME + "/installed_version";
    public static final String PROXY_FLAG = HOME + "/proxy_enabled";

    /** Loopback SOCKS5 listener of wgproxy. */
    public static final int SOCKS_PORT = 8541;

    public static final String PROCESS_NAME = "wgproxy";

    /** wgproxy exits with this code when the config file changed. */
    public static final int RELOAD_EXIT_CODE = 3;

    public static final String NATIVE_LIB = "libwireguard.so";
}
