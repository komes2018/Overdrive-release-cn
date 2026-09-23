package com.overdrive.app.byd.cloud;

import com.overdrive.app.byd.cloud.crypto.CredentialCipher;
import com.overdrive.app.byd.cloud.crypto.CredentialUpgrade;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

/**
 * BYD Cloud API configuration.
 * Reads credentials from the bydCloud section of UnifiedConfigManager.
 */
public final class BydCloudConfig {

    private static final DaemonLogger logger = DaemonLogger.getInstance("BydCloudConfig");

    private static final String BASE_URL_PREFIX = "https://dilinkappoversea-";
    private static final String BASE_URL_SUFFIX = ".byd.auto";
    private static final String USER_AGENT = "okhttp/4.12.0";

    // ── China (CN) stack ────────────────────────────────────────────────
    // The CN cloud stack talks to a different host and uses the WBSK transport
    // codec + /app/auth/* endpoints. Region detection by host containing
    // "cn.byd.auto". Defaults match common CN cloud builds.
    private static final String CN_BASE_URL = "https://dilinksuperappserver-cn.byd.auto";
    public static final String CN_APP_CHANNEL = "99";
    public static final String CN_APP_VERSION = "9.11.2";
    public static final String CN_APP_INNER_VERSION = "512";
    public static final String CN_TARGET_BRAND = "1";   // 1 = dynasty
    public static final String CN_VEHICLE_BRAND = "1";
    public static final String CN_NETWORK_OPERATOR = "中国电信";
    public static final String CN_BRAND_FLAG = "dynasty";

    // ── CN login identifier type ────────────────────────────────────────
    // The CN /app/auth/login payload carries an int `loginType` next to
    // `identifier`. BYD's own app offers several console login modes, and 0 is
    // what the stock CN email/account flow sends. A bare mobile number is
    // believed to use 1 — that is NOT verified against a live server, so the
    // value stays overridable through bydCloud.cnLoginType in the unified
    // config (accepts "auto", or any int) to test another value without a
    // rebuild. AUTO keeps every pre-existing email account on 0.
    public static final int CN_LOGIN_TYPE_AUTO = -1;
    public static final int CN_LOGIN_TYPE_EMAIL = 0;
    public static final int CN_LOGIN_TYPE_PHONE = 1;

    public final boolean enabled;
    public final String username;
    public final String loginKey;
    public final String signPassword;
    public final String commandPwd;
    public final String rawPassword;
    public final String vin;
    public final String countryCode;
    public final String language;
    public final String region;        // Server region: eu, in, sg, au, br, etc.
    public final String imeiMd5;
    public final String appInnerVersion;
    public final String appVersion;
    public final boolean cloudDataMerge; // Toggle: merge cloud telemetry into vehicle data
    public final String energyType;      // From vehicle list: PHEV/BEV identifier
    public final int cnLoginType;        // CN only; always emitted as an int on the wire
    public final boolean isShared;       // CN: true if vehicle has empowerId (authorized/shared account)
    public final String targetBrand;     // CN: 1=dynasty, 2=ocean, 3=denza, 4=yangwang, 5=fangchengbao

