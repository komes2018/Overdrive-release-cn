package com.overdrive.app.byd.cloud;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * BYD overseas country-to-node mapping.
 *
 * Based on hass-byd-vehicle custom_components/byd_vehicle/const.py
 * commit 01dc6b18462cb3bb943cfa0563a7d92f05ae271b (MIT).
 *
 * Region keys are BYD's own and do not always match the country code of the
 * default country. The "no" region is the most surprising: it is the Middle
 * East / Africa node (host: dilinkappoversea-no.byd.auto), NOT Norway —
 * Norway maps to the "eu" region. The "kr" region is hosted at
 * dilinkappoversea-kr.byd.auto; the legacy "kr-ali" key (Aliyun-hosted) is
 * normalized to "kr" by {@link #normalizeRegion(String)}.
 */
public final class BydCloudRegionCatalog {
    public static final String DEFAULT_REGION = "cn";
    public static final String DEFAULT_COUNTRY_CODE = "CN";
    public static final String DEFAULT_LANGUAGE = "zh";

    private static final Map<String, String> COUNTRY_TO_REGION;
    private static final Map<String, String> COUNTRY_TO_LANGUAGE;
    private static final Map<String, String> REGION_TO_DEFAULT_COUNTRY;

    static {
        HashMap<String, String> countryToRegion = new HashMap<>();
        addCountries(countryToRegion, "eu",
                "NO", "NL", "DE", "DK", "SE", "FR", "AT", "LU", "BE", "FI",
                "IT", "ES", "PT", "GB", "IE", "IS", "IL", "HU", "MT", "GR",
                "CH", "PL", "CY", "EE", "LV", "LT", "CZ", "RO", "SK", "SI",
                "BG", "HR", "LI", "ME", "RS", "BA", "MK", "AL", "MD", "MC",
                "VA", "XK", "UA");
        addCountries(countryToRegion, "sg",
                "SG", "TH", "MY", "HK", "MO", "KH", "LA", "PH", "BN", "MM",
                "NP", "BD", "PK", "LK", "PF", "NC", "MN", "BT", "MV");
        addCountries(countryToRegion, "au", "AU", "NZ");
        addCountries(countryToRegion, "br", "BR");
        addCountries(countryToRegion, "jp", "JP");
        addCountries(countryToRegion, "uz", "UZ");
        addCountries(countryToRegion, "no", "AE", "KW", "QA", "MA", "BH", "JO", "ZA", "RE", "MU", "EG");
        addCountries(countryToRegion, "mx",
                "MX", "CL", "UY", "CO", "DO", "CR", "PE", "EC", "PY", "BO",
                "PA", "GT", "SV", "HN", "NI", "AR");
        addCountries(countryToRegion, "id", "ID");
        addCountries(countryToRegion, "tr", "TR");
        addCountries(countryToRegion, "kr", "KR");
        addCountries(countryToRegion, "in", "IN");
        addCountries(countryToRegion, "vn", "VN");
        addCountries(countryToRegion, "sa", "SA");
        addCountries(countryToRegion, "om", "OM");
        addCountries(countryToRegion, "kz", "KZ");
        // China (mainland) — uses the separate CN DiLink stack (WBSK transport,
        // /app/auth/* endpoints, dilinksuperappserver-cn host). See BydCloudConfig.
        addCountries(countryToRegion, "cn", "CN");
        COUNTRY_TO_REGION = Collections.unmodifiableMap(countryToRegion);

        HashMap<String, String> countryToLanguage = new HashMap<>();
        addLanguages(countryToLanguage, "en",
                "AL", "AU", "BD", "BE", "BT", "BA", "BN", "BG", "KH", "HR",
                "CY", "CZ", "EE", "FI", "GR", "HU", "IS", "IN", "IE", "XK",
                "LA", "LV", "LT", "MY", "MV", "MT", "MU", "MN", "ME", "MM",
                "NP", "NZ", "MK", "NO", "PK", "PH", "PL", "RO", "RS", "SG",
                "SK", "SI", "ZA", "LK", "SE", "GB");
        addLanguages(countryToLanguage, "es",
                "AR", "BO", "CL", "CO", "CR", "DO", "EC", "SV", "GT", "HN",
                "MX", "NI", "PA", "PY", "PE", "ES", "UY");
        addLanguages(countryToLanguage, "ar", "BH", "EG", "JO", "KW", "MA", "OM", "QA", "SA", "AE");
        addLanguages(countryToLanguage, "fr", "FR", "PF", "LU", "MC", "NC", "RE");
        addLanguages(countryToLanguage, "de", "AT", "DE", "LI", "CH");
        addLanguages(countryToLanguage, "pt", "BR", "PT");
        addLanguages(countryToLanguage, "zh_TW", "HK", "MO");
        addLanguages(countryToLanguage, "ru", "KZ", "MD", "UA", "UZ");
        addLanguages(countryToLanguage, "id", "ID");
        addLanguages(countryToLanguage, "he", "IL");
        addLanguages(countryToLanguage, "it", "IT", "VA");
        addLanguages(countryToLanguage, "ja", "JP");
        addLanguages(countryToLanguage, "ko", "KR");
        addLanguages(countryToLanguage, "nl", "NL");
        addLanguages(countryToLanguage, "th", "TH");
        addLanguages(countryToLanguage, "tr", "TR");
        addLanguages(countryToLanguage, "vi", "VN");
        addLanguages(countryToLanguage, "zh", "CN");
        COUNTRY_TO_LANGUAGE = Collections.unmodifiableMap(countryToLanguage);

        HashMap<String, String> regionDefaults = new HashMap<>();
        regionDefaults.put("eu", "GB");
        regionDefaults.put("sg", "SG");
        regionDefaults.put("au", "AU");
        regionDefaults.put("br", "BR");
        regionDefaults.put("jp", "JP");
        regionDefaults.put("uz", "UZ");
        regionDefaults.put("no", "AE");
        regionDefaults.put("mx", "MX");
        regionDefaults.put("id", "ID");
        regionDefaults.put("tr", "TR");
        regionDefaults.put("kr", "KR");
        regionDefaults.put("in", "IN");
        regionDefaults.put("vn", "VN");
        regionDefaults.put("sa", "SA");
        regionDefaults.put("om", "OM");
        regionDefaults.put("kz", "KZ");
        regionDefaults.put("cn", "CN");
        REGION_TO_DEFAULT_COUNTRY = Collections.unmodifiableMap(regionDefaults);
    }

    private BydCloudRegionCatalog() {}

    /** BYD region key for mainland China (separate CN DiLink stack). */
    public static final String CHINA_REGION = "cn";

    /** Whether the given (already-normalized) region is the China stack. */
    public static boolean isChinaRegion(String region) {
        return CHINA_REGION.equals(region);
    }

    public static String normalizeCountryCode(String countryCode) {
        if (countryCode == null) return "";
        return countryCode.trim().toUpperCase(Locale.US);
    }

    public static String normalizeRegion(String region) {
        if (region == null) return DEFAULT_REGION;
        String normalized = region.trim().toLowerCase(Locale.US);
        if (normalized.equals("kr-ali")) return "kr";
        return REGION_TO_DEFAULT_COUNTRY.containsKey(normalized) ? normalized : DEFAULT_REGION;
    }

    public static boolean isSupportedCountryCode(String countryCode) {
        return COUNTRY_TO_REGION.containsKey(normalizeCountryCode(countryCode));
    }

    public static String regionForCountryCode(String countryCode) {
        String normalized = normalizeCountryCode(countryCode);
        String region = COUNTRY_TO_REGION.get(normalized);
        return region != null ? region : DEFAULT_REGION;
    }

    public static String languageForCountryCode(String countryCode) {
        String normalized = normalizeCountryCode(countryCode);
        String language = COUNTRY_TO_LANGUAGE.get(normalized);
        return language != null ? language : DEFAULT_LANGUAGE;
    }

    public static String defaultCountryForRegion(String region) {
        String country = REGION_TO_DEFAULT_COUNTRY.get(normalizeRegion(region));
        return country != null ? country : DEFAULT_COUNTRY_CODE;
    }

    private static void addCountries(Map<String, String> target, String region, String... countryCodes) {
        for (String countryCode : countryCodes) {
            target.put(countryCode, region);
        }
    }

    private static void addLanguages(Map<String, String> target, String language, String... countryCodes) {
        for (String countryCode : countryCodes) {
            target.put(countryCode, language);
        }
    }
}
