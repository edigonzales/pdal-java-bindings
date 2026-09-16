package ch.so.agi.pdal.ffm.internal;

import ch.so.agi.pdal.ffm.PdalPreview;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Native pipeline handle with point view access (block-wise dimension reads).
 *
 * <p>Public because the exported {@code PdalView} facade lives in a different package while the
 * module does not export the internal package. The handle owns the native pipeline and must be
 * closed.
 */
public final class PdalViewHandle implements AutoCloseable {
    private MemorySegment pipeline;

    PdalViewHandle(MemorySegment pipeline) {
        this.pipeline = pipeline;
    }

    public long pointCount() {
        return PdalNative.pointCount(requireOpen());
    }

    public int viewCount() {
        return PdalNative.viewCount(requireOpen());
    }

    public long viewPointCount(int view) {
        return PdalNative.viewPointCount(requireOpen(), view);
    }

    public List<PdalPreview.PdalDimension> dimensions(int view) {
        MemorySegment handle = requireOpen();
        int count = PdalNative.viewDimensionCount(handle, view);
        List<PdalPreview.PdalDimension> dimensions = new ArrayList<>(Math.max(count, 0));
        for (int index = 0; index < count; index++) {
            String name = PdalNative.viewDimensionName(handle, view, index);
            String type = PdalNative.viewDimensionType(handle, view, index);
            if (name == null || name.isEmpty()) {
                continue;
            }
            dimensions.add(new PdalPreview.PdalDimension(name, type == null ? "UNKNOWN" : type));
        }
        return List.copyOf(dimensions);
    }

    public double[] readDoubles(int view, String dimension, long start, int count) {
        MemorySegment handle = requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment target = arena.allocate(ValueLayout.JAVA_DOUBLE, count);
            MemorySegment name = arena.allocateFrom(dimension);
            int result = PdalNative.readDoubles(handle, view, name, start, count, target);
            if (result != 0) {
                throw new IllegalStateException(
                        "Cannot read dimension '" + dimension + "' as double (code " + result + ")");
            }
            double[] values = new double[count];
            for (int i = 0; i < count; i++) {
                values[i] = target.getAtIndex(ValueLayout.JAVA_DOUBLE, i);
            }
            return values;
        }
    }

    public long[] readInts(int view, String dimension, long start, int count) {
        MemorySegment handle = requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment target = arena.allocate(ValueLayout.JAVA_LONG, count);
            MemorySegment name = arena.allocateFrom(dimension);
            int result = PdalNative.readInts(handle, view, name, start, count, target);
            if (result != 0) {
                throw new IllegalStateException(
                        "Cannot read dimension '" + dimension + "' as integer (code " + result + ")");
            }
            long[] values = new long[count];
            for (int i = 0; i < count; i++) {
                values[i] = target.getAtIndex(ValueLayout.JAVA_LONG, i);
            }
            return values;
        }
    }

    private MemorySegment requireOpen() {
        MemorySegment handle = pipeline;
        if (handle == null) {
            throw new IllegalStateException("Point view is already closed");
        }
        return handle;
    }

    @Override
    public void close() {
        MemorySegment handle = pipeline;
        pipeline = null;
        if (handle != null) {
            PdalNative.destroyPipeline(handle);
        }
    }
}
