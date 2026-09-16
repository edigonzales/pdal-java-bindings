package ch.so.agi.pdal.ffm.internal;

import ch.so.agi.pdal.ffm.PdalException;
import ch.so.agi.pdal.ffm.PdalPreview;
import ch.so.agi.pdal.ffm.PdalResult;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Initializes the bundled native runtime exactly once and executes pipelines.
 *
 * <p>This class is public because the exported {@code Pdal} facade lives in a
 * different package while the module does not export the internal package.
 */
public final class PdalRuntime {
    private static final AtomicReference<PdalRuntime> INSTANCE = new AtomicReference<>();
    private static final Object LOCK = new Object();

    private final NativeBundleInfo bundleInfo;
    private final String version;

    private PdalRuntime(NativeBundleInfo bundleInfo, String version) {
        this.bundleInfo = bundleInfo;
        this.version = version;
    }

    /**
     * Returns the shared runtime, loading the native bundle on first use.
     *
     * @return initialized runtime
     */
    public static PdalRuntime instance() {
        PdalRuntime existing = INSTANCE.get();
        if (existing != null) {
            return existing;
        }

        synchronized (LOCK) {
            existing = INSTANCE.get();
            if (existing != null) {
                return existing;
            }

            NativeAccess.ensureEnabled();
            NativeBundleInfo bundleInfo = NativeLoader.load();
            applyRuntimeConfiguration(bundleInfo);

            String version = PdalNative.version();
            if (version == null || version.isBlank()) {
                version = bundleInfo.bundleVersion();
            }

            PdalRuntime runtime = new PdalRuntime(bundleInfo, version);
            INSTANCE.set(runtime);
            return runtime;
        }
    }

    /**
     * Version of the bundled runtime.
     *
     * @return PDAL version string
     */
    public String version() {
        return version;
    }

    /**
     * Executes a complete pipeline.
     *
     * @param pipelineJson PDAL pipeline document
     * @return execution result
     */
    public PdalResult execute(String pipelineJson) {
        if (pipelineJson == null || pipelineJson.isBlank()) {
            throw new IllegalArgumentException("pipelineJson must not be null or blank");
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment json = arena.allocateFrom(pipelineJson);
            MemorySegment pipeline = PdalNative.createPipeline(json);
            if (pipeline == null || pipeline.equals(MemorySegment.NULL)) {
                throw new PdalException("Failed to allocate a native pipeline handle");
            }

            try {
                int result = PdalNative.execute(pipeline);
                if (result != 0) {
                    throw new PdalException(requiredErrorMessage(pipeline));
                }

                long pointCount = PdalNative.pointCount(pipeline);
                String metadata = PdalNative.readString(PdalNative.PIPELINE_METADATA, pipeline);
                String log = PdalNative.readString(PdalNative.PIPELINE_LOG, pipeline);
                return new PdalResult(pointCount, metadata, log);
            } finally {
                PdalNative.destroyPipeline(pipeline);
            }
        }
    }

    /**
     * Executes a pipeline in standard mode and keeps the resulting point views
     * for block-wise access.
     *
     * @param pipelineJson PDAL pipeline document
     * @return view handle; close it to release the native resources
     */
    public PdalViewHandle open(String pipelineJson) {
        if (pipelineJson == null || pipelineJson.isBlank()) {
            throw new IllegalArgumentException("pipelineJson must not be null or blank");
        }
        MemorySegment pipeline;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment json = arena.allocateFrom(pipelineJson);
            pipeline = PdalNative.createPipeline(json);
        }
        if (pipeline == null || pipeline.equals(MemorySegment.NULL)) {
            throw new PdalException("Failed to allocate a native pipeline handle");
        }
        if (PdalNative.executeView(pipeline) != 0) {
            String message = requiredErrorMessage(pipeline);
            PdalNative.destroyPipeline(pipeline);
            throw new PdalException(message);
        }
        return new PdalViewHandle(pipeline);
    }

    /**
     * Computes a lightweight preview of a pipeline.
     *
     * @param pipelineJson PDAL pipeline document
     * @return preview information
     */
    public PdalPreview preview(String pipelineJson) {
        if (pipelineJson == null || pipelineJson.isBlank()) {
            throw new IllegalArgumentException("pipelineJson must not be null or blank");
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment json = arena.allocateFrom(pipelineJson);
            MemorySegment pipeline = PdalNative.createPipeline(json);
            if (pipeline == null || pipeline.equals(MemorySegment.NULL)) {
                throw new PdalException("Failed to allocate a native pipeline handle");
            }

            try {
                if (PdalNative.preview(pipeline) != 0) {
                    throw new PdalException(requiredErrorMessage(pipeline));
                }

                long pointCount = PdalNative.previewPointCount(pipeline);
                PdalPreview.Bounds bounds = readBounds(PdalNative.previewBounds(pipeline));
                String srsWkt = PdalNative.previewSrsWkt(pipeline);
                String srsAuthority = PdalNative.previewSrsAuthority(pipeline);
                int dimensionCount = PdalNative.previewDimensionCount(pipeline);
                List<PdalPreview.PdalDimension> dimensions = new ArrayList<>(Math.max(dimensionCount, 0));
                for (int i = 0; i < dimensionCount; i++) {
                    dimensions.add(new PdalPreview.PdalDimension(
                            PdalNative.previewDimensionName(pipeline, i),
                            PdalNative.previewDimensionType(pipeline, i)
                    ));
                }
                return new PdalPreview(pointCount, bounds, srsWkt, srsAuthority, dimensions);
            } finally {
                PdalNative.destroyPipeline(pipeline);
            }
        }
    }

    private static PdalPreview.Bounds readBounds(MemorySegment address) {
        if (CStrings.isNull(address)) {
            return null;
        }
        MemorySegment bounds = address.reinterpret(6L * ValueLayout.JAVA_DOUBLE.byteSize());
        return new PdalPreview.Bounds(
                bounds.get(ValueLayout.JAVA_DOUBLE, 0),
                bounds.get(ValueLayout.JAVA_DOUBLE, 8),
                bounds.get(ValueLayout.JAVA_DOUBLE, 16),
                bounds.get(ValueLayout.JAVA_DOUBLE, 24),
                bounds.get(ValueLayout.JAVA_DOUBLE, 32),
                bounds.get(ValueLayout.JAVA_DOUBLE, 40)
        );
    }

    private static String requiredErrorMessage(MemorySegment pipeline) {
        String message = PdalNative.readString(PdalNative.PIPELINE_ERROR, pipeline);
        if (message == null || message.isBlank()) {
            return "unknown native error";
        }
        return message;
    }

    private static void applyRuntimeConfiguration(NativeBundleInfo bundleInfo) {
        for (Map.Entry<String, Path> option : NativeBundleRuntimeConfig.globalConfigOptions(bundleInfo).entrySet()) {
            if (PdalNative.setEnvironment(option.getKey(), option.getValue().toString()) != 0) {
                throw new IllegalStateException(
                        "Failed to export bundled runtime option " + option.getKey()
                                + "=" + option.getValue()
                );
            }
        }

        // The bundled CA bundle is only used when the user did not configure one.
        for (Map.Entry<String, String> option :
                NativeBundleRuntimeConfig.scopedConfigOptions(bundleInfo).entrySet()) {
            PdalNative.setEnvironment(option.getKey(), option.getValue());
        }
    }
}
