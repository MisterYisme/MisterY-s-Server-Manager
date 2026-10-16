package nl.misteryium.servermanager.installer;

import nl.misteryium.servermanager.ServerType;

import java.nio.file.Path;
import java.util.Objects;

public record ServerInstallConfig(
        ServerType type,
        String minecraftVersion,
        Path installDirectory
) {
    public ServerInstallConfig {
        Objects.requireNonNull(type);
        Objects.requireNonNull(minecraftVersion);
        Objects.requireNonNull(installDirectory);

        if (minecraftVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "Minecraft version cannot be blank."
            );
        }

        installDirectory = installDirectory
                .toAbsolutePath()
                .normalize();
    }
}