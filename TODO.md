# TODO

## Done

- [x] `pdal-ffm-core` + `pdal-ffm-natives` with Gradle (Groovy DSL), Java 25
- [x] minimal C ABI shim (`native/pdal-ffi`) compiled per platform
- [x] conda-based lock/resolver/staging/relocation/audit toolchain
- [x] V0.1 API: `Pdal.version()`, `Pdal.execute(json)`, `PdalResult`, `PdalException`
- [x] unit tests, integration tests and packaged-native smoke tests
- [x] CI workflows (ci, natives, release)

## Open

- [ ] V0.2: point view access (`preview`, block-wise dimension reads)
- [ ] optional stage discovery (`availableDrivers()`) for UI building
- [ ] optional PROJ grid data (`proj-data`, ~520 MB per classifier) as separate
      variant, analogous to the Swiss subset in `gdal-java-bindings`; V0.1 only
      bundles `proj.db` so that CRS transformations work without grid shifts
- [ ] publish to jars.interlis.guru (snapshots/releases) and verify published artifacts
- [ ] `hop-pointcloud-type-plugin` (neutral point cloud value model, own repo)
- [ ] `hop-pdal-plugin` (Hop transforms, PdalPlan fusion, own repo)
- [ ] keep the bigger picture: **geo-native-runtime** – one shared
      GDAL/PROJ base for all Apache Hop geo plugins. PDAL 2.10.2 needs
      GDAL 3.13.x while `gdal-java-bindings` still pins 3.12.2, so GDAL is
      currently bundled twice. Align both projects before shipping both in one
      Hop distribution.
