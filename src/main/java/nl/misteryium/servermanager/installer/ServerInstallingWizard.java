package nl.misteryium.servermanager.installer;

import nl.misteryium.servermanager.ServerType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

public final class ServerInstallingWizard {

    private final ServerInstallerRegistry registry;

    public ServerInstallingWizard(
            ServerInstallerRegistry registry
    ) {
        this.registry = registry;
    }

    public ServerInstallConfig run(Scanner scanner)
            throws IOException, InterruptedException {

        ServerType type = selectServerType(scanner);
        if (type == null) return null;

        String version = selectMinecraftVersion(scanner, type);
        if (version == null) return null;

        Path directory = selectInstallDirectory(scanner);
        if (directory == null) return null;

        System.out.println("\nInstallation summary");
        System.out.println("Server type: " + type);
        System.out.println("Minecraft version: " + version);
        System.out.println("Directory: " + directory);

        System.out.print("Begin installation? [Y/n]: ");

        String input = scanner.nextLine().trim();

        if (!input.isEmpty()
                && !input.equalsIgnoreCase("y")
                && !input.equalsIgnoreCase("yes")) {
            return null;
        }

        return new ServerInstallConfig(type, version, directory);
    }

    private ServerType selectServerType(Scanner scanner) {
        List<ServerType> types = registry.getAll()
                .stream()
                .map(ServerInstaller::getType)
                .toList();

        if (types.isEmpty()) {
            throw new IllegalStateException(
                    "No server installers are registered."
            );
        }

        while (true) {
            System.out.println("\nAvailable server types:");

            for (int i = 0; i < types.size(); i++) {
                System.out.printf("%d. %s%n",
                        i + 1, types.get(i));
            }

            System.out.print("Select a type (or 'q' to cancel): ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("q")) return null;

            try {
                int choice = Integer.parseInt(input);

                if (choice >= 1 && choice <= types.size()) {
                    return types.get(choice - 1);
                }
            } catch (NumberFormatException ignored) {
                // Display the validation message below.
            }

            System.out.println("Invalid selection.");
        }
    }

    private String selectMinecraftVersion(
            Scanner scanner,
            ServerType type
    ) throws IOException, InterruptedException {

        ServerInstaller installer = registry.get(type);
        List<String> versions = installer.getAvailableVersions();

        if (versions.isEmpty()) {
            throw new IOException(
                    "No versions are available for " + type
            );
        }

        while (true) {
            System.out.println("\nAvailable Minecraft versions:");

            for (int i = 0; i < versions.size(); i++) {
                System.out.printf("%d. %s%n",
                        i + 1, versions.get(i));
            }

            System.out.print("Select a version (or 'q' to cancel): ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("q")) return null;

            try {
                int choice = Integer.parseInt(input);

                if (choice >= 1 && choice <= versions.size()) {
                    String version = versions.get(choice - 1);

                    if (installer.supportsVersion(version)) {
                        return version;
                    }
                }
            } catch (NumberFormatException ignored) {
                // Display the validation message below.
            }

            System.out.println("Invalid or unsupported version.");
        }
    }

    private Path selectInstallDirectory(Scanner scanner) {
        while (true) {
            System.out.print(
                    "Installation directory (or 'q' to cancel): "
            );

            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("q")) return null;
            if (input.isBlank()) {
                System.out.println("Directory cannot be empty.");
                continue;
            }

            Path directory;

            try {
                directory = Path.of(input)
                        .toAbsolutePath()
                        .normalize();
            } catch (java.nio.file.InvalidPathException exception) {
                System.out.println("Invalid directory path.");
                continue;
            }

            if (Files.exists(directory)) {
                if (!Files.isDirectory(directory)) {
                    System.out.println(
                            "The path exists but is not a directory."
                    );
                    continue;
                }

                try (var entries = Files.list(directory)) {
                    if (entries.findAny().isPresent()) {
                        System.out.println(
                                "Directory is not empty. "
                                        + "Choose an empty directory."
                        );
                        continue;
                    }
                } catch (IOException exception) {
                    System.out.println(
                            "Cannot access directory: "
                                    + exception.getMessage()
                    );
                    continue;
                }
            }

            return directory;
        }
    }
}