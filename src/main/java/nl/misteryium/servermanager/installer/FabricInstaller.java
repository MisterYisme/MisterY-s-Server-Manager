package nl.misteryium.servermanager.installer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.misteryium.servermanager.ServerType;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FabricInstaller implements ServerInstaller {

    private static final String API = "https://meta.fabricmc.net/v2";

    private static final URI GAME_VERSIONS =
            URI.create(API + "/versions/game");

    private static final URI LOADER_VERSIONS =
            URI.create(API + "/versions/loader");

    private static final URI INSTALLER_VERSIONS =
            URI.create(API + "/versions/installer");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Set<String> supportedVersions =
            ConcurrentHashMap.newKeySet();

    @Override
    public ServerType getType() {
        return ServerType.FABRIC;
    }

    @Override
    public List<String> getAvailableVersions()
            throws IOException, InterruptedException {

        JsonNode versions = getJson(GAME_VERSIONS);

        if (!versions.isArray()) {
            throw new IOException(
                    "Invalid Fabric game version response."
            );
        }

        List<String> result = new ArrayList<>();
        Set<String> refreshedVersions = ConcurrentHashMap.newKeySet();

        for (JsonNode version : versions) {
            if (!version.path("stable").asBoolean(false)) {
                continue;
            }

            String id = version.path("version").asText();

            if (!id.isBlank()) {
                result.add(id);
                refreshedVersions.add(id);
            }
        }

        supportedVersions.clear();
        supportedVersions.addAll(refreshedVersions);

        return List.copyOf(result);
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
        Path serverJar = directory.resolve("server.jar");

        if (!supportsVersion(minecraftVersion)) {
            return ServerInstallResult.failure(
                    directory,
                    "Unsupported Minecraft version. "
                            + "Refresh the official Fabric version list."
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

        Files.createDirectories(directory);

        Path temporaryJar = Files.createTempFile(
                directory,
                ".fabric-download-",
                ".tmp"
        );

        try {
            String loaderVersion = getLatestStableLoader(
                    minecraftVersion
            );

            String installerVersion = getLatestStableInstaller();

            URI downloadUri = URI.create(
                    API + "/versions/loader/"
                            + encodePathSegment(minecraftVersion)
                            + "/"
                            + encodePathSegment(loaderVersion)
                            + "/"
                            + encodePathSegment(installerVersion)
                            + "/server/jar"
            );

            HttpRequest request = HttpRequest.newBuilder(downloadUri)
                    .GET()
                    .build();

            HttpResponse<Path> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofFile(temporaryJar)
            );

            if (response.statusCode() != 200) {
                throw new IOException(
                        "Fabric server download failed with HTTP "
                                + response.statusCode()
                );
            }

            if (Files.size(temporaryJar) == 0) {
                throw new IOException(
                        "Fabric server download returned an empty file."
                );
            }

            try {
                Files.move(
                        temporaryJar,
                        serverJar,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryJar, serverJar);
            }

            return ServerInstallResult.success(
                    directory,
                    "Fabric server installed successfully."
                            + "\nMinecraft: " + minecraftVersion
                            + "\nFabric Loader: " + loaderVersion
                            + "\nFabric Installer: " + installerVersion
                            + "\nLocation: " + serverJar
            );

        } finally {
            Files.deleteIfExists(temporaryJar);
        }
    }

    private String getLatestStableLoader(String minecraftVersion)
            throws IOException, InterruptedException {

        URI uri = URI.create(
                API + "/versions/loader/"
                        + encodePathSegment(minecraftVersion)
        );

        JsonNode versions = getJson(uri);

        if (!versions.isArray()) {
            throw new IOException(
                    "Invalid Fabric loader version response."
            );
        }

        for (JsonNode entry : versions) {
            JsonNode loader = entry.path("loader");

            if (loader.path("stable").asBoolean(false)) {
                String version = loader.path("version").asText();

                if (!version.isBlank()) {
                    return version;
                }
            }
        }

        throw new IOException(
                "No stable Fabric Loader found for Minecraft "
                        + minecraftVersion
        );
    }

    private String getLatestStableInstaller()
            throws IOException, InterruptedException {

        JsonNode versions = getJson(INSTALLER_VERSIONS);

        if (!versions.isArray()) {
            throw new IOException(
                    "Invalid Fabric installer version response."
            );
        }

        for (JsonNode entry : versions) {
            if (entry.path("stable").asBoolean(false)) {
                String version = entry.path("version").asText();

                if (!version.isBlank()) {
                    return version;
                }
            }
        }

        throw new IOException(
                "No stable Fabric installer version is available."
        );
    }

    private JsonNode getJson(URI uri)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException(
                        "Fabric metadata request failed with HTTP "
                                + response.statusCode()
                );
            }

            return JSON.readTree(body);
        }
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
