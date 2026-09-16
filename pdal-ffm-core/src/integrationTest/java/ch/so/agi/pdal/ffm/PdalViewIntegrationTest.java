package ch.so.agi.pdal.ffm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Block-wise point view access against the staged host native bundle. Enabled
 * through the {@code integrationTest} Gradle task.
 */
class PdalViewIntegrationTest {

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static Path generate(Path directory, int count) {
        Path input = directory.resolve("view-input.laz");
        Pdal.execute("""
                {
                  "pipeline": [
                    { "type": "readers.faux", "mode": "ramp",
                      "bounds": "([0,100],[0,100],[0,10])", "count": %d },
                    { "type": "writers.las", "filename": "%s" }
                  ]
                }
                """.formatted(count, jsonPath(input)));
        return input;
    }

    @Test
    void readsPointViewsInBlocks(@TempDir Path tempDir) {
        Path input = generate(tempDir, 2500);

        try (PdalView view = Pdal.open("""
                { "pipeline": [ { "type": "readers.las", "filename": "%s" } ] }
                """.formatted(jsonPath(input)))) {

            assertEquals(2500, view.pointCount());
            assertEquals(1, view.viewCount());
            assertEquals(2500, view.pointCount(0));

            var dimensions = view.dimensions(0);
            assertTrue(
                    dimensions.stream().anyMatch(d -> d.name().equals("X") && d.type().equals("FLOAT64")),
                    "X dimension is missing: " + dimensions);
            assertTrue(
                    dimensions.stream()
                            .anyMatch(d -> d.name().equals("Classification") && d.type().equals("UINT8")),
                    "Classification dimension is missing: " + dimensions);

            double[] x = view.readDoubles(0, "X", 0, 100);
            assertEquals(100, x.length);
            for (double value : x) {
                assertTrue(value >= 0 && value <= 100.5, "unexpected X value: " + value);
            }

            double[] rest = view.readDoubles(0, "X", 2400, 100);
            assertEquals(100, rest.length);
            assertTrue(rest[99] >= rest[0], "blocks are not contiguous");

            long[] classification = view.readInts(0, "Classification", 0, 10);
            assertEquals(10, classification.length);
        }
    }

    @Test
    void rejectsInvalidBlocksAndClosedViews(@TempDir Path tempDir) {
        Path input = generate(tempDir, 100);

        PdalView view =
                Pdal.open("""
                        { "pipeline": [ { "type": "readers.las", "filename": "%s" } ] }
                        """.formatted(jsonPath(input)));
        assertThrows(
                IllegalArgumentException.class, () -> view.readDoubles(0, "X", 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> view.readDoubles(0, "X", 0, PdalView.MAX_BLOCK_POINTS + 1));
        assertThrows(
                IllegalStateException.class, () -> view.readDoubles(0, "MissingDimension", 0, 10));
        assertThrows(IllegalStateException.class, () -> view.readDoubles(0, "X", 1000, 10));
        view.close();
        assertThrows(IllegalStateException.class, () -> view.readDoubles(0, "X", 0, 10));
        // Closing twice is a no-op.
        view.close();
    }

    @Test
    void readsMergedViews(@TempDir Path tempDir) {
        Path first = generate(tempDir, 500);
        Path second = tempDir.resolve("view-input-2.laz");
        Pdal.execute("""
                {
                  "pipeline": [
                    { "type": "readers.faux", "mode": "ramp",
                      "bounds": "([100,200],[0,100],[0,10])", "count": 500 },
                    { "type": "writers.las", "filename": "%s" }
                  ]
                }
                """.formatted(jsonPath(second)));

        try (PdalView view = Pdal.open("""
                { "pipeline": [
                  { "type": "readers.las", "filename": "%s" },
                  { "type": "readers.las", "filename": "%s" },
                  { "type": "filters.merge" } ] }
                """.formatted(jsonPath(first), jsonPath(second)))) {

            assertEquals(1000, view.pointCount());
            double[] x = view.readDoubles(0, "X", 0, 1000);
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (double value : x) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            assertTrue(min <= 0.5, "unexpected minimum: " + min);
            assertTrue(max >= 199.0, "unexpected maximum: " + max);
        }
    }
}
