package clre20.discordLink;

import clre20.discordLink.api.ApiBridge;
import clre20.discordLink.command.DiscordLinkCommand;
import clre20.discordLink.config.Messages;
import clre20.discordLink.config.PluginConfig;
import clre20.discordLink.data.LinkStore;
import clre20.discordLink.data.PlayerLookup;
import clre20.discordLink.discord.DiscordBot;
import clre20.discordLink.discord.DiscordListener;
import clre20.discordLink.listener.LockedListener;
import clre20.discordLink.listener.LoginListener;
import clre20.discordLink.update.UpdateChecker;
import clre20.discordLink.update.UpdateNotifier;
import clre20.discordLink.verify.CodeManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.UUID;

public final class DiscordLink extends JavaPlugin {

    private PluginConfig config;
    private Messages messages;
    private LinkStore store;
    private PlayerLookup lookup;
    private CodeManager codes;
    private DiscordBot discord;
    private ApiBridge api;
    private UpdateChecker updates;
    private UpdateNotifier updateNotifier;

    @Override
    public void onEnable() {
        config = new PluginConfig(this);
        messages = new Messages(this);
        loadFiles();

        store = new LinkStore(new File(getDataFolder(), config.dbFile()),
                reason -> getLogger().warning(messages.console("db-error", "reason", reason)));
        try {
            store.load();
        } catch (Exception e) {
            // 資料庫打不開：讀不到綁定資料就無法判斷誰能進服。強制綁定模式下只讓 OP 進服（寧可擋住，不放行）
            getLogger().severe(messages.console("db-error", "reason", e.getMessage()));
            getLogger().severe(messages.console("db-locked"));
            store.close();
            store = null;
            if (config.forceBinding()) {
                getServer().getPluginManager().registerEvents(new LockedListener(this), this);
            }
            return;
        }
        fillMissingNames();
        getLogger().info(messages.console("db-loaded", "bindings", store.bindingCount(), "bypass", store.bypassCount()));

        lookup = new PlayerLookup(this, store);
        codes = new CodeManager(config.codeExpiryMinutes(), config.attemptWindowMinutes());
        updates = new UpdateChecker(getPluginMeta().getVersion());
        updateNotifier = new UpdateNotifier(this);
        api = new ApiBridge(this);
        discord = new DiscordBot(this, new DiscordListener(this));

        getServer().getPluginManager().registerEvents(new LoginListener(this), this);
        getServer().getPluginManager().registerEvents(updateNotifier, this);
        getServer().getPluginManager().registerEvents(api, this);

        PluginCommand command = getCommand("discordlink");
        if (command != null) {
            DiscordLinkCommand executor = new DiscordLinkCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        printMode();
        api.refresh(true);
        startDiscord();
        updateNotifier.checkOnStartup();
    }

    @Override
    public void onDisable() {
        if (api != null) {
            api.stop();
        }
        if (discord != null) {
            discord.shutdown();
        }
        if (lookup != null) {
            lookup.shutdown();
        }
        if (updates != null) {
            updates.shutdown();
        }
        if (codes != null) {
            codes.clear();
        }
        if (store != null) {
            store.close();
        }
    }

    /** 讀取（並補齊）config.yml 與 messages.yml。 */
    private void loadFiles() {
        config.load();
        messages.load(config.legacyMessages());
        if (config.migrated()) {
            getLogger().info(messages.console("config-migrated"));
        }
        if (config.hadOldApiPort()) {
            getLogger().info(messages.console("old-api-port"));
        }
        if (config.lastFilled() > 0) {
            getLogger().info(messages.console("config-filled", "count", config.lastFilled()));
        }
        if (messages.lastFilled() > 0) {
            getLogger().info(messages.console("messages-filled", "count", messages.lastFilled()));
        }
        if (config.badDbName()) {
            getLogger().warning(messages.console("bad-db-name"));
        }
        String key = config.generatedKey();
        if (key != null) {
            getLogger().info(messages.console("api-key-generated", "masked", key.substring(0, 4) + "…" + key.substring(key.length() - 4)));
        } else if (config.apiKey().length() < 16) {
            getLogger().warning(messages.console("api-key-short"));
        }
    }

    /** 舊資料沒有名稱欄位：從伺服器的玩家快取補上（只讀本機快取，不連網）。 */
    private void fillMissingNames() {
        for (UUID uuid : store.unnamed()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            if (op.getName() != null) {
                store.updateName(uuid, op.getName());
            }
        }
    }

    private void startDiscord() {
        if (!config.hasToken()) {
            getLogger().warning(messages.console("token-missing"));
            return;
        }
        discord.connect(config.token());
    }

    private void printMode() {
        getLogger().info(messages.console(config.forceBinding() ? "mode-force" : "mode-free"));
    }

    /** /dclink reload。 */
    public void reload(CommandSender sender) {
        String oldToken = discord.token();
        loadFiles();
        codes.configure(config.codeExpiryMinutes(), config.attemptWindowMinutes());
        api.refresh(true);
        printMode();
        messages.send(sender, "reload.done");
        if (!config.hasToken()) {
            if (!oldToken.isEmpty()) {
                discord.disconnectAsync();
            }
            getLogger().warning(messages.console("token-missing"));
        } else if (!config.token().equals(oldToken) || (!discord.online() && !discord.connecting())) {
            messages.send(sender, "reload.discord-restart");
            discord.connect(config.token());
        }
    }

    public PluginConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public LinkStore store() {
        return store;
    }

    public PlayerLookup lookup() {
        return lookup;
    }

    public CodeManager codes() {
        return codes;
    }

    public DiscordBot discord() {
        return discord;
    }

    public UpdateChecker updates() {
        return updates;
    }

    public UpdateNotifier updateNotifier() {
        return updateNotifier;
    }
}
