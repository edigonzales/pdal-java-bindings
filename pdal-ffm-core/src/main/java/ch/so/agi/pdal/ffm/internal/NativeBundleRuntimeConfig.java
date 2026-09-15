package ch.so.agi.pdal.ffm.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Applies the runtime configuration of a bundled PDAL distribution.
 *
 * <p>GDAL (used by PDAL for CRS handling and some drivers) and PROJ read their
 * data directories from process environment variables. The bundled runtime
 * therefore exports {@code GDAL_DATA}, {@code PROJ_DATA}/{@code PROJ_LIB} and
 * {@code PDAL_DRIVER_PATH} through the native shim before the first pipeline
 * executes. Explicit user environment variables win.
 */
final class NativeBundleRuntimeConfig {
    static final String GDAL_DATA = "GDAL_DATA";
    static final String PROJ_LIB = "PROJ_LIB";
    static final String PROJ_DATA = "PROJ_DATA";
    static final String PDAL_DRIVER_PATH = "PDAL_DRIVER_PATH";
    static final String CURL_CA_BUNDLE = "CURL_CA_BUNDLE";
    static final String SSL_CERT_FILE = "SSL_CERT_FILE";

    private NativeBundleRuntimeConfig() {
    }

    /**
     * Environment variables that point into the extracted bundle. User-defined
     * process environment variables are not overridden.
     */
    static Map<String, Path> globalConfigOptions(NativeBundleInfo bundleInfo) {
        return globalConfigOptions(bundleInfo, System.getenv());
    }

    static Map<String, Path> globalConfigOptions(NativeBundleInfo bundleInfo, Map<String, String> environment) {
        Objects.requireNonNull(bundleInfo, "bundleInfo must not be null");
        Objects.requireNonNull(environment, "environment must not be null");

        LinkedHashMap<String, Path> options = new LinkedHashMap<>();
        if (!hasValue(environment.get(GDAL_DATA))) {
            putIfPresent(options, GDAL_DATA, bundleInfo.gdalData());
        }
        if (!hasValue(environment.get(PROJ_DATA)) && !hasValue(environment.get(PROJ_LIB))) {
            putIfPresent(options, PROJ_DATA, bundleInfo.projData());
            putIfPresent(options, PROJ_LIB, bundleInfo.projData());
        }
        if (!hasValue(environment.get(PDAL_DRIVER_PATH))) {
            putIfPresent(options, PDAL_DRIVER_PATH, bundleInfo.pluginPath());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    static Map<String, String> scopedConfigOptions(NativeBundleInfo bundleInfo) {
        return scopedConfigOptions(bundleInfo, System.getenv(), System.getProperties());
    }

    static Map<String, String> scopedConfigOptions(
            NativeBundleInfo bundleInfo,
            Map<String, String> environment,
            Properties systemProperties
    ) {
        Objects.requireNonNull(bundleInfo, "bundleInfo must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(systemProperties, "systemProperties must not be null");

        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        Path bundledCa = bundledCaBundle(bundleInfo, environment, systemProperties);
        if (bundledCa != null) {
            String bundledCaPath = bundledCa.toAbsolutePath().toString();
            options.put(CURL_CA_BUNDLE, bundledCaPath);
            options.put(SSL_CERT_FILE, bundledCaPath);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    static Path bundledCaBundle(
            NativeBundleInfo bundleInfo,
            Map<String, String> environment,
            Properties systemProperties
    ) {
        Objects.requireNonNull(bundleInfo, "bundleInfo must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(systemProperties, "systemProperties must not be null");

        if (!isUnixClassifier(bundleInfo.classifier()) || hasUserDefinedCaOption(environment, systemProperties)) {
            return null;
        }

        Path caBundle = bundleInfo.caBundle();
        if (caBundle == null || !Files.isRegularFile(caBundle)) {
            return null;
        }
        return caBundle.toAbsolutePath();
    }

    private static boolean isUnixClassifier(String classifier) {
        return classifier.startsWith("linux-") || classifier.startsWith("osx-");
    }

    private static boolean hasUserDefinedCaOption(Map<String, String> environment, Properties systemProperties) {
        return hasValue(environment.get(CURL_CA_BUNDLE))
                || hasValue(environment.get(SSL_CERT_FILE))
                || hasValue(systemProperties.getProperty(CURL_CA_BUNDLE))
                || hasValue(systemProperties.getProperty(SSL_CERT_FILE));
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private static void putIfPresent(Map<String, Path> options, String key, Path value) {
        if (value != null) {
            options.put(key, value.toAbsolutePath());
        }
    }
}
