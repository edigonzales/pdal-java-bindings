package ch.so.agi.pdal.ffm.internal;

import ch.so.agi.pdal.ffm.Pdal;
import ch.so.agi.pdal.ffm.PdalException;
import ch.so.agi.pdal.ffm.PdalResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Smoke test against a packaged native classifier JAR (exactly one classpath
 * native bundle). Verifies extraction into the private tmp cache and that the
 * bundled runtime works without any system PDAL installation.
 */
public final class PdalPackagedNativeSmoke {
    private PdalPackagedNativeSmoke() {
    }

    public static void main(String[] args) throws IOException {
        Path outputDir = Path.of(args.length > 0 ? args[0] : "build/smoke-test-output").toAbsolutePath();
        Files.createDirectories(outputDir);

        Path input = outputDir.resolve("packaged-input.laz");
        Path output = outputDir.resolve("packaged-crop.laz");
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
                      "count": 4000
                    },
                    { "type": "writers.las", "filename": "%s" }
                  ]
                }
                """.formatted(jsonPath(input));

        PdalResult generated = Pdal.execute(generatePipeline);
        require(generated.pointCount() == 4000, "expected 4000 generated points");

        String cropPipeline = """
                {
                  "pipeline": [
                    "%s",
                    { "type": "filters.crop", "bounds": "([30,70],[30,70],[0,10])" },
                    { "type": "writers.las", "filename": "%s" }
                  ]
                }
                """.formatted(jsonPath(input), jsonPath(output));

        PdalResult cropped = Pdal.execute(cropPipeline);
        require(cropped.pointCount() > 0, "crop produced no points");
        require(Files.isRegularFile(output), "cropped LAZ file is missing");

        assertExtractedBundle();
        assertBundledCaBundleWhenExpected();
        assertErrorHandling();
        assertPreview(input);

        System.out.println("OK packaged native smoke: " + cropped.pointCount() + " points");
    }

    private static void assertPreview(Path input) {
        var preview =
                Pdal.preview("""
                        { "pipeline": [ { "type": "readers.las", "filename": "%s" } ] }
                        """.formatted(jsonPath(input)));
        require(preview.pointCount() == 4000, "preview reports " + preview.pointCount() + " points");
        require(preview.bounds() != null, "preview bounds are missing");
        require(
                preview.dimensions().stream().anyMatch(d -> d.name().equals("X")),
                "preview dimensions are missing");
    }

    private static void assertExtractedBundle() throws IOException {
        Path cacheRoot = Path.of(System.getProperty("java.io.tmpdir"), "pdal-ffm");
        require(Files.isDirectory(cacheRoot), "extraction cache not found: " + cacheRoot);
        try (Stream<Path> files = Files.walk(cacheRoot)) {
            boolean marker = files.anyMatch(path -> path.getFileName().toString().equals(".extract-complete"));
            require(marker, "extraction marker not found below " + cacheRoot);
        }
    }

    private static void assertBundledCaBundleWhenExpected() throws IOException {
        if (!Boolean.getBoolean("pdal.ffm.smoke.expectBundledCaBundle")) {
            return;
        }
        Path cacheRoot = Path.of(System.getProperty("java.io.tmpdir"), "pdal-ffm");
        try (Stream<Path> files = Files.walk(cacheRoot)) {
            boolean caBundle = files.anyMatch(path -> path.toString().endsWith(Path.of("ssl", "cacert.pem").toString()));
            require(caBundle, "bundled ssl/cacert.pem not extracted below " + cacheRoot);
        }
    }

    private static void assertErrorHandling() {
        try {
            Pdal.execute("""
                    { "pipeline": [ { "type": "readers.does-not-exist" } ] }
                    """);
            throw new IllegalStateException("Smoke test failed: invalid stage did not raise PdalException");
        } catch (PdalException expected) {
            require(!expected.nativeMessage().isBlank(), "error message is empty");
        }
    }

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Packaged native smoke failed: " + message);
        }
    }
}
