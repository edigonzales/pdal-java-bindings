/*
 * Minimal C ABI shim around the PDAL C++ pipeline API.
 *
 * The Java side (java.lang.foreign / FFM) talks to this stable, tiny C surface
 * only. All C++ concerns (name mangling, exceptions, STL types) stay behind it.
 *
 * Threading: a pipeline handle must not be used from multiple threads at the
 * same time. Create one handle per concurrent execution.
 */
#ifndef PDAL_FFI_H
#define PDAL_FFI_H

#include <stdint.h>

#ifdef _WIN32
#  ifdef PDAL_FFI_BUILD
#    define PDAL_FFI_API __declspec(dllexport)
#  else
#    define PDAL_FFI_API __declspec(dllimport)
#  endif
#else
#  define PDAL_FFI_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* Version of the bundled PDAL runtime (e.g. "2.10.2"). */
PDAL_FFI_API const char* pdal_ffi_version(void);

/*
 * Sets a process environment variable (e.g. GDAL_DATA, PROJ_DATA,
 * PDAL_DRIVER_PATH) before PDAL initializes GDAL/PROJ. Returns 0 on success.
 */
PDAL_FFI_API int32_t pdal_ffi_set_environment(const char* name, const char* value);

/*
 * Creates a pipeline from a PDAL pipeline JSON document (the same document the
 * `pdal pipeline` CLI accepts). Returns a non-NULL handle even on parse errors;
 * inspect pdal_ffi_pipeline_error() afterwards.
 */
PDAL_FFI_API void* pdal_ffi_pipeline_create(const char* json);

/* Executes the pipeline. Returns 0 on success, non-zero on failure. */
PDAL_FFI_API int32_t pdal_ffi_pipeline_execute(void* pipeline);

/* Number of points produced by the last successful execution. */
PDAL_FFI_API uint64_t pdal_ffi_pipeline_point_count(void* pipeline);

/* Pipeline metadata as JSON. Empty string if no metadata is available. */
PDAL_FFI_API const char* pdal_ffi_pipeline_metadata(void* pipeline);

/* Captured PDAL log output of the last operation. Never NULL. */
PDAL_FFI_API const char* pdal_ffi_pipeline_log(void* pipeline);

/* Error message of the last failed operation. Empty string if no error. */
PDAL_FFI_API const char* pdal_ffi_pipeline_error(void* pipeline);

/* Releases the pipeline handle. NULL is a no-op. */
PDAL_FFI_API void pdal_ffi_pipeline_destroy(void* pipeline);

#ifdef __cplusplus
}
#endif

#endif /* PDAL_FFI_H */
