package ch.so.agi.pdal.ffm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Smoke test for the bundled PDAL runtime:
 * synthetic points (readers.faux) -&gt; LAZ -&gt; crop -&gt; LAZ.
 *
 * <p>Runs without any system PDAL installation. Exits with a non-zero status
 * code on failure.
 */
public final class PdalSmoke {
    private static final int FAUX_POINT_COUNT = 5000;

    private PdalSmoke() {
    }

    public static void main(String[] args) throws IOException {
        Path outputDir = Path.of(args.length > 0 ? args[0] : "build/smoke-test-output").toAbsolutePath();
        Files.createDirectories(outputDir);

        Path input = outputDir.resolve("smoke-input.laz");
        Path output = outputDir.resolve("smoke-crop.laz");
        Files.deleteIfExists(input);
        Files.deleteIfExists(output);

        System.out.println("PDAL version: " + Pdal.version());

        String generatePipeline = """
                {
                  "pipeline": [
                    {
                      "type": "readers.faux",
                      "mode": "ramp",
                      "bounds": "([0,100],[0,100],[0,10])",
                      "count": %d
                    },
                    { "type": "writers.las", "filename": "%s" }
                  ]
                }
                """.formatted(FAUX_POINT_COUNT, jsonPath(input));

        PdalResult generated = Pdal.execute(generatePipeline);
        require(generated.pointCount() == FAUX_POINT_COUNT,
                "expected " + FAUX_POINT_COUNT + " generated points but got " + generated.pointCount());
        require(Files.isRegularFile(input), "generated LAZ file is missing: " + input);

        String cropPipeline = """
                {
                  "pipeline": [
                    "%s",
                    {
                      "type": "filters.crop",
                      "bounds": "([20,80],[20,80],[0,10])"
                    },
                    { "type": "writers.las", "filename": "%s", "minor_version": 4 }
                  ]
                }
                """.formatted(jsonPath(input), jsonPath(output));

        PdalResult cropped = Pdal.execute(cropPipeline);
        require(cropped.pointCount() > 0, "crop produced no points");
        require(cropped.pointCount() < FAUX_POINT_COUNT, "crop did not remove any points");
        require(Files.isRegularFile(output), "cropped LAZ file is missing: " + output);
        require(!cropped.metadataJson().isBlank(), "metadata JSON is empty");
        require(cropped.metadataJson().startsWith("{"), "metadata is not a JSON object");

        System.out.println("OK " + cropped.pointCount() + " points -> " + output);
    }

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Smoke test failed: " + message);
        }
    }
}
