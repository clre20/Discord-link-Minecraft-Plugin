package clre20.discordLink.listener;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.data.LinkStore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/** 登入檢查與提醒。只查記憶體資料，不碰資料庫。 */
public final class LoginListener implements Listener {

    private final DiscordLink plugin;

    public LoginListener(DiscordLink plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        // 已被其他插件（封禁、白名單）擋下的連線不產生代碼
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        if (!plugin.config().forceBinding()) {
            return;
        }
        UUID uuid = event.getUniqueId();
        LinkStore store = plugin.store();
        if (store.isBypassed(uuid) || store.isBound(uuid)) {
            return;
        }
        String code = plugin.codes().generate(uuid, event.getName());
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                plugin.messages().component("kick", "code", code, "minutes", plugin.config().codeExpiryMinutes()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        LinkStore store = plugin.store();
        store.updateName(uuid, player.getName());
        if (store.isBypassed(uuid) || store.isBound(uuid)) {
            return;
        }
        String reminder = plugin.messages().raw("join-reminder");
        if (!reminder.isBlank()) {
            plugin.messages().send(player, "join-reminder");
        }
    }
}
