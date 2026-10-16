package nl.misteryium.servermanager.installer;

import java.io.IOException;

public final class ServerInstallationService {

    private final ServerInstallerRegistry registry;

    public ServerInstallationService(
            ServerInstallerRegistry registry
    ) {
        this.registry = registry;
    }

    public ServerInstallResult install(
            ServerInstallConfig config
    ) throws IOException, InterruptedException {

        ServerInstaller installer = registry.get(config.type());

        if (!installer.supportsVersion(config.minecraftVersion())) {
            throw new IllegalArgumentException(
                    "Unsupported Minecraft version: "
                            + config.minecraftVersion()
            );
        }

        return installer.install(
                config.installDirectory(),
                config.minecraftVersion()
        );
    }
}