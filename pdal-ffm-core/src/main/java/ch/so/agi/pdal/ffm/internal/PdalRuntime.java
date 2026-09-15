package ch.so.agi.pdal.ffm.internal;

import ch.so.agi.pdal.ffm.PdalException;
import ch.so.agi.pdal.ffm.PdalResult;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
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

    public String version() {
        return version;
    }

    public NativeBundleInfo bundleInfo() {
        return bundleInfo;
    }

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
