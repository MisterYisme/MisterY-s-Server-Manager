package nl.misteryium.servermanager.installer;

import java.nio.file.Path;
import java.util.Objects;

public record ServerInstallResult(
        boolean successful,
        Path serverDirectory,
        String message
) {
    public ServerInstallResult {
        Objects.requireNonNull(serverDirectory);
        Objects.requireNonNull(message);
    }

    public static ServerInstallResult success(
            Path directory,
            String message
    ) {
        return new ServerInstallResult(true, directory, message);
    }

    public static ServerInstallResult failure(
            Path directory,
            String message
    ) {
        return new ServerInstallResult(false, directory, message);
    }
}