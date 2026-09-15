# Native runtime

This document describes how the bundled PDAL runtime is produced, how it is
loaded at runtime and how it can be configured.

## Bundle layout

```
META-INF/pdal-native/<classifier>/
  manifest.json
  lib/            libpdalcpp.*, libpdal_ffi.*, libproj.*, libgdal.*, ...
                  libpdal_plugin_*.so|dylib (optional stage plugins)
  share/gdal/     GDAL data files        -> GDAL_DATA
  share/proj/     PROJ data files        -> PROJ_DATA / PROJ_LIB
  ssl/cacert.pem  CA bundle for libcurl  -> CURL_CA_BUNDLE / SSL_CERT_FILE (Unix)
```

On Windows the payload lives in `bin/` (DLLs) and `share/` (data).

### manifest.json

| Field | Meaning |
| --- | --- |
| `bundleVersion` | PDAL version of the bundle |
| `ffiLibrary` | the `libpdal_ffi` shim loaded by the Java runtime |
| `entryLibrary` | `libpdalcpp` loaded by the Java runtime |
| `preloadLibraries` | libraries loaded before the entry library |
| `gdalDataPath` | directory exported as `GDAL_DATA` |
| `projDataPath` | directory exported as `PROJ_DATA`/`PROJ_LIB` |
| `pluginPath` | directory exported as `PDAL_DRIVER_PATH` |
| `caBundlePath` | CA bundle exported as `CURL_CA_BUNDLE`/`SSL_CERT_FILE` (optional) |
| `cacheKey` | SHA-256 over manifest + payload; invalidates the extraction cache |

The checked-in `manifest.json` has no `cacheKey`; the Gradle build adds it when
packaging the classifier JAR.

## Build pipeline

1. `tools/natives/refresh-lock-closure.sh` resolves the transitive conda
   dependency closure of `libpdal-core` per classifier and pins URLs/SHA-256 in
   `tools/natives/binaries.lock`. Removed upstream artifacts are skipped
   automatically.
2. `tools/natives/fetch-and-stage.sh <classifier>`
   downloads and verifies every pinned package, copies `lib`/`bin`/`share`
   payloads into the staging directory, builds the C ABI shim with
   `tools/ffi/build-ffi.{sh,ps1}`, prunes non-runtime files and writes the
   manifest.
3. `tools/natives/relocate-runtime-deps.sh <classifier>` rewrites Linux
   `DT_NEEDED` entries (patchelf or lief), normalizes macOS install names to
   `@rpath` and ad-hoc signs the binaries, and validates Windows DLLs.
4. `tools/natives/audit-runtime-deps.sh <classifier>` verifies that every
   dependency of every bundled library is either bundled itself or an allowed
   system library.

The Gradle module `pdal-ffm-natives` packages one JAR per classifier
(`pdal-ffm-natives-<version>-natives-<classifier>.jar`).

## Runtime loading

`NativeLoader` locates exactly one `META-INF/pdal-native/<classifier>/manifest.json`
on the class path, extracts the bundle into
`${java.io.tmpdir}/pdal-ffm/<cacheKey>/<classifier>` (marker file
`.extract-complete`) and loads the libraries with `System.load`. On Windows the
`bin` directory is registered as a DLL search directory through kernel32.

`NativeBundleRuntimeConfig` exports the bundled data directories through the
native shim before the first pipeline runs:

| Variable | Value | Override |
| --- | --- | --- |
| `GDAL_DATA` | `share/gdal` | user environment wins |
| `PROJ_DATA` / `PROJ_LIB` | `share/proj` | user environment wins |
| `PDAL_DRIVER_PATH` | `pluginPath` | user environment wins |
| `CURL_CA_BUNDLE` / `SSL_CERT_FILE` | `ssl/cacert.pem` | only set when the user did not define one |

The versions of the bundled runtime are checked by `Pdal.version()` and
covered by the smoke tests.

On Windows the C ABI shim is compiled with the MSVC toolset (same major
version as the conda packages) and links dynamically against the Microsoft
Visual C++ runtime (`vcruntime140.dll`, `msvcp140.dll`). These are not bundled
and must be available on the target machine (usually installed with the
Microsoft Visual C++ Redistributable).

## Troubleshooting

- **`No bundled PDAL native resources found for classifier ...`**: add exactly
  one natives artifact for the host platform.
- **`Native access is not enabled`**: start the JVM with
  `--enable-native-access=ch.so.agi.pdal.ffm` (or `ALL-UNNAMED`).
- **Stale extraction after re-staging**: delete
  `${java.io.tmpdir}/pdal-ffm/<old-cacheKey>` or point
  `-Djava.io.tmpdir` to a fresh directory. The `cacheKey` normally prevents
  this.
- **`Couldn't create stage of type ...`**: the stage is provided by an optional
  PDAL plugin that is not part of the standard bundle.
