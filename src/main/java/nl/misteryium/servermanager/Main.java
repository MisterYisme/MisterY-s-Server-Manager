package nl.misteryium.servermanager;

import nl.misteryium.servermanager.installer.*;

import java.io.IOException;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {

        try (Scanner scanner = new Scanner(System.in)) {
            ServerInstallerRegistry registry =
                    new ServerInstallerRegistry(List.of(
                            new VanillaInstaller(),
                            new FabricInstaller(),
                            new ForgeInstaller()
//                            new NeoForgeInstaller(),
//                            new QuiltInstaller()
                    ));

            ServerInstallingWizard wizard =
                    new ServerInstallingWizard(registry);

            ServerInstallConfig config = wizard.run(scanner);

            if (config != null) {
                ServerInstallationService service =
                        new ServerInstallationService(registry);

                ServerInstallResult result = service.install(config);

                System.out.println(result.message());
            } else {
                System.out.println("Installation cancelled.");
            }
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}
