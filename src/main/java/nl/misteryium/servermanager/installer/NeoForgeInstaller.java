package nl.misteryium.servermanager.installer;

import nl.misteryium.servermanager.ServerType;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NeoForgeInstaller implements ServerInstaller {

    private static final URI MAVEN_METADATA = URI.create(
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
    );

    private static final String MAVEN_BASE =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/";

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.(\\d+))?$"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Set<String> supportedVersions =
            ConcurrentHashMap.newKeySet();

    /*
     * Maps Minecraft versions to their selected NeoForge build.
     *
     * Examples:
     * 1.21.1 -> 21.1.228
     * 1.20.4 -> 20.4.176
     * 26.3   -> 26.3.0.84
     */
    private volatile Map<String, String> neoForgeVersions = Map.of();

    @Override
    public ServerType getType() {
        return ServerType.NEOFORGE;
    }

    @Override
    public List<String> getAvailableVersions()
            throws IOException, InterruptedException {

        Map<String, String> refreshed = loadNeoForgeVersions();

        neoForgeVersions = Map.copyOf(refreshed);

        supportedVersions.clear();
        supportedVersions.addAll(refreshed.keySet());

        return List.copyOf(
                new TreeSet<>(refreshed.keySet())
        );
    }

    @Override
    public boolean supportsVersion(String minecraftVersion) {
        return minecraftVersion != null
                && supportedVersions.contains(minecraftVersion);
    }

    @Override
    public ServerInstallResult install(
            Path serverDir,
            String minecraftVersion
    ) throws IOException, InterruptedException {

        Path directory = serverDir.toAbsolutePath().normalize();

        if (!supportsVersion(minecraftVersion)) {
            return ServerInstallResult.failure(
                    directory,
                    "Unsupported Minecraft version. Refresh the official NeoForge version list."
            );
        }

        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory)) {
                return ServerInstallResult.failure(
                        directory,
                        "The installation path is not a directory."
                );
            }

            try (var entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) {
                    return ServerInstallResult.failure(
                            directory,
                            "The installation directory is not empty."
                    );
                }
            }
        }

        String neoForgeVersion =
                neoForgeVersions.get(minecraftVersion);

        if (neoForgeVersion == null) {
            return ServerInstallResult.failure(
                    directory,
                    "No NeoForge build found for Minecraft "
                            + minecraftVersion
            );
        }

        URI installerUri = URI.create(
                MAVEN_BASE
                        + neoForgeVersion
                        + "/neoforge-"
                        + neoForgeVersion
                        + "-installer.jar"
        );

        Files.createDirectories(directory);

        Path installerJar = Files.createTempFile(
                "neoforge-installer-",
                ".jar"
        );

        Path installLog = Files.createTempFile(
                "neoforge-install-",
                ".log"
        );

        try {
            download(installerUri, installerJar);

            Process process = new ProcessBuilder(
                    "java",
                    "-jar",
                    installerJar.toAbsolutePath().toString(),
                    "--installServer"
            )
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(installLog.toFile())
                    .start();

            final int exitCode;

            try {
                exitCode = process.waitFor();
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw exception;
            }

            if (exitCode != 0) {
                throw new IOException(
                        "NeoForge installer exited with code "
                                + exitCode
                                + ". Log: "
                                + readLog(installLog)
                );
            }

            boolean hasFiles;

            try (var entries = Files.list(directory)) {
                hasFiles = entries.findAny().isPresent();
            }

            if (!hasFiles) {
                throw new IOException(
                        "NeoForge installer created no installation files."
                );
            }

            return ServerInstallResult.success(
                    directory,
                    "NeoForge server installed successfully."
                            + "\nMinecraft: " + minecraftVersion
                            + "\nNeoForge: " + neoForgeVersion
                            + "\nLocation: " + directory
                            + "\nUse the generated run.sh or run.bat "
                            + "to start the server."
            );

        } finally {
            Files.deleteIfExists(installerJar);
            Files.deleteIfExists(installLog);
        }
    }

    private Map<String, String> loadNeoForgeVersions()
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder(MAVEN_METADATA)
                .header("Accept", "application/xml")
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException(
                        "NeoForge metadata request failed with HTTP "
                                + response.statusCode()
                );
            }

            try {
                DocumentBuilderFactory factory =
                        DocumentBuilderFactory.newInstance();

                factory.setFeature(
                        XMLConstants.FEATURE_SECURE_PROCESSING,
                        true
                );
                factory.setFeature(
                        "http://apache.org/xml/features/"
                                + "disallow-doctype-decl",
                        true
                );
                factory.setFeature(
                        "http://xml.org/sax/features/"
                                + "external-general-entities",
                        false
                );
                factory.setFeature(
                        "http://xml.org/sax/features/"
                                + "external-parameter-entities",
                        false
                );
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                factory.setAttribute(
                        XMLConstants.ACCESS_EXTERNAL_DTD,
                        ""
                );
                factory.setAttribute(
                        XMLConstants.ACCESS_EXTERNAL_SCHEMA,
                        ""
                );

                Document document = factory.newDocumentBuilder()
                        .parse(body);

                NodeList nodes = document.getElementsByTagName(
                        "version"
                );

                /*
                 * First collect all stable NeoForge builds.
                 * Maven metadata order is not assumed to be numeric.
                 */
                Map<String, List<String>> candidates =
                        new LinkedHashMap<>();

                for (int i = 0; i < nodes.getLength(); i++) {
                    String version = nodes.item(i)
                            .getTextContent()
                            .trim();

                    // Ignore beta, alpha, and other qualified builds.
                    if (!VERSION_PATTERN.matcher(version).matches()) {
                        continue;
                    }

                    String minecraftVersion =
                            toMinecraftVersion(version);

                    candidates.computeIfAbsent(
                            minecraftVersion,
                            ignored -> new ArrayList<>()
                    ).add(version);
                }

                Map<String, String> result = new LinkedHashMap<>();

                Comparator<String> comparator =
                        NeoForgeInstaller::compareVersions;

                candidates.forEach((minecraftVersion, builds) -> {
                    builds.sort(comparator.reversed());

                    if (!builds.isEmpty()) {
                        result.put(minecraftVersion, builds.getFirst());
                    }
                });

                if (result.isEmpty()) {
                    throw new IOException(
                            "No stable NeoForge versions found."
                    );
                }

                return result;

            } catch (Exception exception) {
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }

                throw new IOException(
                        "Could not parse NeoForge Maven metadata.",
                        exception
                );
            }
        }
    }

    /*
     * NeoForge 21.1.x -> Minecraft 1.21.1
     * NeoForge 20.4.x -> Minecraft 1.20.4
     * NeoForge 26.3.x -> Minecraft 26.3
     */
    private static String toMinecraftVersion(String version) {
        Matcher matcher = VERSION_PATTERN.matcher(version);

        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Invalid NeoForge version: " + version
            );
        }

        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));

        if (major >= 26) {
            return major + "." + minor;
        }

        return "1." + major + "." + minor;
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");

        int length = Math.max(a.length, b.length);

        for (int i = 0; i < length; i++) {
            int av = i < a.length ? Integer.parseInt(a[i]) : 0;
            int bv = i < b.length ? Integer.parseInt(b[i]) : 0;

            int comparison = Integer.compare(av, bv);

            if (comparison != 0) {
                return comparison;
            }
        }

        return 0;
    }

    private void download(URI uri, Path destination)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .build();

        HttpResponse<Path> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofFile(destination)
        );

        if (response.statusCode() != 200) {
            throw new IOException(
                    "NeoForge download failed with HTTP "
                            + response.statusCode()
            );
        }

        if (Files.size(destination) == 0) {
            throw new IOException(
                    "NeoForge installer download was empty."
            );
        }
    }

    private static String readLog(Path log) throws IOException {
        List<String> lines = Files.readAllLines(log);

        int start = Math.max(0, lines.size() - 30);

        return String.join(
                System.lineSeparator(),
                lines.subList(start, lines.size())
        );
    }
}