This directory contains the staged PDAL native runtime for this classifier.

The directory is populated by tools/natives/fetch-and-stage.sh:
- lib/ or bin/  native libraries, the libpdal_ffi shim and PDAL stage plugins
- share/gdal     GDAL data files (GDAL_DATA)
- share/proj     PROJ data files (PROJ_DATA/PROJ_LIB)
- ssl/           CA bundle used by the bundled libcurl (Unix only)

Only manifest.json and this file are checked into git. All native payload
files are ignored and are rebuilt from the pinned conda packages.
