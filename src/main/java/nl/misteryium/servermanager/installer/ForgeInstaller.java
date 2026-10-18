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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ForgeInstaller implements ServerInstaller {

    private static final URI MAVEN_METADATA = URI.create(
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
    );

    private static final String FORGE_MAVEN =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Set<String> supportedVersions =
            ConcurrentHashMap.newKeySet();

    /*
     * Maps Minecraft versions to Forge artifact versions.
     *
     * Example:
     * 1.20.1 -> 1.20.1-47.4.0
     */
    private volatile Map<String, String> forgeVersions = Map.of();

    @Override
    public ServerType getType() {
        return ServerType.FORGE;
    }

    @Override
    public List<String> getAvailableVersions()
            throws IOException, InterruptedException {

        Map<String, String> refreshedVersions =
                loadForgeVersions();

        forgeVersions = Map.copyOf(refreshedVersions);

        supportedVersions.clear();
        supportedVersions.addAll(refreshedVersions.keySet());

        return List.copyOf(refreshedVersions.keySet());
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
                    "Unsupported Minecraft version. "
                            + "Refresh the official Forge version list."
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

        String forgeVersion = forgeVersions.get(minecraftVersion);

        if (forgeVersion == null) {
            return ServerInstallResult.failure(
                    directory,
                    "No Forge build found for Minecraft "
                            + minecraftVersion
                            + ". Refresh the Forge version list."
            );
        }

        String artifact = minecraftVersion + "-" + forgeVersion;
        URI installerUri = URI.create(
                FORGE_MAVEN
                        + artifact
                        + "/forge-" + artifact + "-installer.jar"
        );

        Files.createDirectories(directory);

        Path installerJar = Files.createTempFile(
                "forge-installer-",
                ".jar"
        );

        Path installLog = Files.createTempFile(
                "forge-install-",
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
                        "Forge installer exited with code "
                                + exitCode
                                + ". Log: "
                                + readLog(installLog)
                );
            }

            boolean installationCreatedFiles;

            try (var entries = Files.list(directory)) {
                installationCreatedFiles = entries.findAny().isPresent();
            }

            if (!installationCreatedFiles) {
                throw new IOException(
                        "Forge installer exited successfully "
                                + "but created no installation files."
                );
            }

            return ServerInstallResult.success(
                    directory,
                    "Forge server installed successfully."
                            + "\nMinecraft: " + minecraftVersion
                            + "\nForge: " + forgeVersion
                            + "\nLocation: " + directory
                            + "\nCheck the generated launch scripts "
                            + "or argument files to start the server."
            );

        } finally {
            Files.deleteIfExists(installerJar);
            Files.deleteIfExists(installLog);
        }
    }

    private Map<String, String> loadForgeVersions()
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
                        "Forge metadata request failed with HTTP "
                                + response.statusCode()
                );
            }

            DocumentBuilderFactory factory =
                    DocumentBuilderFactory.newInstance();

            try {
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

                Map<String, String> result = new LinkedHashMap<>();

                for (int i = 0; i < nodes.getLength(); i++) {
                    String artifactVersion = nodes.item(i)
                            .getTextContent()
                            .trim();

                    int separator = artifactVersion.indexOf('-');

                    if (separator <= 0
                            || separator == artifactVersion.length() - 1) {
                        continue;
                    }

                    String minecraftVersion =
                            artifactVersion.substring(0, separator);

                    String forgeBuild =
                            artifactVersion.substring(separator + 1);

                    if (!minecraftVersion.matches("\\d+\\.\\d+(\\.\\d+)?")
                            || forgeBuild.isBlank()) {
                        continue;
                    }

                    // Later metadata entries replace earlier builds.
                    result.put(minecraftVersion, forgeBuild);
                }

                if (result.isEmpty()) {
                    throw new IOException(
                            "No usable Forge versions found in metadata."
                    );
                }

                return result;

            } catch (Exception exception) {
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }

                throw new IOException(
                        "Could not parse Forge Maven metadata.",
                        exception
                );
            }
        }
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
                    "Forge installer download failed with HTTP "
                            + response.statusCode()
            );
        }

        if (Files.size(destination) == 0) {
            throw new IOException(
                    "Forge installer download returned an empty file."
            );
        }
    }

    private static String readLog(Path log) throws IOException {
        List<String> lines = Files.readAllLines(log);

        int start = Math.max(0, lines.size() - 30);

        return String.join(
                System.lineSeparator(),
                new ArrayList<>(lines.subList(start, lines.size()))
        );
    }
}