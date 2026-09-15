package ch.so.agi.pdal.ffm.internal;

import java.util.Locale;

record NativePlatform(String os, String arch, String classifier) {
    static NativePlatform current() {
        return from(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static NativePlatform from(String osName, String osArch) {
        String normalizedOs = normalizeOs(osName);
        String normalizedArch = normalizeArch(osArch);
        return new NativePlatform(normalizedOs, normalizedArch, normalizedOs + "-" + normalizedArch);
    }

    private static String normalizeOs(String osName) {
        if (osName == null || osName.isBlank()) {
            throw new IllegalStateException("System property os.name is empty");
        }
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "osx";
        }
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        throw new IllegalStateException("Unsupported operating system for PDAL bundle: " + osName);
    }

    private static String normalizeArch(String osArch) {
        if (osArch == null || osArch.isBlank()) {
            throw new IllegalStateException("System property os.arch is empty");
        }
        String arch = osArch.toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "x86_64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "aarch64";
        }
        throw new IllegalStateException("Unsupported architecture for PDAL bundle: " + osArch);
    }
}