    private BydCloudConfig(boolean enabled, String username, String loginKey,
                           String signPassword, String commandPwd, String rawPassword,
                           String vin, String countryCode, String language, String region,
                           boolean cloudDataMerge, String energyType, int cnLoginType,
                           boolean isShared, String targetBrand) {
        this.enabled = enabled;
        this.username = username;
        this.loginKey = loginKey;
        this.signPassword = signPassword;
        this.commandPwd = commandPwd;
        this.rawPassword = rawPassword;
        this.vin = vin;
        String normalizedRegion = BydCloudRegionCatalog.normalizeRegion(region);
        String normalizedCountryCode = BydCloudRegionCatalog.normalizeCountryCode(countryCode);
        if (!normalizedCountryCode.isEmpty()
                && !BydCloudRegionCatalog.isSupportedCountryCode(normalizedCountryCode)) {
            logger.warn("Unsupported BYD countryCode=" + normalizedCountryCode
                    + "; falling back to default for region=" + normalizedRegion);
        }
        this.countryCode = BydCloudRegionCatalog.isSupportedCountryCode(normalizedCountryCode)
                ? normalizedCountryCode
                : BydCloudRegionCatalog.defaultCountryForRegion(normalizedRegion);
        this.language = (language != null && !language.trim().isEmpty())
                ? language.trim()
                : BydCloudRegionCatalog.languageForCountryCode(this.countryCode);
        this.region = BydCloudRegionCatalog.regionForCountryCode(this.countryCode);
        this.cloudDataMerge = cloudDataMerge;
        this.energyType = energyType != null ? energyType : "";
        this.cnLoginType = resolveCnLoginType(cnLoginType, username);
        this.isShared = isShared;
        this.targetBrand = (targetBrand != null && !targetBrand.trim().isEmpty())
                ? targetBrand.trim() : CN_TARGET_BRAND;
        // Device fingerprint derived from username (matches Niek/BYD-re)
        this.imeiMd5 = (username != null && !username.isEmpty())
                ? com.overdrive.app.byd.cloud.crypto.BydCryptoUtils.md5Hex(username)
                : "00000000000000000000000000000000";
        // CN app reports a different version lineage than overseas. These feed
        // the inner "version"/"appInnerVersion"/"appVersion" payload fields.
        if (BydCloudRegionCatalog.isChinaRegion(this.region)) {
            this.appInnerVersion = CN_APP_INNER_VERSION;
            this.appVersion = CN_APP_VERSION;
        } else {
            this.appInnerVersion = "323";
            this.appVersion = "3.2.3";
        }
    }

    /**
     * Redact a login identifier for logs.
     *
     * Keeps the first character and, for an email, the domain (useful when
     * telling two accounts apart); never assumes an '@' is present — a CN
     * account may be a bare mobile number, and the old inline
     * {@code substring(indexOf('@') + 1)} threw StringIndexOutOfBoundsException
     * on exactly that input.
     */
    public static String maskIdentifier(String identifier) {
        if (identifier == null || identifier.length() < 2) return "***";
        int at = identifier.indexOf('@');
        return (at > 0)
                ? identifier.charAt(0) + "***" + identifier.substring(at)
                : identifier.charAt(0) + "***";
    }

