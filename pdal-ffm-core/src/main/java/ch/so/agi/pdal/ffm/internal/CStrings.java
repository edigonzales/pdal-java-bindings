package ch.so.agi.pdal.ffm.internal;

import java.lang.foreign.MemorySegment;

final class CStrings {
    private static final long MAX_C_STRING_BYTES = 8L * 1024L * 1024L;

    private CStrings() {
    }

    static String fromCString(MemorySegment cString) {
        if (isNull(cString)) {
            return "";
        }
        return cString.reinterpret(MAX_C_STRING_BYTES).getString(0);
    }

    static boolean isNull(MemorySegment segment) {
        return segment == null || segment.equals(MemorySegment.NULL) || segment.address() == 0L;
    }
}
