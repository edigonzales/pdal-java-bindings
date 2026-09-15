package ch.so.agi.pdal.ffm.internal;

import ch.so.agi.pdal.ffm.Pdal;

final class NativeAccess {
    private NativeAccess() {
    }

    static void ensureEnabled() {
        Module module = Pdal.class.getModule();
        if (module.isNativeAccessEnabled()) {
            return;
        }

        String moduleToken = module.isNamed() ? module.getName() : "ALL-UNNAMED";
        throw new IllegalStateException(
                "Native access is not enabled. Start the JVM with --enable-native-access=" + moduleToken
        );
    }
}
