# Third-Party Notices

The native bundles published by this repository contain binaries from
conda-forge packages that are pinned (URL + SHA-256) in
`tools/natives/binaries.lock`. The main components are:

| Component | License | Source |
| --- | --- | --- |
| PDAL | BSD-3-Clause | https://github.com/PDAL/PDAL |
| GDAL | MIT/X | https://github.com/OSGeo/gdal |
| PROJ (+ proj-data) | MIT/X | https://github.com/OSGeo/PROJ |
| GeoTIFF, libtiff, libcurl, openssl, zlib, zstd, sqlite, ... | see upstream | https://conda-forge.org |

The C ABI shim `native/pdal-ffi` is original code of this repository and links
dynamically against the bundled PDAL library.

Before shipping binaries to third parties, review the license texts of the
packages in the lock file for the targeted classifiers (for example LGPL
components such as LASzip or MPL-licensed libraries) and include the required
notices.
