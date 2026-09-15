package ch.so.agi.pdal.ffm.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Locates, extracts and loads the platform native bundle of the runtime
 * dependency (e.g. {@code pdal-ffm-natives-<version>-natives-osx-aarch64.jar}).
 */
final class NativeLoader {
    private static final String RESOURCE_ROOT = "META-INF/pdal-native";
    private static final AtomicReference<NativeBundleInfo> LOADED = new AtomicReference<>();
    private static final Object LOCK = new Object();

    private NativeLoader() {
    }

    static NativeBundleInfo load() {
        NativeBundleInfo existing = LOADED.get();
        if (existing != null) {
            return existing;
        }

        synchronized (LOCK) {
            existing = LOADED.get();
            if (existing != null) {
                return existing;
            }
            NativeBundleInfo loaded = loadOnce();
            LOADED.set(loaded);
            return loaded;
        }
    }

    private static NativeBundleInfo loadOnce() {
        NativePlatform platform = NativePlatform.current();
        String classifier = platform.classifier();
        String prefix = RESOURCE_ROOT + "/" + classifier;

        URL manifestUrl = findManifest(prefix, classifier);
        NativeManifest manifest = NativeManifest.parse(readUtf8(manifestUrl));
        NativeBundleInfo bundleInfo = resolveBundleInfo(manifestUrl, manifest, classifier);
        WindowsNativeLibraryPathSupport.configureIfNeeded(platform, bundleInfo.extractionRoot());

        for (String preload : manifest.preloadLibraries()) {
            loadLibrary(bundleInfo.extractionRoot(), preload, "preloadLibraries");
        }
        loadLibrary(bundleInfo.extractionRoot(), manifest.ffiLibrary(), "ffiLibrary");
        loadLibrary(bundleInfo.extractionRoot(), manifest.entryLibrary(), "entryLibrary");
        return bundleInfo;
    }

    static NativeBundleInfo resolveBundleInfo(URL manifestUrl, NativeManifest manifest, String classifier) {
        Path extractionRoot = resolveBundleRoot(manifestUrl, extractionIdentity(manifest), classifier);
        return new NativeBundleInfo(
                classifier,
                manifest.bundleVersion(),
                extractionRoot,
                resolveOptional(extractionRoot, manifest.gdalDataPath()),
                resolveOptional(extractionRoot, manifest.projDataPath()),
                resolveOptional(extractionRoot, manifest.pluginPath()),
                resolveOptional(extractionRoot, manifest.caBundlePath())
        );
    }

    private static String extractionIdentity(NativeManifest manifest) {
        String cacheKey = manifest.cacheKey();
        if (cacheKey != null && !cacheKey.isBlank()) {
            return cacheKey;
        }
        return manifest.bundleVersion();
    }

    private static URL findManifest(String prefix, String classifier) {
        String manifestResource = prefix + "/manifest.json";
        try {
            // Hop executes transforms in plugin class loaders; the thread context
            // class loader is not necessarily the plugin loader that contains the
            // bundled natives. Search all relevant loaders and require one match.
            List<URL> matches = new ArrayList<>();
            collectManifests(Thread.currentThread().getContextClassLoader(), manifestResource, matches);
            collectManifests(NativeLoader.class.getClassLoader(), manifestResource, matches);
            collectManifests(ClassLoader.getSystemClassLoader(), manifestResource, matches);

            if (matches.isEmpty()) {
                throw new IllegalStateException(
                        "No bundled PDAL native resources found for classifier '" + classifier + "'. "
                                + "Add runtime dependency ch.so.agi:pdal-ffm-natives:<VERSION>:natives-"
                                + classifier
                );
            }

            URL first = matches.get(0);
            if (matches.size() > 1) {
                StringBuilder all = new StringBuilder(first.toString());
                for (int i = 1; i < matches.size(); i++) {
                    all.append(", ").append(matches.get(i));
                }
                throw new IllegalStateException(
                        "Multiple bundled PDAL native resources found for classifier '" + classifier + "': "
                                + all
                                + ". Add exactly one runtime dependency "
                                + "ch.so.agi:pdal-ffm-natives:<VERSION>:natives-" + classifier
                );
            }
            return first;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan classpath for " + manifestResource, e);
        }
    }

