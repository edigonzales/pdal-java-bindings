package ch.so.agi.pdal.ffm.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeManifestTest {

    private static final String MANIFEST = """
            {
              "bundleVersion": "2.10.2",
              "ffiLibrary": "lib/libpdal_ffi.dylib",
              "entryLibrary": "lib/libpdalcpp.20.dylib",
              "preloadLibraries": [
                "lib/libproj.25.dylib"
              ],
              "gdalDataPath": "share/gdal",
              "projDataPath": "share/proj",
              "pluginPath": "lib",
              "caBundlePath": "ssl/cacert.pem",
              "cacheKey": "abc123"
            }
            """;

    @Test
    void parsesAllFields() {
        NativeManifest manifest = NativeManifest.parse(MANIFEST);

        assertEquals("2.10.2", manifest.bundleVersion());
        assertEquals("lib/libpdal_ffi.dylib", manifest.ffiLibrary());
        assertEquals("lib/libpdalcpp.20.dylib", manifest.entryLibrary());
        assertEquals(List.of("lib/libproj.25.dylib"), manifest.preloadLibraries());
        assertEquals("share/gdal", manifest.gdalDataPath());
        assertEquals("share/proj", manifest.projDataPath());
        assertEquals("lib", manifest.pluginPath());
        assertEquals("ssl/cacert.pem", manifest.caBundlePath());
        assertEquals("abc123", manifest.cacheKey());
    }

    @Test
    void parsesEmptyPreloadList() {
        NativeManifest manifest = NativeManifest.parse("""
                { "ffiLibrary": "bin/pdal_ffi.dll", "entryLibrary": "bin/pdalcpp.dll",
                  "preloadLibraries": [] }
                """);

        assertTrue(manifest.preloadLibraries().isEmpty());
        assertEquals("unknown", manifest.bundleVersion());
    }

    @Test
    void rejectsMissingFfiLibrary() {
        assertThrows(IllegalStateException.class, () -> NativeManifest.parse("""
                { "entryLibrary": "lib/libpdalcpp.so.20" }
                """));
    }

    @Test
    void rejectsMissingEntryLibrary() {
        assertThrows(IllegalStateException.class, () -> NativeManifest.parse("""
                { "ffiLibrary": "lib/libpdal_ffi.so" }
                """));
    }
}