    /**
     * Parse the optional {@code bydCloud.cnLoginType} override.
     * Empty or "auto" → {@link #CN_LOGIN_TYPE_AUTO}; any non-negative int is
     * taken verbatim so a new wire value can be probed on-device.
     */
    private static int parseCnLoginType(String raw) {
        if (raw == null) return CN_LOGIN_TYPE_AUTO;
        String value = raw.trim();
        if (value.isEmpty() || "auto".equalsIgnoreCase(value)) return CN_LOGIN_TYPE_AUTO;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= 0) return parsed;
            logger.warn("Ignoring negative bydCloud.cnLoginType=" + raw);
        } catch (NumberFormatException e) {
            logger.warn("Ignoring non-numeric bydCloud.cnLoginType=" + raw);
        }
        return CN_LOGIN_TYPE_AUTO;
    }

    /**
     * Resolve the CN login identifier type. An explicit non-negative override
     * wins; otherwise defaults to 0 (matches HA byd_china tested wire behavior
     * for both mobile numbers and email accounts).
     */
    private static int resolveCnLoginType(int configured, String username) {
        if (configured >= 0) return configured;
        return CN_LOGIN_TYPE_EMAIL; // 0
    }

    /**
     * Load config from UnifiedConfigManager.
     * Handles legacy plaintext values transparently.
     */
    public static BydCloudConfig fromUnifiedConfig() {
        JSONObject config = UnifiedConfigManager.loadConfig();
        JSONObject bydCloud = config.optJSONObject("bydCloud");
        if (bydCloud == null) {
            return new BydCloudConfig(false, "", "", "", "", "", "",
                    BydCloudRegionCatalog.DEFAULT_COUNTRY_CODE,
                    BydCloudRegionCatalog.DEFAULT_LANGUAGE,
                    BydCloudRegionCatalog.DEFAULT_REGION, false, "",
                    CN_LOGIN_TYPE_AUTO, false, CN_TARGET_BRAND);
        }

        String storedRawPassword = bydCloud.optString("rawPassword", "");
        String rawPassword = CredentialCipher.decrypt(storedRawPassword);

        // Migrate legacy plaintext to protected form on first read
        if (!storedRawPassword.isEmpty() && !CredentialCipher.isEncrypted(storedRawPassword)) {
            migrateRawPassword(bydCloud, rawPassword);
        } else {
            // Upgrade a legacy firmware-fingerprint-bound ciphertext to the
            // stable device-id-only key so a future OTA can't strand it (same
            // OTA-invalidation class as the Telegram token). No-op once stable.
            // Single-key-delta + CAS so a concurrent clear/rotate of other
            // bydCloud keys (or the password itself) isn't resurrected.
            CredentialUpgrade.reEncryptKeyIfLegacy("bydCloud", "rawPassword");
        }

        return new BydCloudConfig(
                bydCloud.optBoolean("enabled", false),
                bydCloud.optString("username", ""),
                bydCloud.optString("loginKey", ""),
                bydCloud.optString("signPassword", ""),
                bydCloud.optString("commandPwd", ""),
                rawPassword,
                bydCloud.optString("vin", ""),
                bydCloud.optString("countryCode", BydCloudRegionCatalog.DEFAULT_COUNTRY_CODE),
                bydCloud.optString("language", BydCloudRegionCatalog.DEFAULT_LANGUAGE),
                bydCloud.optString("region", BydCloudRegionCatalog.DEFAULT_REGION),
                bydCloud.optBoolean("cloudDataMerge", false),
                bydCloud.optString("energyType", ""),
                parseCnLoginType(bydCloud.optString("cnLoginType", "")),
                bydCloud.optBoolean("isShared", false),
                bydCloud.optString("targetBrand", CN_TARGET_BRAND)
        );
    }

    /**
     * Migrate a legacy plaintext value to protected form.
     */
    private static void migrateRawPassword(JSONObject bydCloud, String plainPassword) {
        try {
            String encrypted = CredentialCipher.encrypt(plainPassword);
            if (!CredentialCipher.isEncrypted(encrypted)) return;  // fail-open guard: never write plaintext back
            // Single-key delta, NOT the whole (read-earlier, now-stale) section:
            // updateSection merges per-key under its file lock, so writing only
            // rawPassword preserves a concurrent clearCredentials()/saveCredentials()
            // on other keys (no resurrect of a just-cleared enabled/username).
            JSONObject delta = new JSONObject();
            delta.put("rawPassword", encrypted);
            UnifiedConfigManager.updateSection("bydCloud", delta);
        } catch (Exception e) {
            // Best-effort — plaintext still works, will migrate on next save
        }
    }

    /**
     * Check if all required credentials are configured.
     */
    public boolean isConfigured() {
        return enabled
                && !username.isEmpty()
                && !loginKey.isEmpty()
                && !signPassword.isEmpty()
                && !commandPwd.isEmpty();
    }

    /**
     * Check if credentials have been verified (login + VIN + PIN all succeeded).
     */
    public boolean isVerified() {
        return isConfigured() && !vin.isEmpty();
    }

    /** Whether this config uses the China (CN) DiLink stack. */
    public boolean isChinaRegion() {
        return BydCloudRegionCatalog.isChinaRegion(region);
    }

    public String getBaseUrl() {
        // `region` is already normalized at construction time, so no extra
        // normalize() needed here. China uses a distinct host; every other
        // region keeps the unchanged dilinkappoversea-<region> pattern.
        if (isChinaRegion()) {
            return CN_BASE_URL;
        }
        return BASE_URL_PREFIX + region + BASE_URL_SUFFIX;
    }

    public String getUserAgent() {
        return USER_AGENT;
    }

    /**
     * Save credentials to UnifiedConfigManager.
     */
    public static void saveCredentials(String username, String loginKey,
                                       String signPassword, String commandPwd,
                                       String rawPassword,
                                       String vin, String countryCode, String language,
                                       String region) {
        saveCredentials(username, loginKey, signPassword, commandPwd, rawPassword,
                vin, countryCode, language, region, "", false);
    }

    public static void saveCredentials(String username, String loginKey,
                                       String signPassword, String commandPwd,
                                       String rawPassword,
                                       String vin, String countryCode, String language,
                                       String region, String energyType,
                                       boolean cloudDataMerge) {
        saveCredentials(username, loginKey, signPassword, commandPwd, rawPassword,
                vin, countryCode, language, region, energyType, cloudDataMerge, false, CN_TARGET_BRAND);
    }

    public static void saveCredentials(String username, String loginKey,
                                       String signPassword, String commandPwd,
                                       String rawPassword,
                                       String vin, String countryCode, String language,
                                       String region, String energyType,
                                       boolean cloudDataMerge, boolean isShared,
                                       String targetBrand) {
        JSONObject bydCloud = new JSONObject();
        try {
            bydCloud.put("enabled", true);
            bydCloud.put("username", username);
            bydCloud.put("loginKey", loginKey);
            bydCloud.put("signPassword", signPassword);
            bydCloud.put("commandPwd", commandPwd);
            String encPw = CredentialCipher.encrypt(rawPassword);
            // Never persist a non-empty password in cleartext (encrypt() is
            // fail-open on a JCE error). An empty password legitimately stays
            // "" (encrypt passes empties through), so only abort when a real
            // secret failed to encrypt. Void method → skip the write entirely
            // (losing the save beats writing a bare password to the 0666 store).
            if (rawPassword != null && !rawPassword.isEmpty()
                    && !CredentialCipher.isEncrypted(encPw)) {
                logger.warn("Credential encryption failed; aborting BYD-cloud save (not persisting plaintext)");
                return;
            }
            bydCloud.put("rawPassword", encPw);
            bydCloud.put("vin", vin);
            String normalizedCountryCode = BydCloudRegionCatalog.normalizeCountryCode(countryCode);
            if (!BydCloudRegionCatalog.isSupportedCountryCode(normalizedCountryCode)) {
                normalizedCountryCode = BydCloudRegionCatalog.defaultCountryForRegion(region);
            }
            String normalizedRegion = BydCloudRegionCatalog.regionForCountryCode(normalizedCountryCode);
            bydCloud.put("countryCode", normalizedCountryCode);
            bydCloud.put("language", (language != null && !language.trim().isEmpty())
                    ? language.trim()
                    : BydCloudRegionCatalog.languageForCountryCode(normalizedCountryCode));
            bydCloud.put("region", normalizedRegion);
            bydCloud.put("cloudDataMerge", cloudDataMerge);
            if (energyType != null && !energyType.isEmpty()) {
                bydCloud.put("energyType", energyType);
            }
            bydCloud.put("isShared", isShared);
            if (targetBrand != null && !targetBrand.trim().isEmpty()) {
                bydCloud.put("targetBrand", targetBrand.trim());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to build config JSON", e);
        }
        UnifiedConfigManager.updateSection("bydCloud", bydCloud);
    }

    /**
     * Clear stored credentials.
     */
    public static void clearCredentials() {
        JSONObject bydCloud = new JSONObject();
        try {
            bydCloud.put("enabled", false);
            bydCloud.put("username", "");
            bydCloud.put("loginKey", "");
            bydCloud.put("signPassword", "");
            bydCloud.put("commandPwd", "");
            bydCloud.put("rawPassword", "");
            bydCloud.put("vin", "");
        } catch (Exception ignored) {}
        UnifiedConfigManager.updateSection("bydCloud", bydCloud);
    }
}
