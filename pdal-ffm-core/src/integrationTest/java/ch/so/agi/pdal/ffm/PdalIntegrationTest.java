package ch.so.agi.pdal.ffm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against the staged host native bundle. Enabled through
 * the {@code integrationTest} Gradle task (PDAL_FFM_RUN_INTEGRATION=true).
 */
class PdalIntegrationTest {

    @Test
    void reportsBundledVersion() {
        assertTrue(Pdal.version().startsWith("2.10."), "unexpected PDAL version: " + Pdal.version());
    }

    @Test
    void generatesAndCropsPoints(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("input.laz");
        Path output = tempDir.resolve("output.laz");

        PdalResult generated = Pdal.execute("""
                {
                  "pipeline": [
                    { "type": "readers.faux", "mode": "ramp",
                      "bounds": "([0,100],[0,100],[0,10])", "count": 2000 },
                    { "type": "writers.las", "filename": "%s" }
                  ]
                }
                """.formatted(jsonPath(input)));
        assertEquals(2000, generated.pointCount());
        assertTrue(Files.isRegularFile(input));

        PdalResult cropped = Pdal.execute("""
                {
                  "pipeline": [
                    "%s",
                    { "type": "filters.crop", "bounds": "([40,60],[40,60],[0,10])" },
                    { "type": "writers.las", "filename": "%s" }
                  ]
                }
                """.formatted(jsonPath(input), jsonPath(output)));
        assertTrue(cropped.pointCount() > 0, "crop produced no points");
        assertTrue(cropped.pointCount() < 2000, "crop did not reduce the point count");
        assertTrue(Files.isRegularFile(output));

        assertFalse(cropped.metadataJson().isBlank(), "metadata JSON is empty");
        assertTrue(cropped.metadataJson().startsWith("{"), "metadata is not a JSON object");
    }

    @Test
    void previewsPointCloudWithoutExecuting(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("preview-input.laz");
        Pdal.execute("""
                {
                  "pipeline": [
                    { "type": "readers.faux", "mode": "ramp",
                      "bounds": "([0,10],[0,20],[0,5])", "count": 1234 },
                    { "type": "writers.las", "filename": "%s" }
                  ]
                }
                """.formatted(jsonPath(input)));

        PdalPreview preview = Pdal.preview("""
                { "pipeline": [ { "type": "readers.las", "filename": "%s" } ] }
                """.formatted(jsonPath(input)));

        assertEquals(1234, preview.pointCount());
        assertNotNull(preview.bounds(), "bounds are missing");
        assertEquals(0.0, preview.bounds().minX(), 0.02);
        assertEquals(10.0, preview.bounds().maxX(), 0.02);
        assertEquals(20.0, preview.bounds().maxY(), 0.02);
        assertEquals(5.0, preview.bounds().maxZ(), 0.02);
        assertTrue(
                preview.dimensions().stream()
                        .anyMatch(d -> d.name().equals("X") && d.type().equals("FLOAT64")),
                "X dimension is missing");
        assertTrue(
                preview.dimensions().stream()
                        .anyMatch(d -> d.name().equals("Intensity") && d.type().equals("UINT16")),
                "Intensity dimension is missing");
        assertTrue(preview.srsWkt().isEmpty(), "unexpected CRS: " + preview.srsWkt());
    }

    @Test
    void reportsNativeErrors() {
        PdalException exception = assertThrows(PdalException.class, () -> Pdal.execute("""
                { "pipeline": [ { "type": "readers.does-not-exist" } ] }
                """));
        assertFalse(exception.nativeMessage().isBlank(), "native error message is empty");
    }

    @Test
    void rejectsBlankPipeline() {
        assertThrows(IllegalArgumentException.class, () -> Pdal.execute("  "));
    }

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
