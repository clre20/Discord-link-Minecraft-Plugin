package clre20.discordLink.data;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * 用名稱找玩家 UUID，不會卡住主執行緒：
 * 先查本插件資料、線上玩家、伺服器快取；都沒有才在背景向 Mojang 查詢（單一執行緒）。
 */
public final class PlayerLookup {

    /** Java 版名稱；開頭允許一個非英數字元（例如 Floodgate 的 . 前綴）。 */
    private static final Pattern NAME = Pattern.compile("[.*~!+-]?[A-Za-z0-9_]{1,16}");

    private final Plugin plugin;
    private final LinkStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiscordLink-Lookup");
        t.setDaemon(true);
        return t;
    });

    public PlayerLookup(Plugin plugin, LinkStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * 在主執行緒呼叫；結果也在主執行緒回呼。找不到時 uuid 為 null。
     *
     * @return true 表示需要等待網路查詢
     */
    public boolean lookup(String name, BiConsumer<UUID, String> callback) {
        UUID known = store.findByName(name);
        if (known != null) {
            String n = store.nameOf(known);
            callback.accept(known, n == null ? name : n);
            return false;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            callback.accept(online.getUniqueId(), online.getName());
            return false;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            callback.accept(cached.getUniqueId(), cached.getName() == null ? name : cached.getName());
            return false;
        }
        executor.execute(() -> {
            UUID id = null;
            String resolved = name;
            try {
                PlayerProfile profile = Bukkit.createProfile(name);
                if (profile.complete(false) && profile.getId() != null) {
                    id = profile.getId();
                    if (profile.getName() != null) {
                        resolved = profile.getName();
                    }
                }
            } catch (Exception ignored) {
            }
            UUID finalId = id;
            String finalName = resolved;
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(finalId, finalName));
            }
        });
        return true;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
