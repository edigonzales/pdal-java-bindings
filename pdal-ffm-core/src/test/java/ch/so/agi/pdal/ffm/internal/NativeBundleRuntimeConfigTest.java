package ch.so.agi.pdal.ffm.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeBundleRuntimeConfigTest {

    private static NativeBundleInfo bundleInfo(Path root, String classifier, Path caBundle) {
        return new NativeBundleInfo(
                classifier,
                "2.10.2",
                root,
                root.resolve("share/gdal"),
                root.resolve("share/proj"),
                root.resolve("lib"),
                caBundle
        );
    }

    @Test
    void exportsBundledDataPaths(@TempDir Path root) {
        NativeBundleInfo info = bundleInfo(root, "osx-aarch64", null);
        Map<String, Path> options = NativeBundleRuntimeConfig.globalConfigOptions(info, Map.of());

        assertEquals(root.resolve("share/gdal").toAbsolutePath(), options.get("GDAL_DATA"));
        assertEquals(root.resolve("share/proj").toAbsolutePath(), options.get("PROJ_DATA"));
        assertEquals(root.resolve("share/proj").toAbsolutePath(), options.get("PROJ_LIB"));
        assertEquals(root.resolve("lib").toAbsolutePath(), options.get("PDAL_DRIVER_PATH"));
    }

    @Test
    void userEnvironmentWins(@TempDir Path root) {
        NativeBundleInfo info = bundleInfo(root, "linux-x86_64", null);
        Map<String, Path> options = NativeBundleRuntimeConfig.globalConfigOptions(info, Map.of(
                "GDAL_DATA", "/user/gdal",
                "PROJ_DATA", "/user/proj",
                "PDAL_DRIVER_PATH", "/user/plugins"
        ));

        assertTrue(options.isEmpty(), "user-defined environment variables must not be overridden");
    }

    @Test
    void legacyProjLibDisablesProjDataExport(@TempDir Path root) {
        NativeBundleInfo info = bundleInfo(root, "linux-x86_64", null);
        Map<String, Path> options = NativeBundleRuntimeConfig.globalConfigOptions(info, Map.of(
                "PROJ_LIB", "/user/proj"
        ));

        assertFalse(options.containsKey("PROJ_DATA"));
        assertFalse(options.containsKey("PROJ_LIB"));
    }

    @Test
    void usesBundledCaBundleOnUnix(@TempDir Path root) throws IOException {
        Path caBundle = root.resolve("ssl/cacert.pem");
        Files.createDirectories(caBundle.getParent());
        Files.writeString(caBundle, "dummy");

        NativeBundleInfo info = bundleInfo(root, "linux-x86_64", caBundle);
        Map<String, String> options = NativeBundleRuntimeConfig.scopedConfigOptions(info, Map.of(), new Properties());

        assertEquals(caBundle.toAbsolutePath().toString(), options.get("CURL_CA_BUNDLE"));
        assertEquals(caBundle.toAbsolutePath().toString(), options.get("SSL_CERT_FILE"));
    }

    @Test
    void ignoresCaBundleWhenUserDefined(@TempDir Path root) throws IOException {
        Path caBundle = root.resolve("ssl/cacert.pem");
        Files.createDirectories(caBundle.getParent());
        Files.writeString(caBundle, "dummy");

        NativeBundleInfo info = bundleInfo(root, "linux-x86_64", caBundle);
        Map<String, String> options = NativeBundleRuntimeConfig.scopedConfigOptions(
                info, Map.of("SSL_CERT_FILE", "/user/ca.pem"), new Properties());

        assertTrue(options.isEmpty());
    }

    @Test
    void ignoresCaBundleOnWindows(@TempDir Path root) throws IOException {
        Path caBundle = root.resolve("ssl/cacert.pem");
        Files.createDirectories(caBundle.getParent());
        Files.writeString(caBundle, "dummy");

        NativeBundleInfo info = bundleInfo(root, "windows-x86_64", caBundle);
        Map<String, String> options = NativeBundleRuntimeConfig.scopedConfigOptions(info, Map.of(), new Properties());

        assertTrue(options.isEmpty());
    }
}
