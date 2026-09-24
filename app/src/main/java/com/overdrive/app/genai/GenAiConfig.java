package com.overdrive.app.genai;

import com.overdrive.app.byd.cloud.crypto.CredentialCipher;
import com.overdrive.app.byd.cloud.crypto.CredentialUpgrade;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.net.URI;
import java.util.Locale;

/**
 * Typed access to the {@code genAi} section of UnifiedConfigManager.
 *
 * <p>The API key follows the exact BYD Cloud credential path: device-bound
 * AES-GCM via {@link CredentialCipher}, transparent plaintext migration, and
 * stable-key upgrade after firmware changes.
 */
public final class GenAiConfig {

    public static final String SECTION = "genAi";

    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_ANTHROPIC = "anthropic";
    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_OPENAI_COMPATIBLE = "openai_compatible";

    public static final String INSIGHT_SCHEDULE_OFF = "off";
    public static final String INSIGHT_SCHEDULE_DAILY = "daily";
    public static final String INSIGHT_SCHEDULE_WEEKLY = "weekly";
    public static final String DEFAULT_INSIGHT_MODE = GenAiContext.OVERVIEW;
    public static final int DEFAULT_INSIGHT_HOUR = 20;
    public static final int DEFAULT_INSIGHT_MINUTE = 0;
    public static final int DEFAULT_INSIGHT_DAY = 7;

    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 1200;
    private static final int MIN_OUTPUT_TOKENS = 64;
    private static final int MAX_OUTPUT_TOKENS = 8192;
    private static final int MAX_API_KEY_CHARS = 8192;
    private static final int MAX_MODEL_CHARS = 200;
    private static final int MAX_BASE_URL_CHARS = 2048;

    private static final DaemonLogger logger =
            DaemonLogger.getInstance("GenAiConfig");

    public final boolean enabled;
    public final String provider;
    public final String baseUrl;
    public final String model;
    public final String realtimeModel;
    public final String apiKey;
    public final int maxOutputTokens;
    public final String insightSchedule;
    public final int insightHour;
    public final int insightMinute;
    public final int insightDay;
    public final String insightMode;
    public final boolean insightDashboard;
    public final boolean insightNotifications;
    public final boolean routineLearningEnabled;

    GenAiConfig(boolean enabled, String provider, String baseUrl, String model,
                String realtimeModel, String apiKey, int maxOutputTokens) {
        this(enabled, provider, baseUrl, model, realtimeModel, apiKey,
                maxOutputTokens, INSIGHT_SCHEDULE_OFF,
                DEFAULT_INSIGHT_HOUR, DEFAULT_INSIGHT_MINUTE,
                DEFAULT_INSIGHT_DAY,
                DEFAULT_INSIGHT_MODE, false, false, false);
    }

    GenAiConfig(boolean enabled, String provider, String baseUrl, String model,
                String realtimeModel, String apiKey, int maxOutputTokens,
                String insightSchedule, int insightHour, int insightMinute,
                int insightDay, String insightMode,
                boolean insightDashboard, boolean insightNotifications) {
        this(enabled, provider, baseUrl, model, realtimeModel, apiKey,
                maxOutputTokens, insightSchedule, insightHour, insightMinute,
                insightDay, insightMode, insightDashboard,
                insightNotifications, false);
    }

    GenAiConfig(boolean enabled, String provider, String baseUrl, String model,
                String realtimeModel, String apiKey, int maxOutputTokens,
                String insightSchedule, int insightHour, int insightMinute,
                int insightDay, String insightMode,
                boolean insightDashboard, boolean insightNotifications,
                boolean routineLearningEnabled) {
        this.enabled = enabled;
        this.provider = normalizeProvider(provider);
        this.baseUrl = normalizeBaseUrlOrDefault(baseUrl, this.provider);
        this.model = cleanModel(model);
        this.realtimeModel = cleanModel(realtimeModel);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.maxOutputTokens = clampTokens(maxOutputTokens);
        this.insightSchedule = normalizeInsightSchedule(insightSchedule);
        this.insightHour = insightHour;
        this.insightMinute = insightMinute;
        this.insightDay = insightDay;
        this.insightMode = normalizeInsightMode(insightMode);
        this.insightDashboard = insightDashboard;
        this.insightNotifications = insightNotifications;
        this.routineLearningEnabled = routineLearningEnabled;
    }

