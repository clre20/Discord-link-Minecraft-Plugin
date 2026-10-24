package clre20.discordLink.api;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.config.PluginConfig;
import clre20.discordLink.data.LinkStore;
import clre20.discordLink.util.Ids;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * HTTP API 的處理邏輯（不引用 HttpHub 的類別，沒裝 HttpHub 也能載入）。
 * 只讀記憶體裡的資料，不呼叫 Bukkit 查玩家，不會卡住 HTTP 執行緒或主執行緒。
 */
public final class ApiService {

    public record Result(int status, String json) {
    }

    private final DiscordLink plugin;

    public ApiService(DiscordLink plugin) {
        this.plugin = plugin;
    }

    /**
     * @param query  取得查詢參數
     * @param header 取得請求標頭
     */
    public Result handle(String method, Function<String, String> query, Function<String, String> header) {
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return error(405, "method");
        }
        PluginConfig config = plugin.config();
        String expected = config.apiKey();
        if (expected.isEmpty()) {
            return error(503, "disabled");
        }
        if (!keyMatches(expected, requestKey(query, header, config.allowQueryKey()))) {
            return error(401, "unauthorized");
        }

        String action = query.apply("action");
        String target = query.apply("target");
        if (action == null || action.isEmpty()) {
            return error(400, "missing-action");
        }
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "query_dc" -> target == null || target.isBlank() ? error(400, "missing-target") : queryDc(target.trim());
            case "query_mc" -> target == null || target.isBlank() ? error(400, "missing-target") : queryMc(target.trim());
            case "all" -> all();
            default -> error(400, "unknown-action");
        };
    }

    private Result queryDc(String target) {
        LinkStore store = plugin.store();
        UUID uuid = Ids.parseUuid(target);
        if (uuid == null) {
            uuid = target.length() <= 17 ? store.findByName(target) : null;
        }
        LinkStore.Binding b = uuid == null ? null : store.binding(uuid);
        if (b == null) {
            JsonObject o = success();
            o.addProperty("bound", false);
            o.addProperty("message", text("player-unbound"));
            return ok(o);
        }
        JsonObject data = entry(b);
        data.addProperty("discord_id", b.discordId());
        JsonObject o = success();
        o.addProperty("bound", true);
        o.add("data", data);
        return ok(o);
    }

    private Result queryMc(String target) {
        if (!PluginConfig.isSnowflake(target)) {
            return error(400, "invalid-discord-id");
        }
        var list = plugin.store().boundTo(target);
        JsonObject o = success();
        if (list.isEmpty()) {
            o.addProperty("bound", false);
            o.addProperty("message", text("discord-unbound"));
            return ok(o);
        }
        JsonArray arr = new JsonArray();
        list.forEach(b -> arr.add(entry(b)));
        o.addProperty("bound", true);
        o.add("data", arr);
        return ok(o);
    }

    private Result all() {
        var list = plugin.store().allBindings();
        JsonArray arr = new JsonArray();
        for (LinkStore.Binding b : list) {
            JsonObject e = entry(b);
            e.addProperty("discord_id", b.discordId());
            arr.add(e);
        }
        JsonObject o = success();
        o.addProperty("total", list.size());
        o.add("data", arr);
        return ok(o);
    }

    private JsonObject entry(LinkStore.Binding b) {
        JsonObject e = new JsonObject();
        e.addProperty("player", b.name() == null || b.name().isEmpty() ? plugin.messages().raw("unknown-player") : b.name());
        e.addProperty("uuid", b.uuid().toString());
        return e;
    }

    private static String requestKey(Function<String, String> query, Function<String, String> header, boolean allowQuery) {
        String auth = header.apply("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return allowQuery ? query.apply("key") : null;
    }

    /** 固定時間比對，避免從回應時間猜出金鑰。 */
    static boolean keyMatches(String expected, String given) {
        if (given == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    private String text(String key) {
        return plugin.messages().raw("http." + key);
    }

    private static JsonObject success() {
        JsonObject o = new JsonObject();
        o.addProperty("status", "success");
        return o;
    }

    private static Result ok(JsonObject o) {
        return new Result(200, o.toString());
    }

    private Result error(int status, String key) {
        JsonObject o = new JsonObject();
        o.addProperty("status", "error");
        o.addProperty("message", text(key));
        return new Result(status, o.toString());
    }
}
