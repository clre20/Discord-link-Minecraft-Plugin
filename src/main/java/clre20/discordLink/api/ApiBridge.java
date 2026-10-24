package clre20.discordLink.api;

import clre20.discordLink.DiscordLink;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * 管理 HTTP API 的註冊：啟動、重新載入、HttpHub 在伺服器運作中被啟用或停用時自動切換。
 */
public final class ApiBridge implements Listener {

    private static final String HUB = "HttpHub";

    private final DiscordLink plugin;
    private final ApiService service;
    private HubApi active;

    public ApiBridge(DiscordLink plugin) {
        this.plugin = plugin;
        this.service = new ApiService(plugin);
    }

    /** 依目前設定註冊或移除（主執行緒）。 */
    public void refresh(boolean announce) {
        boolean want = plugin.config().apiEnabled();
        boolean hubOn = plugin.getServer().getPluginManager().isPluginEnabled(HUB);
        if (!want) {
            stop();
            return;
        }
        if (!hubOn) {
            stop();
            if (announce) {
                plugin.getLogger().info(plugin.messages().console("api-no-httphub"));
            }
            return;
        }
        if (active == null) {
            active = HubApi.register(plugin, service);
            if (active != null) {
                plugin.getLogger().info(plugin.messages().console("api-registered", "port", active.port()));
            }
        }
    }

    public void stop() {
        if (active != null) {
            try {
                active.remove();
            } catch (Throwable ignored) {
            }
            active = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnable(PluginEnableEvent event) {
        if (HUB.equals(event.getPlugin().getName())) {
            refresh(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisable(PluginDisableEvent event) {
        if (HUB.equals(event.getPlugin().getName()) && active != null) {
            active = null; // HttpHub 停用時會自己移除所有路徑
            plugin.getLogger().info(plugin.messages().console("api-removed"));
        }
    }
}
