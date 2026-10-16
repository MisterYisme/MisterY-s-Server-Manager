package nl.misteryium.servermanager.installer;

import nl.misteryium.servermanager.ServerType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface ServerInstaller {

    ServerType getType();

    List<String> getAvailableVersions()
            throws IOException, InterruptedException;

    boolean supportsVersion(String minecraftVersion);

    ServerInstallResult install(
            Path serverDir,
            String minecraftVersion
    ) throws IOException, InterruptedException;
}