    /** Load and decrypt the current configuration. */
    public static GenAiConfig fromUnifiedConfig() {
        JSONObject root = UnifiedConfigManager.loadConfig();
        JSONObject section = root.optJSONObject(SECTION);
        if (section == null) section = new JSONObject();

        String storedKey = section.optString("apiKey", "");
        String apiKey = CredentialCipher.decrypt(storedKey);
        if (!storedKey.isEmpty() && !CredentialCipher.isEncrypted(storedKey)) {
            migratePlaintextKey(apiKey);
        } else {
            CredentialUpgrade.reEncryptKeyIfLegacy(SECTION, "apiKey");
        }

        String provider = normalizeProvider(
                section.optString("provider", PROVIDER_OPENAI));
        return new GenAiConfig(
                section.optBoolean("enabled", false),
                provider,
                section.optString("baseUrl", defaultBaseUrl(provider)),
                section.optString("model", ""),
                section.optString("realtimeModel", ""),
                apiKey,
                section.optInt("maxOutputTokens", DEFAULT_MAX_OUTPUT_TOKENS),
                section.optString(
                        "insightSchedule", INSIGHT_SCHEDULE_OFF),
                section.optInt("insightHour", DEFAULT_INSIGHT_HOUR),
                section.optInt(
                        "insightMinute", DEFAULT_INSIGHT_MINUTE),
                section.optInt("insightDay", DEFAULT_INSIGHT_DAY),
                section.optString("insightMode", DEFAULT_INSIGHT_MODE),
                section.optBoolean("insightDashboard", false),
                section.optBoolean("insightNotifications", false),
                section.optBoolean("routineLearningEnabled", false)
        );
    }

