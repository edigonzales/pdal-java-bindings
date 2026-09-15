package ch.so.agi.pdal.ffm.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NativeLoaderTest {

    private static final String MANIFEST = """
            {
              "bundleVersion": "2.10.2",
              "ffiLibrary": "lib/libpdal_ffi.dylib",
              "entryLibrary": "lib/libpdalcpp.20.dylib",
              "preloadLibraries": [],
              "gdalDataPath": "share/gdal",
              "projDataPath": "share/proj",
              "pluginPath": "lib",
              "caBundlePath": "ssl/cacert.pem",
              "cacheKey": "cafebabe"
            }
            """;

    @Test
    void resolvesFileTreeBundle(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("manifest.json"), MANIFEST);
        Files.createDirectories(root.resolve("share/gdal"));
        Files.createDirectories(root.resolve("share/proj"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("ssl"));
        Files.writeString(root.resolve("ssl/cacert.pem"), "dummy");

        URL manifestUrl = root.resolve("manifest.json").toUri().toURL();
        NativeManifest manifest = NativeManifest.parse(MANIFEST);

        NativeBundleInfo info = NativeLoader.resolveBundleInfo(manifestUrl, manifest, "osx-aarch64");

        assertEquals(root, info.extractionRoot());
        assertEquals(root.resolve("share/gdal"), info.gdalData());
        assertEquals(root.resolve("share/proj"), info.projData());
        assertEquals(root.resolve("lib"), info.pluginPath());
        assertEquals(root.resolve("ssl/cacert.pem"), info.caBundle());
        assertEquals("2.10.2", info.bundleVersion());
    }

    @Test
    void resolvesMissingOptionalPathsToNull(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("manifest.json"), MANIFEST);
        Files.createDirectories(root.resolve("lib"));

        URL manifestUrl = root.resolve("manifest.json").toUri().toURL();
        NativeManifest manifest = NativeManifest.parse(MANIFEST);

        NativeBundleInfo info = NativeLoader.resolveBundleInfo(manifestUrl, manifest, "osx-aarch64");

        assertNull(info.gdalData());
        assertNull(info.projData());
        assertNull(info.caBundle());
    }
}
