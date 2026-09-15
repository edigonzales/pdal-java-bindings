package ch.so.agi.pdal.ffm.internal;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

final class WindowsNativeLibraryPathSupport {
    private static final int LOAD_LIBRARY_SEARCH_DEFAULT_DIRS = 0x00001000;
    private static final int LOAD_LIBRARY_SEARCH_USER_DIRS = 0x00000400;
    private static final RegistrationState GLOBAL_STATE = new RegistrationState();

    private WindowsNativeLibraryPathSupport() {
    }

    static void configureIfNeeded(NativePlatform platform, Path extractionRoot) {
        Objects.requireNonNull(platform, "platform must not be null");
        if (!"windows".equals(platform.os())) {
            return;
        }
        configureIfNeeded(platform, extractionRoot, GLOBAL_STATE, Kernel32Holder.INSTANCE);
    }

    static void configureIfNeeded(
            NativePlatform platform,
            Path extractionRoot,
            RegistrationState state,
            Kernel32Access kernel32
    ) {
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(extractionRoot, "extractionRoot must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(kernel32, "kernel32 must not be null");

        if (!"windows".equals(platform.os())) {
            return;
        }

        Path dllDirectory = resolveDllDirectory(extractionRoot);
        synchronized (state.lock()) {
            if (state.registeredPaths().contains(dllDirectory)) {
                return;
            }

            String preferredFailure = null;
            if (kernel32.supportsAddDllDirectory()) {
                preferredFailure = tryPreferredRegistration(kernel32, dllDirectory);
                if (preferredFailure == null) {
                    state.registeredPaths().add(dllDirectory);
                    return;
                }
            }

            String fallbackFailure = tryFallbackRegistration(kernel32, dllDirectory);
            if (fallbackFailure == null) {
                state.registeredPaths().add(dllDirectory);
                return;
            }

            throw registrationFailure(platform.classifier(), dllDirectory, preferredFailure, fallbackFailure);
        }
    }

    private static Path resolveDllDirectory(Path extractionRoot) {
        Path dllDirectory = extractionRoot.resolve("bin").toAbsolutePath().normalize();
        if (!Files.isDirectory(dllDirectory)) {
            throw new IllegalStateException("Windows native DLL directory is missing: " + dllDirectory);
        }
        return dllDirectory;
    }

    private static String tryPreferredRegistration(Kernel32Access kernel32, Path dllDirectory) {
        if (!kernel32.setDefaultDllDirectories(LOAD_LIBRARY_SEARCH_DEFAULT_DIRS | LOAD_LIBRARY_SEARCH_USER_DIRS)) {
            return "SetDefaultDllDirectories failed (GetLastError=" + kernel32.getLastError() + ")";
        }
        if (!kernel32.addDllDirectory(dllDirectory)) {
            return "AddDllDirectory failed for '" + dllDirectory + "' (GetLastError=" + kernel32.getLastError() + ")";
        }
        return null;
    }

    private static String tryFallbackRegistration(Kernel32Access kernel32, Path dllDirectory) {
        if (!kernel32.setDllDirectory(dllDirectory)) {
            return "SetDllDirectoryW failed for '" + dllDirectory + "' (GetLastError=" + kernel32.getLastError() + ")";
        }
        return null;
    }

    private static IllegalStateException registrationFailure(
            String classifier,
            Path dllDirectory,
            String preferredFailure,
            String fallbackFailure
    ) {
        StringBuilder message = new StringBuilder()
                .append("Failed to register Windows DLL search path '")
                .append(dllDirectory)
                .append("' for classifier '")
                .append(classifier)
                .append("'");
        if (preferredFailure != null) {
            message.append(": ").append(preferredFailure);
            if (fallbackFailure != null) {
                message.append("; fallback ").append(fallbackFailure);
            }
        } else if (fallbackFailure != null) {
            message.append(": ").append(fallbackFailure);
        }
        return new IllegalStateException(message.toString());
    }

    record RegistrationState(Object lock, Set<Path> registeredPaths) {
        RegistrationState() {
            this(new Object(), new HashSet<>());
        }
    }

    interface Kernel32Access {
        boolean supportsAddDllDirectory();

        boolean setDefaultDllDirectories(int flags);

        boolean addDllDirectory(Path dllDirectory);

        boolean setDllDirectory(Path dllDirectory);

        int getLastError();
    }

    private static final class Kernel32Holder {
        private static final Kernel32Access INSTANCE = new FfmKernel32Access();

        private Kernel32Holder() {
        }
    }

    private static final class FfmKernel32Access implements Kernel32Access {
        private static final Arena LOOKUP_ARENA = Arena.ofShared();
        private static final Linker LINKER = Linker.nativeLinker();

        private final SymbolLookup symbolLookup = SymbolLookup.libraryLookup("kernel32", LOOKUP_ARENA);
        private final MethodHandle setDefaultDllDirectories = downcallOptional(
                "SetDefaultDllDirectories",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );
        private final MethodHandle addDllDirectory = downcallOptional(
                "AddDllDirectory",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );
        private final MethodHandle setDllDirectoryW = downcallOptional(
                "SetDllDirectoryW",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );
        private final MethodHandle getLastError = downcallOptional(
                "GetLastError",
                FunctionDescriptor.of(ValueLayout.JAVA_INT)
        );

        @Override
        public boolean supportsAddDllDirectory() {
            return setDefaultDllDirectories != null && addDllDirectory != null;
        }

        @Override
        public boolean setDefaultDllDirectories(int flags) {
            if (setDefaultDllDirectories == null) {
                return false;
            }
            return invokeInt(setDefaultDllDirectories, flags) != 0;
        }

        @Override
        public boolean addDllDirectory(Path dllDirectory) {
            if (addDllDirectory == null) {
                return false;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment widePath = allocateWideString(arena, dllDirectory);
                MemorySegment cookie = invokeAddress(addDllDirectory, widePath);
                return !CStrings.isNull(cookie);
            }
        }

        @Override
        public boolean setDllDirectory(Path dllDirectory) {
            if (setDllDirectoryW == null) {
                return false;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment widePath = allocateWideString(arena, dllDirectory);
                return invokeInt(setDllDirectoryW, widePath) != 0;
            }
        }

        @Override
        public int getLastError() {
            if (getLastError == null) {
                return -1;
            }
            return invokeInt(getLastError);
        }

        private MethodHandle downcallOptional(String symbolName, FunctionDescriptor descriptor) {
            return symbolLookup.find(symbolName)
                    .map(symbol -> LINKER.downcallHandle(symbol, descriptor))
                    .orElse(null);
        }

        private static MemorySegment allocateWideString(Arena arena, Path path) {
            String value = path.toAbsolutePath().normalize().toString() + "\0";
            MemorySegment segment = arena.allocate(
                    ValueLayout.JAVA_CHAR.byteSize() * value.length(),
                    ValueLayout.JAVA_CHAR.byteAlignment()
            );
            for (int i = 0; i < value.length(); i++) {
                segment.setAtIndex(ValueLayout.JAVA_CHAR, i, value.charAt(i));
            }
            return segment;
        }

        private static MemorySegment invokeAddress(MethodHandle handle, Object... args) {
            try {
                return (MemorySegment) handle.invokeWithArguments(args);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException("Native Windows loader invocation failed", e);
            }
        }

        private static int invokeInt(MethodHandle handle, Object... args) {
            try {
                return (int) handle.invokeWithArguments(args);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException("Native Windows loader invocation failed", e);
            }
        }
    }
}
