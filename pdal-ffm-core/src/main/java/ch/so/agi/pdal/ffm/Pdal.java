package ch.so.agi.pdal.ffm;

import ch.so.agi.pdal.ffm.internal.PdalRuntime;

/**
 * Executes PDAL pipelines with a bundled, stand-alone PDAL runtime.
 *
 * <p>The bundled runtime is resolved through the platform-native resource
 * dependency (for example {@code ch.so.agi:pdal-ffm-natives:<VERSION>:natives-osx-aarch64}).
 * PDAL does not have to be installed on the machine, and all bundled native
 * libraries are extracted into a private cache below {@code java.io.tmpdir} at
 * first use.
 *
 * <p>Requires {@code --enable-native-access} for the module (or
 * {@code ALL-UNNAMED} when running on the class path).
 *
 * <pre>{@code
 * PdalResult result = Pdal.execute("""
 *     {
 *       "pipeline": [
 *         "/data/input.laz",
 *         { "type": "filters.crop", "bounds": "([2600000,2601000],[1200000,1201000])" },
 *         "/tmp/output.laz"
 *       ]
 *     }
 *     """);
 * long points = result.pointCount();
 * }</pre>
 */
public final class Pdal {
    private Pdal() {
    }

    /**
     * Version of the bundled PDAL runtime (e.g. {@code 2.10.2}).
     *
     * @return PDAL version string
     */
    public static String version() {
        return PdalRuntime.instance().version();
    }

    /**
     * Executes a PDAL pipeline described as JSON (the same document the
     * {@code pdal pipeline} CLI accepts).
     *
     * @param pipelineJson PDAL pipeline JSON
     * @return result with point count, metadata JSON and log output
     * @throws PdalException if the pipeline cannot be read or executed
     */
    public static PdalResult execute(String pipelineJson) {
        return PdalRuntime.instance().execute(pipelineJson);
    }

    /**
     * Computes a lightweight preview of a PDAL pipeline (point count, bounds,
     * CRS and dimension layout) without executing it.
     *
     * @param pipelineJson PDAL pipeline JSON
     * @return preview information
     * @throws PdalException if the pipeline cannot be read or previewed
     */
    public static PdalPreview preview(String pipelineJson) {
        return PdalRuntime.instance().preview(pipelineJson);
    }
}