    private static void collectManifests(ClassLoader classLoader, String manifestResource, List<URL> matches)
            throws IOException {
        if (classLoader == null) {
            return;
        }
        Enumeration<URL> urls = classLoader.getResources(manifestResource);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (matches.stream().noneMatch(existing -> existing.toString().equals(url.toString()))) {
                matches.add(url);
            }
        }
    }

    private static String readUtf8(URL url) {
        try (InputStream inputStream = url.openStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read native manifest: " + url, e);
        }
    }

    private static Path extractionRoot(String extractionIdentity, String classifier) {
        String javaTmp = System.getProperty("java.io.tmpdir");
        return Path.of(javaTmp, "pdal-ffm", extractionIdentity, classifier);
    }

    private static Path resolveBundleRoot(URL manifestUrl, String extractionIdentity, String classifier) {
        if ("file".equals(manifestUrl.getProtocol())) {
            return fileTreeRoot(manifestUrl);
        }

        Path extractionRoot = extractionRoot(extractionIdentity, classifier);
        Path marker = extractionRoot.resolve(".extract-complete");
        if (!Files.exists(marker)) {
            extractBundle(manifestUrl, extractionRoot);
            try {
                Files.createDirectories(extractionRoot);
                Files.writeString(marker, extractionIdentity, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create extraction marker: " + marker, e);
            }
        }
        return extractionRoot;
    }

    private static Path fileTreeRoot(URL manifestUrl) {
        Path manifestPath;
        try {
            manifestPath = Path.of(manifestUrl.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid manifest URL: " + manifestUrl, e);
        }

        Path sourceRoot = manifestPath.getParent();
        if (sourceRoot == null || !Files.exists(sourceRoot)) {
            throw new IllegalStateException("Native resource source path does not exist: " + manifestPath);
        }
        return sourceRoot;
    }

    private static void extractBundle(URL manifestUrl, Path extractionRoot) {
        try {
            Files.createDirectories(extractionRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create extraction directory: " + extractionRoot, e);
        }

        String protocol = manifestUrl.getProtocol();
        if ("jar".equals(protocol)) {
            extractFromJar(manifestUrl, extractionRoot);
            return;
        }
        if ("file".equals(protocol)) {
            extractFromFileTree(manifestUrl, extractionRoot);
            return;
        }
        throw new IllegalStateException("Unsupported manifest URL protocol for native extraction: " + protocol);
    }

    private static void extractFromJar(URL manifestUrl, Path extractionRoot) {
        try {
            JarURLConnection connection = (JarURLConnection) manifestUrl.openConnection();
            connection.setUseCaches(false);

            String entryName = connection.getEntryName();
            String prefix = entryName.substring(0, entryName.length() - "manifest.json".length());

            try (JarFile jarFile = connection.getJarFile()) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                        continue;
                    }

                    String relativeName = entry.getName().substring(prefix.length());
                    if (relativeName.isBlank()) {
                        continue;
                    }

                    Path target = safeResolve(extractionRoot, relativeName);
                    Files.createDirectories(target.getParent());
                    try (InputStream inputStream = jarFile.getInputStream(entry)) {
                        Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract native bundle from JAR: " + manifestUrl, e);
        }
    }

    private static void extractFromFileTree(URL manifestUrl, Path extractionRoot) {
        Path sourceRoot = fileTreeRoot(manifestUrl);

        try (var stream = Files.walk(sourceRoot)) {
            stream.filter(Files::isRegularFile).forEach(source -> {
                Path relative = sourceRoot.relativize(source);
                Path target = safeResolve(extractionRoot, relative.toString());
                try {
                    Files.createDirectories(Objects.requireNonNull(target.getParent()));
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to copy native resource " + source + " to " + target, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to traverse native resource tree: " + sourceRoot, e);
        }
    }

    private static void loadLibrary(Path extractionRoot, String relativePath, String sourceField) {
        Path libPath = safeResolve(extractionRoot, relativePath);
        if (!Files.exists(libPath)) {
            throw new IllegalStateException(
                    "Native library listed in " + sourceField + " is missing: " + relativePath
            );
        }
        try {
            System.load(libPath.toString());
        } catch (UnsatisfiedLinkError e) {
            if (isAlreadyLoadedByAnotherClassLoader(e, libPath)) {
                return;
            }
            throw e;
        }
    }

    private static boolean isAlreadyLoadedByAnotherClassLoader(UnsatisfiedLinkError error, Path libPath) {
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("already loaded in another classloader")
                && (message.contains(libPath.toString()) || message.contains(libPath.getFileName().toString()));
    }

    private static Path resolveOptional(Path extractionRoot, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path path = safeResolve(extractionRoot, relativePath);
        return Files.exists(path) ? path : null;
    }

    private static Path safeResolve(Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalStateException("Invalid path in native manifest: " + relative);
        }
        return resolved;
    }
}
