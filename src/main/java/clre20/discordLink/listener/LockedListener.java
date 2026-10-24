package clre20.discordLink.listener;

import clre20.discordLink.DiscordLink;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 資料庫無法讀取時使用：強制綁定模式下只讓 OP 進服，避免未驗證的玩家趁機進入。 */
public final class LockedListener implements Listener {

    private final DiscordLink plugin;
    /** OP 名單在主執行緒先取一份，登入檢查（非同步）只讀這份。 */
    private final Set<UUID> operators = new HashSet<>();

    public LockedListener(DiscordLink plugin) {
        this.plugin = plugin;
        Bukkit.getOperators().forEach(op -> operators.add(op.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        if (operators.contains(event.getUniqueId())) {
            return;
        }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.messages().component("locked-kick"));
    }
}
