package nl.misteryium.servermanager.installer;

import nl.misteryium.servermanager.ServerType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class FabricInstaller implements ServerInstaller {
    @Override
    public ServerType getType() {
        return ServerType.FABRIC;
    }

    @Override
    public List<String> getAvailableVersions() throws IOException, InterruptedException {
        return List.of();
    }

    @Override
    public boolean supportsVersion(String minecraftVersion) {
        return false;
    }

    @Override
    public ServerInstallResult install(Path serverDir, String minecraftVersion) throws IOException, InterruptedException {
        return null;
    }
}