    /**
     * Persist a complete user-facing configuration update.
     *
     * <p>An omitted or blank {@code apiKey} preserves the current secret only
     * while the provider is unchanged. Switching providers without supplying a
     * new key clears the old provider's credential instead of accidentally
     * sending it to a different vendor. {@code clearApiKey=true} explicitly
     * erases it.
     */
    public static SaveResult save(JSONObject input) {
        if (input == null) return SaveResult.error("Missing configuration.");

        JSONObject currentRoot = UnifiedConfigManager.loadConfig();
        JSONObject existing = currentRoot.optJSONObject(SECTION);
        if (existing == null) existing = new JSONObject();

        try {
            existing = new JSONObject(existing.toString());
            String oldProvider = normalizeProvider(
                    existing.optString("provider", PROVIDER_OPENAI));
            String oldBaseUrl = normalizeBaseUrlOrDefault(
                    existing.optString("baseUrl", ""), oldProvider);
            String provider = input.has("provider")
                    ? normalizeProvider(input.optString("provider", oldProvider))
                    : oldProvider;
            if (!isKnownProvider(provider)) {
                return SaveResult.error("Unsupported provider.");
            }

            String baseUrl;
            if (input.has("baseUrl")) {
                baseUrl = normalizeBaseUrlOrDefault(
                        input.optString("baseUrl", ""), provider);
            } else if (!provider.equals(oldProvider)) {
                baseUrl = defaultBaseUrl(provider);
            } else {
                baseUrl = normalizeBaseUrlOrDefault(
                        existing.optString("baseUrl", ""), provider);
            }

            String model = input.has("model")
                    ? cleanModel(input.optString("model", ""))
                    : cleanModel(existing.optString("model", ""));
            String realtimeModel = input.has("realtimeModel")
                    ? cleanModel(input.optString("realtimeModel", ""))
                    : cleanModel(existing.optString("realtimeModel", ""));
            int maxOutputTokens = input.has("maxOutputTokens")
                    ? clampTokens(input.optInt(
                            "maxOutputTokens", DEFAULT_MAX_OUTPUT_TOKENS))
                    : clampTokens(existing.optInt(
                            "maxOutputTokens", DEFAULT_MAX_OUTPUT_TOKENS));
            String insightSchedule = input.has("insightSchedule")
                    ? normalizeInsightSchedule(input.optString(
                            "insightSchedule", INSIGHT_SCHEDULE_OFF))
                    : normalizeInsightSchedule(existing.optString(
                            "insightSchedule", INSIGHT_SCHEDULE_OFF));
            int insightHour = input.has("insightHour")
                    ? input.optInt("insightHour", -1)
                    : existing.optInt(
                            "insightHour", DEFAULT_INSIGHT_HOUR);
            int insightMinute = input.has("insightMinute")
                    ? input.optInt("insightMinute", -1)
                    : existing.optInt(
                            "insightMinute", DEFAULT_INSIGHT_MINUTE);
            int insightDay = input.has("insightDay")
                    ? input.optInt("insightDay", -1)
                    : existing.optInt("insightDay", DEFAULT_INSIGHT_DAY);
            String insightMode = input.has("insightMode")
                    ? normalizeInsightMode(input.optString(
                            "insightMode", DEFAULT_INSIGHT_MODE))
                    : normalizeInsightMode(existing.optString(
                            "insightMode", DEFAULT_INSIGHT_MODE));
            boolean insightDashboard =
                    input.has("insightDashboard")
                            ? input.optBoolean("insightDashboard", false)
                            : existing.optBoolean(
                                    "insightDashboard", false);
            boolean insightNotifications =
                    input.has("insightNotifications")
                            ? input.optBoolean(
                                    "insightNotifications", false)
                            : existing.optBoolean(
                                    "insightNotifications", false);
            boolean routineLearningEnabled =
                    input.has("routineLearningEnabled")
                            ? input.optBoolean(
                                    "routineLearningEnabled", false)
                            : existing.optBoolean(
                                    "routineLearningEnabled", false);
            boolean enabled = input.has("enabled")
                    ? input.optBoolean("enabled", false)
                    : existing.optBoolean("enabled", false);

            String storedKey = existing.optString("apiKey", "");
            if ((!provider.equals(oldProvider)
                    || !sameCredentialOrigin(oldBaseUrl, baseUrl))
                    && !hasNewApiKey(input)) {
                storedKey = "";
            }
            if (input.optBoolean("clearApiKey", false)) {
                storedKey = "";
            } else if (input.has("apiKey")) {
                String plain = input.optString("apiKey", "").trim();
                if (plain.length() > MAX_API_KEY_CHARS) {
                    return SaveResult.error("API key is too long.");
                }
                if (!plain.isEmpty()) {
                    String encrypted = CredentialCipher.encrypt(plain);
                    if (!CredentialCipher.isEncrypted(encrypted)
                            || !plain.equals(CredentialCipher.decrypt(encrypted))) {
                        logger.warn("Credential encryption failed; GenAI config was not saved");
                        return SaveResult.error(
                                "Could not protect the API key on this device.");
                    }
                    storedKey = encrypted;
                }
            }

            String plainKey = CredentialCipher.decrypt(storedKey);
            GenAiConfig candidate = new GenAiConfig(
                    enabled, provider, baseUrl, model, realtimeModel,
                    plainKey, maxOutputTokens, insightSchedule,
                    insightHour, insightMinute, insightDay, insightMode,
                    insightDashboard, insightNotifications,
                    routineLearningEnabled);
            String validationError = candidate.validationError();
            if (validationError != null) return SaveResult.error(validationError);

            JSONObject section = new JSONObject();
            section.put("enabled", candidate.enabled);
            section.put("provider", candidate.provider);
            section.put("baseUrl", candidate.baseUrl);
            section.put("model", candidate.model);
            section.put("realtimeModel", candidate.realtimeModel);
            section.put("apiKey", storedKey);
            section.put("maxOutputTokens", candidate.maxOutputTokens);
            section.put("insightSchedule", candidate.insightSchedule);
            section.put("insightHour", candidate.insightHour);
            section.put("insightMinute", candidate.insightMinute);
            section.put("insightDay", candidate.insightDay);
            section.put("insightMode", candidate.insightMode);
            section.put("insightDashboard",
                    candidate.insightDashboard);
            section.put(
                    "insightNotifications",
                    candidate.insightNotifications);
            section.put(
                    "routineLearningEnabled",
                    candidate.routineLearningEnabled);

            if (!UnifiedConfigManager.updateSection(SECTION, section)) {
                return SaveResult.error("Could not save GenAI settings.");
            }
            return SaveResult.success(candidate);
        } catch (IllegalArgumentException e) {
            return SaveResult.error(e.getMessage());
        } catch (Exception e) {
            logger.warn("GenAI config save failed: " + e.getMessage());
            return SaveResult.error("Could not save GenAI settings.");
        }
    }

