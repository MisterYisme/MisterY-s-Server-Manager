package nl.misteryium.servermanager.installer;

import nl.misteryium.servermanager.ServerType;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

public final class ServerInstallerRegistry {

    private final Map<ServerType, ServerInstaller> installers =
            new EnumMap<>(ServerType.class);

    public ServerInstallerRegistry(
            Collection<ServerInstaller> installers
    ) {
        for (ServerInstaller installer : installers) {
            ServerInstaller previous = this.installers.put(
                    installer.getType(),
                    installer
            );

            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate installer for: "
                                + installer.getType()
                );
            }
        }
    }

    public ServerInstaller get(ServerType type) {
        ServerInstaller installer = installers.get(type);

        if (installer == null) {
            throw new IllegalArgumentException(
                    "No installer registered for: " + type
            );
        }

        return installer;
    }

    public Collection<ServerInstaller> getAll() {
        return installers.values();
    }
}