package ch.so.agi.pdal.ffm.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativePlatformTest {

    @Test
    void mapsMacOsArm64() {
        NativePlatform platform = NativePlatform.from("Mac OS X", "aarch64");
        assertEquals("osx", platform.os());
        assertEquals("aarch64", platform.arch());
        assertEquals("osx-aarch64", platform.classifier());
    }

    @Test
    void mapsDarwinX86_64() {
        assertEquals("osx-x86_64", NativePlatform.from("Darwin", "x86_64").classifier());
    }

    @Test
    void mapsLinuxAmd64() {
        assertEquals("linux-x86_64", NativePlatform.from("Linux", "amd64").classifier());
    }

    @Test
    void mapsLinuxArm64() {
        assertEquals("linux-aarch64", NativePlatform.from("Linux", "arm64").classifier());
    }

    @Test
    void mapsWindowsAmd64() {
        assertEquals("windows-x86_64", NativePlatform.from("Windows 11", "amd64").classifier());
    }

    @Test
    void rejectsUnsupportedOs() {
        assertThrows(IllegalStateException.class, () -> NativePlatform.from("FreeBSD", "x86_64"));
    }

    @Test
    void rejectsUnsupportedArchitecture() {
        assertThrows(IllegalStateException.class, () -> NativePlatform.from("Linux", "riscv64"));
    }
}
