package ch.so.agi.pdal.ffm;

import ch.so.agi.pdal.ffm.internal.PdalViewHandle;

import java.util.List;

/**
 * Block-wise read access to the point views of an executed pipeline.
 *
 * <p>Created by {@link Pdal#open(String)}. The pipeline runs in standard mode, so all points of the
 * plan are held in native memory until the view is closed; the JVM heap is not used. Read blocks of
 * at most {@link #MAX_BLOCK_POINTS} points per call, for example:
 *
 * <pre>{@code
 * try (PdalView view = Pdal.open(pipelineJson)) {
 *     var dimensions = view.dimensions(0);
 *     long count = view.pointCount(0);
 *     for (long start = 0; start < count; start += 100_000) {
 *         int block = (int) Math.min(100_000, count - start);
 *         double[] x = view.readDoubles(0, "X", start, block);
 *         long[] classification = view.readInts(0, "Classification", start, block);
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * <p>Not thread-safe: a view belongs to one consumer and must be closed by it.
 */
public final class PdalView implements AutoCloseable {

    /** Maximum number of points per read call. */
    public static final int MAX_BLOCK_POINTS = 1_048_576;

    private final PdalViewHandle handle;

    PdalView(PdalViewHandle handle) {
        this.handle = handle;
    }

    /**
     * Total number of points of all views.
     *
     * @return point count
     */
    public long pointCount() {
        return handle.pointCount();
    }

    /**
     * Number of point views (usually one).
     *
     * @return number of views
     */
    public int viewCount() {
        return handle.viewCount();
    }

    /**
     * Number of points in the given view.
     *
     * @param view view index
     * @return point count of that view
     */
    public long pointCount(int view) {
        return handle.viewPointCount(view);
    }

    /**
     * Dimension layout of the given view.
     *
     * @param view view index
     * @return dimension names and types
     */
    public List<PdalPreview.PdalDimension> dimensions(int view) {
        return handle.dimensions(view);
    }

    /**
     * Reads a block of values as double.
     *
     * @param view view index
     * @param dimension dimension name (for example {@code X})
     * @param start index of the first point
     * @param count number of points, at most {@link #MAX_BLOCK_POINTS}
     * @return values, one per point
     */
    public double[] readDoubles(int view, String dimension, long start, int count) {
        validateBlock(start, count);
        return handle.readDoubles(view, dimension, start, count);
    }

    /**
     * Reads a block of values as long (all integer dimension types).
     *
     * @param view view index
     * @param dimension dimension name (for example {@code Classification})
     * @param start index of the first point
     * @param count number of points, at most {@link #MAX_BLOCK_POINTS}
     * @return values, one per point
     */
    public long[] readInts(int view, String dimension, long start, int count) {
        validateBlock(start, count);
        return handle.readInts(view, dimension, start, count);
    }

    @Override
    public void close() {
        handle.close();
    }

    private static void validateBlock(long start, int count) {
        if (start < 0) {
            throw new IllegalArgumentException("start must not be negative");
        }
        if (count < 1 || count > MAX_BLOCK_POINTS) {
            throw new IllegalArgumentException(
                    "count must be between 1 and " + MAX_BLOCK_POINTS + " but was " + count);
        }
    }
}
