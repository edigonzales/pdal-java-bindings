# pdal-java-bindings

Java bindings for [PDAL](https://pdal.io) based on the **Java FFM API**
(`java.lang.foreign`) and a tiny hand-written C ABI shim. Native PDAL is
bundled per platform, so **no system PDAL installation is required** and no
JNI/`pdal-native` artifacts are used.

This repository is the foundation for point cloud support in Apache Hop
(`hop-pointcloud-type-plugin` and `hop-pdal-plugin`). It must work stand-alone
first.

## Modules

| Module | Description |
| --- | --- |
| `pdal-ffm-core` | Public API (`Pdal`, `PdalResult`, `PdalException`), FFM downcalls, native loader and runtime configuration |
| `pdal-ffm-natives` | Per-classifier native bundle JARs (PDAL + GDAL/PROJ runtime, C ABI shim, runtime data) |
| `native/pdal-ffi` | The C ABI shim (`pdal_ffi.h`/`pdal_ffi.cpp`, ~200 lines) compiled per platform |
| `tools/natives` | Conda-based staging, relocation and audit of the native runtime |
| `tools/ffi` | FFI shim build scripts (clang/g++/MSVC) |

## Requirements

- JDK 25
- `--enable-native-access=ch.so.agi.pdal.ffm` (or `ALL-UNNAMED` when used from
  the class path) at runtime
- For staging natives: conda (or `cph`/`conda-package-handling`), `curl` and a
  C++17 toolchain matching the host platform

## Usage

```kotlin
dependencies {
    implementation("ch.so.agi:pdal-ffm-core:<version>")
    runtimeOnly("ch.so.agi:pdal-ffm-natives:<version>:natives-osx-aarch64")
}
```

```java
PdalResult result = Pdal.execute("""
    {
      "pipeline": [
        "/data/input.laz",
        { "type": "filters.crop", "bounds": "([2600000,2601000],[1200000,1201000])" },
        "/data/output.laz"
      ]
    }
    """);

long pointCount = result.pointCount();
String metadataJson = result.metadataJson();
String log = result.log();
```

`Pdal.execute` accepts the same pipeline JSON as the `pdal pipeline` CLI and
runs the whole pipeline inside the JVM process. `Pdal.version()` returns the
version of the bundled PDAL runtime.

`Pdal.preview` computes a lightweight description (point count, bounds, CRS and
dimension layout) from the reader headers without reading the point cloud:

```java
PdalPreview preview = Pdal.preview("""
    { "pipeline": [ { "type": "readers.las", "filename": "/data/input.laz" } ] }
    """);

long points = preview.pointCount();
PdalPreview.Bounds bounds = preview.bounds();
String crs = preview.srsAuthority();          // e.g. "EPSG:2056"
List<PdalPreview.PdalDimension> dimensions = preview.dimensions();
```

Exactly one natives artifact for the current platform must be on the run-time
class path. The loader extracts it lazily into
`${java.io.tmpdir}/pdal-ffm/<cacheKey>/<classifier>` and loads
`libpdalcpp` plus the `libpdal_ffi` shim from there.

## Bundled runtime

The native bundles are pinned in `tools/natives/binaries.lock` and resolved
from conda-forge:

- PDAL **2.10.2** (`libpdal-core`, ABI `libpdalcpp.20`)
- GDAL **3.13.x** (`libgdal-core`), PROJ **9.8.x**
- core stages only: LAS/LAZ, COPC, EPT, OGR/GeoPackage, GDAL raster output,
  HTTP(S) readers and the core filter set (`crop`, `smrf`, `hag_*`,
  `reprojection`, `voxel*`, `outlier`, `decimation`, `range`, `expression`, ...)

Optional PDAL plugins (E57, TileDB, Oracle, ...) are intentionally **not**
bundled. PDAL stages that are not part of the bundle report
`Couldn't create stage of type ...` at runtime.

## Building

```sh
./gradlew build                      # unit tests, no natives required
./gradlew :pdal-ffm-natives:assemble # requires staged natives
```

### Staging natives (development)

```sh
conda create -n pdal -c conda-forge libpdal-core=2.10.2 conda-package-handling
conda activate pdal

tools/natives/fetch-and-stage.sh osx-aarch64     # stage + build the C ABI shim
tools/natives/audit-runtime-deps.sh osx-aarch64  # verify the dependency closure
./gradlew :pdal-ffm-natives:assemble
```

`fetch-and-stage.sh` can only build the shim for the host platform (no cross
compilation). Use `PDAL_FFM_ALLOW_FOREIGN_STAGE=true` to stage a foreign
classifier without the shim (inspection only).

Rebuild the lock closure after changing root packages:

```sh
tools/natives/refresh-lock-closure.sh          # rewrite extra_* entries
tools/natives/refresh-lock-closure.sh --check  # drift check (CI)
```

### Tests

```sh
./gradlew test                # unit tests (no natives)
PDAL_FFM_RUN_INTEGRATION=true ./gradlew integrationTest   # staged host natives
./gradlew smokeTest           # faux -> LAZ -> crop -> LAZ smoke test
./gradlew :pdal-ffm-core:smokeTestPackagedNative \
    -PpdalFfmSmokeNativeJar=<path-to-native-jar>
```

## Roadmap

- **V0.2**: point view access (block-wise dimension reads via
  `pdal_ffi_view_*`/`read_dimension`); the descriptor path (`Pdal.preview`) is
  already available
- **Hop integration**: `hop-pointcloud-type-plugin` (neutral value model) and
  `hop-pdal-plugin` (transforms fused into a single `PdalPlan` pipeline)
- **geo-native-runtime**: long-term goal is one shared native GDAL/PROJ base
  for all Apache Hop geo plugins so that GDAL is not bundled twice
  (`gdal-ffm-natives` currently pins GDAL 3.12.x while PDAL needs 3.13.x)

## Notes

- `tools/jextract` contains a JDK 25 jextract distribution. The current
  bindings are hand-written because the C ABI is tiny; jextract can be used
  again when the binding surface grows.
- Bundling follows the same model as `gdal-java-bindings`: pinned conda
  packages, classifier JARs, runtime relocation (patchelf/install_name_tool,
  ad-hoc codesign) and a dependency audit per platform.
- Third-party licenses of the bundled binaries: see `THIRD_PARTY_NOTICES.md`.
