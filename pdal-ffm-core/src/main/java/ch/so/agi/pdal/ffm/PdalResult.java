package ch.so.agi.pdal.ffm;

/**
 * Result of a successful {@link Pdal#execute(String)} call.
 *
 * <p>A result is a plain immutable value: it holds the number of points that
 * were produced, the PDAL metadata JSON and the captured PDAL log output.
 * It never references native resources, so it stays valid after the pipeline
 * has been closed.
 *
 * @param pointCount number of points produced by the pipeline
 * @param metadataJson PDAL metadata of the executed pipeline as JSON
 * @param log captured PDAL log output
 */
public record PdalResult(long pointCount, String metadataJson, String log) {

    public PdalResult {
        if (pointCount < 0) {
            throw new IllegalArgumentException("pointCount must not be negative");
        }
        metadataJson = metadataJson == null ? "" : metadataJson;
        log = log == null ? "" : log;
    }
}
