package nl.misteryium.servermanager;

public enum ServerType {
    VANILLA("Vanilla"),
    FABRIC("Fabric"),
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
    QUILT("Quilt"),
    CUSTOM("Custom");

    private final String displayName;

    ServerType(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}