package ch.so.agi.pdal.ffm.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Hand-written FFM downcalls for the minimal PDAL C ABI shim.
 *
 * <p>The ABI is deliberately tiny and versioned by this repository, so the
 * bindings are declared by hand instead of generating them with jextract.
 */
final class PdalNative {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup SYMBOL_LOOKUP =
            SymbolLookup.loaderLookup().or(LINKER.defaultLookup());

    static final MethodHandle VERSION = downcall(
            "pdal_ffi_version",
            FunctionDescriptor.of(ValueLayout.ADDRESS)
    );
    static final MethodHandle SET_ENVIRONMENT = downcall(
            "pdal_ffi_set_environment",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    static final MethodHandle PIPELINE_CREATE = downcall(
            "pdal_ffi_pipeline_create",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    static final MethodHandle PIPELINE_EXECUTE = downcall(
            "pdal_ffi_pipeline_execute",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    );
    static final MethodHandle PIPELINE_PREVIEW = downcall(
            "pdal_ffi_pipeline_preview",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    );
    static final MethodHandle PREVIEW_POINT_COUNT = downcall(
            "pdal_ffi_preview_point_count",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    );
    static final MethodHandle PREVIEW_BOUNDS = downcall(
            "pdal_ffi_preview_bounds",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    static final MethodHandle PREVIEW_SRS_WKT = downcall(
            "pdal_ffi_preview_srs_wkt",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    static final MethodHandle PREVIEW_SRS_AUTHORITY = downcall(
            "pdal_ffi_preview_srs_authority",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    static final MethodHandle PREVIEW_DIMENSION_COUNT = downcall(
            "pdal_ffi_preview_dimension_count",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    );
    static final MethodHandle PREVIEW_DIMENSION_NAME = downcall(
            "pdal_ffi_preview_dimension_name",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    );
    static final MethodHandle PREVIEW_DIMENSION_TYPE = downcall(
            "pdal_ffi_preview_dimension_type",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    );
    static final MethodHandle PIPELINE_POINT_COUNT = downcall(
            "pdal_ffi_pipeline_point_count",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    );
    static final MethodHandle PIPELINE_METADATA = downcall(
            "pdal_ffi_pipeline_metadata",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    static final MethodHandle PIPELINE_LOG = downcall(
            "pdal_ffi_pipeline_log",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    static final MethodHandle PIPELINE_ERROR = downcall(
            "pdal_ffi_pipeline_error",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    static final MethodHandle PIPELINE_DESTROY = downcall(
            "pdal_ffi_pipeline_destroy",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
    );

    private PdalNative() {
    }

    private static MethodHandle downcall(String symbolName, FunctionDescriptor descriptor) {
        MemorySegment address = SYMBOL_LOOKUP.find(symbolName)
                .orElseThrow(() -> new IllegalStateException("Required PDAL FFI symbol not found: " + symbolName));
        return LINKER.downcallHandle(address, descriptor);
    }

    static String readString(MethodHandle handle, MemorySegment pipeline) {
        MemorySegment segment = invokeAddress(handle, pipeline);
        return CStrings.fromCString(segment);
    }

    static String version() {
        return CStrings.fromCString(invokeAddress(VERSION));
    }

    static int setEnvironment(String name, String value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nameSegment = arena.allocateFrom(name);
            MemorySegment valueSegment = arena.allocateFrom(value);
            return invokeInt(SET_ENVIRONMENT, nameSegment, valueSegment);
        }
    }

    static MemorySegment createPipeline(MemorySegment json) {
        return invokeAddress(PIPELINE_CREATE, json);
    }

    static int execute(MemorySegment pipeline) {
        return invokeInt(PIPELINE_EXECUTE, pipeline);
    }

    static int preview(MemorySegment pipeline) {
        return invokeInt(PIPELINE_PREVIEW, pipeline);
    }

    static long previewPointCount(MemorySegment pipeline) {
        return invokeLong(PREVIEW_POINT_COUNT, pipeline);
    }

    static MemorySegment previewBounds(MemorySegment pipeline) {
        return invokeAddress(PREVIEW_BOUNDS, pipeline);
    }

    static String previewSrsWkt(MemorySegment pipeline) {
        return CStrings.fromCString(invokeAddress(PREVIEW_SRS_WKT, pipeline));
    }

    static String previewSrsAuthority(MemorySegment pipeline) {
        return CStrings.fromCString(invokeAddress(PREVIEW_SRS_AUTHORITY, pipeline));
    }

    static int previewDimensionCount(MemorySegment pipeline) {
        return invokeInt(PREVIEW_DIMENSION_COUNT, pipeline);
    }

    static String previewDimensionName(MemorySegment pipeline, int index) {
        return CStrings.fromCString(invokeAddress(PREVIEW_DIMENSION_NAME, pipeline, index));
    }

    static String previewDimensionType(MemorySegment pipeline, int index) {
        return CStrings.fromCString(invokeAddress(PREVIEW_DIMENSION_TYPE, pipeline, index));
    }

    static long pointCount(MemorySegment pipeline) {
        return invokeLong(PIPELINE_POINT_COUNT, pipeline);
    }

    static void destroyPipeline(MemorySegment pipeline) {
        invokeVoid(PIPELINE_DESTROY, pipeline);
    }

    static MemorySegment invokeAddress(MethodHandle handle, Object... args) {
        try {
            return (MemorySegment) handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("PDAL native invocation failed", e);
        }
    }

    private static int invokeInt(MethodHandle handle, Object... args) {
        try {
            return (int) handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("PDAL native invocation failed", e);
        }
    }

    private static long invokeLong(MethodHandle handle, Object... args) {
        try {
            return (long) handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("PDAL native invocation failed", e);
        }
    }

    private static void invokeVoid(MethodHandle handle, Object... args) {
        try {
            handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("PDAL native invocation failed", e);
        }
    }
}
