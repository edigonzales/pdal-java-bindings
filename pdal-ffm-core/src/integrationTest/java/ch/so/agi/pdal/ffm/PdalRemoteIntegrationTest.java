package ch.so.agi.pdal.ffm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remote COPC tests against a public dataset. Enable with
 * {@code PDAL_FFM_RUN_REMOTE=true}; requires network access and is not part of
 * the default integration test run.
 *
 * <p>Guards the TLS setup of the bundled runtime: after relocating the conda
 * packages, libcurl's baked-in CA path is rewritten and the bundled CA file is
 * exported as {@code CURL_CA_BUNDLE}/{@code SSL_CERT_FILE}.
 */
@EnabledIfEnvironmentVariable(named = "PDAL_FFM_RUN_REMOTE", matches = "true")
class PdalRemoteIntegrationTest {
    private static final String COPC_URL =
            "https://s3.amazonaws.com/hobu-lidar/autzen-classified.copc.laz";

    @Test
    void previewsRemoteCopc() {
        PdalPreview preview = Pdal.preview("""
                { "pipeline": [ { "type": "readers.copc", "filename": "%s" } ] }
                """.formatted(COPC_URL));

        assertTrue(preview.pointCount() > 9_000_000L, "unexpected point count: " + preview.pointCount());
        assertFalse(preview.srsAuthority().isEmpty(), "CRS authority is empty");
        assertFalse(preview.dimensions().isEmpty(), "dimension layout is empty");
    }

    @Test
    void readsRemoteCopcSubset(@TempDir Path tempDir) {
        Path output = tempDir.resolve("extract.laz");

        PdalResult result = Pdal.execute("""
                { "pipeline": [
                  { "type": "readers.copc", "filename": "%s",
                    "bounds": "([636000,636100],[849000,849100])" },
                  { "type": "writers.las", "filename": "%s" } ] }
                """.formatted(COPC_URL, jsonPath(output)));

        assertTrue(result.pointCount() >= 5_000L, "unexpected subset size: " + result.pointCount());
        assertTrue(result.pointCount() < 6_000L, "unexpected subset size: " + result.pointCount());
        assertTrue(Files.isRegularFile(output), "subset file is missing");
    }

    @Test
    void reportsRemoteErrors() {
        PdalException exception = assertThrowsPdalException(() -> Pdal.preview("""
                { "pipeline": [ { "type": "readers.copc",
                  "filename": "https://example.invalid/missing.copc.laz" } ] }
                """));
        assertFalse(exception.nativeMessage().isBlank(), "native error message is empty");
    }

    private static PdalException assertThrowsPdalException(Runnable action) {
        try {
            action.run();
        } catch (PdalException e) {
            return e;
        }
        throw new AssertionError("expected a PdalException");
    }

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
