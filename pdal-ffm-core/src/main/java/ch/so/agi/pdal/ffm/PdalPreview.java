package ch.so.agi.pdal.ffm;

import java.util.List;

/**
 * Lightweight preview of a PDAL pipeline.
 *
 * <p>Computed with PDAL's preview mode (QuickInfo plus the dimension layout),
 * which reads point cloud headers and only a small number of points. It is the
 * cheap way to describe a point cloud without executing the pipeline.
 *
 * @param pointCount number of points reported by the reader ({@code 0} when unknown)
 * @param bounds axis aligned bounds or {@code null} when unknown
 * @param srsWkt CRS as well-known text, empty when unknown
 * @param srsAuthority CRS authority code such as {@code EPSG:2056}, empty when unknown
 * @param dimensions ordered dimension layout
 */
public record PdalPreview(
        long pointCount,
        Bounds bounds,
        String srsWkt,
        String srsAuthority,
        List<PdalDimension> dimensions) {

    /**
     * Axis aligned bounds in CRS units.
     *
     * @param minX minimum X
     * @param minY minimum Y
     * @param minZ minimum Z
     * @param maxX maximum X
     * @param maxY maximum Y
     * @param maxZ maximum Z
     */
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    /**
     * One previewed dimension.
     *
     * @param name dimension name such as {@code X} or {@code Intensity}
     * @param type one of {@code INT8}, {@code UINT8}, {@code INT16}, {@code UINT16},
     *     {@code INT32}, {@code UINT32}, {@code INT64}, {@code UINT64}, {@code FLOAT32},
     *     {@code FLOAT64}
     */
    public record PdalDimension(String name, String type) {
        /** Validates the dimension. */
        public PdalDimension {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("dimension name is required");
            }
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("dimension type is required");
            }
        }
    }

    /** Validates the values and normalizes {@code null} strings to empty strings. */
    public PdalPreview {
        if (pointCount < 0) {
            throw new IllegalArgumentException("pointCount must not be negative");
        }
        srsWkt = srsWkt == null ? "" : srsWkt;
        srsAuthority = srsAuthority == null ? "" : srsAuthority;
        dimensions = List.copyOf(dimensions);
    }
}
