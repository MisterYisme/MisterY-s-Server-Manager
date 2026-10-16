package nl.misteryium.servermanager.installer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.misteryium.servermanager.ServerType;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VanillaInstaller implements ServerInstaller {
    private static final URI VERSION_MANIFEST = URI.create(
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
    );

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Set<String> supportedVersions = ConcurrentHashMap.newKeySet();
    @Override
    public ServerType getType() {
        return ServerType.VANILLA;
    }

    @Override
    public List<String> getAvailableVersions() throws IOException, InterruptedException {
        JsonNode manifest = getJson(VERSION_MANIFEST);
        JsonNode versions = manifest.path("versions");

        if (!versions.isArray()) {
            throw new IOException(
                    "Invalid Minecraft version manifest."
            );
        }

        List<String> result = new ArrayList<>();
        supportedVersions.clear();

        for (JsonNode version : versions) {
            if (!"release".equals(version.path("type").asText())) {
                continue;
            }

            String id = version.path("id").asText();

            if (!id.isBlank()) {
                result.add(id);
                supportedVersions.add(id);
            }
        }

        return List.copyOf(result);
    }

    @Override
    public boolean supportsVersion(String minecraftVersion) {
        return minecraftVersion != null && supportedVersions.contains(minecraftVersion);
    }

    @Override
    public ServerInstallResult install(Path serverDir, String minecraftVersion) throws IOException, InterruptedException {
        Path directory = serverDir.toAbsolutePath().normalize();
        Path serverJar = directory.resolve("server.jar");

        if (Files.exists(serverJar)) {
            return ServerInstallResult.failure(
                    directory,
                    "server.jar already exists. Installation cancelled."
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

        if (!supportsVersion(minecraftVersion)) {
            return ServerInstallResult.failure(
                    directory,
                    "Unsupported version. Refresh the official version list."
            );
        }

        Files.createDirectories(directory);

        Path temporaryJar = Files.createTempFile(
                directory, ".server-download-", ".tmp"
        );

        try {
            JsonNode versionEntry = findVersion(minecraftVersion);
            URI metadataUri = officialUri(
                    versionEntry.path("url").asText()
            );

            JsonNode metadata = getJson(metadataUri);
            JsonNode download = metadata.path("downloads").path("server");

            String url = download.path("url").asText();
            String expectedSha1 = download.path("sha1").asText();

            if (url.isBlank() || !expectedSha1.matches("[a-fA-F0-9]{40}")) {
                throw new IOException(
                        "Server download metadata is missing or invalid."
                );
            }

            URI downloadUri = officialUri(url);

            HttpRequest request = HttpRequest.newBuilder(downloadUri)
                    .GET()
                    .build();

            HttpResponse<Path> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofFile(temporaryJar)
            );

            if (response.statusCode() != 200) {
                throw new IOException(
                        "Server download failed with HTTP "
                                + response.statusCode()
                );
            }

            String actualSha1 = sha1(temporaryJar);

            if (!actualSha1.equalsIgnoreCase(expectedSha1)) {
                throw new IOException(
                        "SHA-1 verification failed. Download discarded."
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
                    "Minecraft Java Edition " + minecraftVersion
                            + " installed successfully at " + serverJar
            );

        } finally {
            Files.deleteIfExists(temporaryJar);
        }
    }

    private JsonNode findVersion(String version)
            throws IOException, InterruptedException {

        JsonNode versions = getJson(VERSION_MANIFEST).path("versions");

        for (JsonNode entry : versions) {
            if (version.equals(entry.path("id").asText())
                    && "release".equals(entry.path("type").asText())) {
                return entry;
            }
        }

        throw new IOException(
                "Version not found in official manifest: " + version
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
                        "Metadata request failed with HTTP "
                                + response.statusCode()
                );
            }

            return JSON.readTree(body);
        }
    }

    private static URI officialUri(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();

            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || !(host.equalsIgnoreCase("mojang.com")
                    || host.toLowerCase(Locale.ROOT)
                    .endsWith(".mojang.com"))
                    || uri.getUserInfo() != null) {
                throw new IOException(
                        "Refusing non-official download URL."
                );
            }

            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid URL in official metadata.", exception);
        }
    }

    private static String sha1(Path file) throws IOException {
        final MessageDigest digest;

        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-1 is unavailable in this Java runtime.", exception
            );
        }

        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;

            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }
}
