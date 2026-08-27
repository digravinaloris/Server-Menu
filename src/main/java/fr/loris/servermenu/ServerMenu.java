package fr.loris.servermenu;

public final class ServerMenu extends org.bukkit.plugin.java.JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Command registration and dialog building/opening both happen in
        // ServerMenuBootstrap (LifecycleEvents.COMMANDS + RegistryEvents.DIALOG).
        // Paper plugins don't support the legacy getCommand()/plugin.yml commands: flow.
        getLogger().info("ServerMenu enabled.");
    }
}
