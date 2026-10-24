package clre20.discordLink.api;

import clre20.httpHub.api.HttpHubService;
import clre20.httpHub.api.Response;
import clre20.httpHub.api.Route;
import org.bukkit.plugin.Plugin;

/**
 * 透過 HttpHub 註冊 /discordlink/api/query。
 * 只有在 HttpHub 啟用時才會載入這個類別（沒裝 HttpHub 時不會 NoClassDefFoundError）。
 */
final class HubApi {

    static final String PATH = "api/query";

    private final Route route;
    private final int port;

    private HubApi(Route route, int port) {
        this.route = route;
        this.port = port;
    }

    /** 註冊路徑；HttpHub 服務不存在時回傳 null。 */
    static HubApi register(Plugin plugin, ApiService service) {
        HttpHubService hub = HttpHubService.get();
        if (hub == null) {
            return null;
        }
        Route route = hub.handle(plugin, PATH, req -> {
            ApiService.Result r = service.handle(req.method(), req::query, req::header);
            return Response.json(r.status(), r.json())
                    .header("Access-Control-Allow-Origin", "*")
                    .header("Cache-Control", "no-store");
        });
        return new HubApi(route, hub.port());
    }

    int port() {
        return port;
    }

    void remove() {
        if (route.active()) {
            route.remove();
        }
    }
}