    public boolean isConfigured() {
        if (model.isEmpty() || baseUrl.isEmpty()) return false;
        return !providerRequiresApiKey(provider) || !apiKey.isEmpty();
    }

    public boolean supportsNativeRealtimeAudio() {
        return PROVIDER_OPENAI.equals(provider)
                || PROVIDER_GEMINI.equals(provider);
    }

    public boolean isRealtimeConfigured() {
        return enabled && isConfigured()
                && supportsNativeRealtimeAudio()
                && !realtimeModel.isEmpty();
    }

    /** Public shape; the decrypted key and encrypted blob never leave the daemon. */
    public JSONObject toPublicJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("enabled", enabled);
            json.put("provider", provider);
            json.put("baseUrl", baseUrl);
            json.put("model", model);
            json.put("realtimeModel", realtimeModel);
            json.put("maxOutputTokens", maxOutputTokens);
            json.put("apiKeyConfigured", !apiKey.isEmpty());
            json.put("configured", isConfigured());
            json.put("nativeRealtimeAudioCapable",
                    supportsNativeRealtimeAudio());
            json.put("insightSchedule", insightSchedule);
            json.put("insightHour", insightHour);
            json.put("insightMinute", insightMinute);
            json.put("insightDay", insightDay);
            json.put("insightMode", insightMode);
            json.put("insightDashboard", insightDashboard);
            json.put("insightNotifications", insightNotifications);
            json.put("routineLearningEnabled", routineLearningEnabled);
            json.put("insightScheduleActive",
                    enabled && !INSIGHT_SCHEDULE_OFF.equals(
                            insightSchedule));
        } catch (Exception ignored) {
        }
        return json;
    }

    private String validationError() {
        if (!isKnownInsightSchedule(insightSchedule)) {
            return "Unsupported insight schedule.";
        }
        if (insightHour < 0 || insightHour > 23) {
            return "Insight hour must be between 0 and 23.";
        }
        if (insightMinute < 0 || insightMinute > 59) {
            return "Insight minute must be between 0 and 59.";
        }
        if (insightDay < 1 || insightDay > 7) {
            return "Insight day must be between 1 and 7.";
        }
        if (!GenAiContext.isInsightMode(insightMode)) {
            return "Unsupported insight type.";
        }
        if (!enabled) return null;
        if (!isKnownProvider(provider)) return "Unsupported provider.";
        if (model.isEmpty()) return "Choose a text model before enabling GenAI.";
        if (providerRequiresApiKey(provider) && apiKey.isEmpty()) {
            return "Add the provider API key before enabling GenAI.";
        }
        return null;
    }

    static String normalizeInsightSchedule(String raw) {
        String value = raw == null
                ? "" : raw.trim().toLowerCase(Locale.US);
        return value.isEmpty() ? INSIGHT_SCHEDULE_OFF : value;
    }

    static boolean isKnownInsightSchedule(String value) {
        return INSIGHT_SCHEDULE_OFF.equals(value)
                || INSIGHT_SCHEDULE_DAILY.equals(value)
                || INSIGHT_SCHEDULE_WEEKLY.equals(value);
    }

    static String normalizeInsightMode(String raw) {
        String value = raw == null
                ? "" : raw.trim().toLowerCase(Locale.US);
        return value.isEmpty() ? DEFAULT_INSIGHT_MODE : value;
    }

    public static boolean isKnownProvider(String provider) {
        return PROVIDER_OPENAI.equals(provider)
                || PROVIDER_ANTHROPIC.equals(provider)
                || PROVIDER_GEMINI.equals(provider)
                || PROVIDER_OPENAI_COMPATIBLE.equals(provider);
    }

    public static boolean providerRequiresApiKey(String provider) {
        return !PROVIDER_OPENAI_COMPATIBLE.equals(
                normalizeProvider(provider));
    }

    /**
     * Cheap dashboard gate that deliberately avoids decrypting credentials or
     * touching the provider runtime.
     */
    public static boolean isDashboardPresentationEnabled() {
        try {
            JSONObject section = UnifiedConfigManager.loadConfig()
                    .optJSONObject(SECTION);
            return section != null
                    && section.optBoolean("enabled", false)
                    && section.optBoolean("insightDashboard", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean hasNewApiKey(JSONObject input) {
        return input != null && input.has("apiKey")
                && !input.optString("apiKey", "").trim().isEmpty();
    }

    public static String normalizeProvider(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if ("custom".equals(value) || "compatible".equals(value)
                || "openai-compatible".equals(value)) {
            return PROVIDER_OPENAI_COMPATIBLE;
        }
        return value.isEmpty() ? PROVIDER_OPENAI : value;
    }

    public static String defaultBaseUrl(String provider) {
        String normalized = normalizeProvider(provider);
        if (PROVIDER_ANTHROPIC.equals(normalized)) {
            return "https://api.anthropic.com";
        }
        if (PROVIDER_GEMINI.equals(normalized)) {
            return "https://generativelanguage.googleapis.com";
        }
        if (PROVIDER_OPENAI_COMPATIBLE.equals(normalized)) {
            return "";
        }
        return "https://api.openai.com";
    }

    static String normalizeBaseUrlOrDefault(String raw, String provider) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) value = defaultBaseUrl(provider);
        if (value.isEmpty()) return "";
        if (value.length() > MAX_BASE_URL_CHARS) {
            throw new IllegalArgumentException("Provider URL is too long.");
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme)
                    && !"http".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException(
                        "Provider URL must use HTTP or HTTPS.");
            }
            if (uri.getHost() == null || uri.getHost().trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Provider URL must include a host.");
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException(
                        "Provider URL must not contain credentials.");
            }
            if (uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "Provider URL must not contain a query or fragment.");
            }
            if ("http".equalsIgnoreCase(scheme)) {
                if (!PROVIDER_OPENAI_COMPATIBLE.equals(
                        normalizeProvider(provider))) {
                    throw new IllegalArgumentException(
                            "OpenAI, Anthropic, and Gemini require HTTPS.");
                }
                if (!isPrivateHttpHost(uri.getHost())) {
                    throw new IllegalArgumentException(
                            "HTTP compatible endpoints must use localhost or a private IP address.");
                }
            }
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Provider URL is invalid.");
        }
    }

    private static boolean isPrivateHttpHost(String rawHost) {
        String host = rawHost == null
                ? "" : rawHost.trim().toLowerCase(Locale.ROOT);
        if ("localhost".equals(host) || "::1".equals(host)
                || host.startsWith("127.")) {
            return true;
        }
        String[] octets = host.split("\\.");
        if (octets.length != 4) return false;
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            for (String octet : octets) {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) return false;
            }
            return first == 10
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 169 && second == 254);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    static boolean sameCredentialOrigin(String first, String second) {
        String left = first == null ? "" : first.trim();
        String right = second == null ? "" : second.trim();
        if (left.equals(right)) return true;
        try {
            URI a = new URI(left);
            URI b = new URI(right);
            return a.getScheme().equalsIgnoreCase(b.getScheme())
                    && a.getHost().equalsIgnoreCase(b.getHost())
                    && effectivePort(a) == effectivePort(b);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    static String cleanModel(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > MAX_MODEL_CHARS) {
            throw new IllegalArgumentException("Model name is too long.");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException(
                        "Model name contains invalid characters.");
            }
        }
        return value;
    }

    private static int clampTokens(int value) {
        if (value < MIN_OUTPUT_TOKENS) return MIN_OUTPUT_TOKENS;
        return Math.min(value, MAX_OUTPUT_TOKENS);
    }

    private static void migratePlaintextKey(String plain) {
        if (plain == null || plain.isEmpty()) return;
        try {
            String encrypted = CredentialCipher.encrypt(plain);
            if (!CredentialCipher.isEncrypted(encrypted)) return;
            UnifiedConfigManager.updateSection(
                    SECTION, new JSONObject().put("apiKey", encrypted));
        } catch (Exception ignored) {
            // Legacy plaintext remains readable; retry on the next load.
        }
    }

    public static final class SaveResult {
        public final boolean success;
        public final String error;
        public final GenAiConfig config;

        private SaveResult(boolean success, String error, GenAiConfig config) {
            this.success = success;
            this.error = error;
            this.config = config;
        }

        static SaveResult success(GenAiConfig config) {
            return new SaveResult(true, null, config);
        }

        static SaveResult error(String error) {
            return new SaveResult(false,
                    error == null || error.trim().isEmpty()
                            ? "Invalid GenAI configuration." : error,
                    null);
        }
    }
}
