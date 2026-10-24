package clre20.discordLink.config;

import clre20.discordLink.DiscordLink;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * config.yml。讀取時全部轉成欄位，之後只讀欄位（任何執行緒都可以讀）。
 */
public final class PluginConfig {

    private static final Pattern DB_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}");
    private static final Pattern SNOWFLAKE = Pattern.compile("\\d{17,20}");

    private final DiscordLink plugin;

    private volatile String prefix = "";
    private volatile String dbFile = "database.db";
    private volatile String token = "";
    private volatile String verifyChannelId = "";
    private volatile boolean privateMessage = true;
    private volatile boolean adminLog = true;
    private volatile String adminLogChannelId = "";
    private volatile boolean forceBinding = true;
    private volatile int maxBindings = 1;
    private volatile int codeExpiryMinutes = 5;
    private volatile int maxFailedAttempts = 5;
    private volatile int attemptWindowMinutes = 10;
    private volatile boolean apiEnabled = true;
    private volatile String apiKey = "";
    private volatile boolean allowQueryKey = true;
    private volatile boolean updateCheckOnStartup = true;
    private volatile boolean updateNotifyOnJoin = true;

    /** 上次載入的結果（給後台訊息用）。 */
    private int lastFilled;
    private boolean migrated;
    private boolean hadOldApiPort;
    private boolean badDbName;
    private String generatedKey;
    private Map<String, Object> legacyMessages = Map.of();

    public PluginConfig(DiscordLink plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        migrated = false;
        hadOldApiPort = false;
        generatedKey = null;
        legacyMessages = Map.of();
        if (file.exists()) {
            YamlConfiguration raw = new YamlConfiguration();
            try {
                raw.load(file);
                if (!raw.contains("config-version")) {
                    legacyMessages = extractLegacyMessages(raw);
                    migrated = true;
                }
                hadOldApiPort = raw.contains("api.port");
            } catch (Exception ignored) {
                // 格式錯誤由 YamlFiles 備份並提示
            }
        }

        YamlFiles.Loaded loaded = YamlFiles.load(plugin, "config.yml", "config-version", PluginConfig::migrate);
        YamlConfiguration c = loaded.yaml();
        lastFilled = loaded.filled();

        String key = c.getString("api.key", "GENERATE");
        if (key == null || key.isBlank() || key.equalsIgnoreCase("GENERATE")) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            key = HexFormat.of().formatHex(bytes);
            c.set("api.key", key);
            try {
                c.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("config.yml: " + e.getMessage());
            }
            generatedKey = key;
        }

        prefix = c.getString("prefix", "<aqua>[DiscordLink]</aqua> ");
        String db = c.getString("database.sqlite-file-name", "database.db");
        badDbName = db == null || !DB_NAME.matcher(db).matches();
        dbFile = badDbName ? "database.db" : db;
        token = trim(c.getString("discord.token", ""));
        verifyChannelId = trim(c.getString("discord.verify-channel-id", ""));
        privateMessage = c.getBoolean("discord.private-message", true);
        adminLog = c.getBoolean("discord.admin-log.enabled", true);
        adminLogChannelId = trim(c.getString("discord.admin-log.channel-id", ""));
        forceBinding = c.getBoolean("verification.force-binding", true);
        maxBindings = clamp(c.getInt("verification.max-bindings-per-discord", 1), 1, 100);
        codeExpiryMinutes = clamp(c.getInt("verification.code-expiry-minutes", 5), 1, 60);
        maxFailedAttempts = clamp(c.getInt("verification.max-failed-attempts", 5), 1, 100);
        attemptWindowMinutes = clamp(c.getInt("verification.attempt-window-minutes", 10), 1, 1440);
        apiEnabled = c.getBoolean("api.enabled", true);
        apiKey = trim(key);
        allowQueryKey = c.getBoolean("api.allow-query-key", true);
        updateCheckOnStartup = c.getBoolean("update-checker.check-on-startup", true);
        updateNotifyOnJoin = c.getBoolean("update-checker.notify-on-join", true);
    }

    /** 舊版設定（沒有 config-version）搬到新位置。 */
    private static void migrate(YamlConfiguration old, YamlConfiguration merged) {
        move(old, merged, "discord-token", "discord.token");
        move(old, merged, "verify-channel-id", "discord.verify-channel-id");
        move(old, merged, "sqlite-file-name", "database.sqlite-file-name");
        move(old, merged, "max-bindings-per-discord", "verification.max-bindings-per-discord");
        move(old, merged, "notifications.private-message.enabled", "discord.private-message");
        move(old, merged, "embeds.private-message.enabled", "discord.private-message");
        move(old, merged, "notifications.admin-log.enabled", "discord.admin-log.enabled");
        move(old, merged, "notifications.admin-log.channel-id", "discord.admin-log.channel-id");
        move(old, merged, "embeds.admin-log.enabled", "discord.admin-log.enabled");
        move(old, merged, "embeds.admin-log.channel-id", "discord.admin-log.channel-id");
    }

    private static void move(YamlConfiguration old, YamlConfiguration merged, String from, String to) {
        if (old.contains(from, true) && !old.isConfigurationSection(from)) {
            merged.set(to, old.get(from));
        }
    }

    /** 舊版 config.yml 裡的顯示文字，之後搬到 messages.yml。 */
    private static Map<String, Object> extractLegacyMessages(YamlConfiguration raw) {
        Map<String, Object> out = new HashMap<>();
        copy(raw, out, "messages.join-reminder", "join-reminder");
        // 新版會自動加上 config 的 prefix，去掉舊訊息自帶的 [DiscordLink]
        if (out.get("join-reminder") instanceof String s) {
            out.put("join-reminder", s.replaceFirst("^\\s*(<[^>]+>)?\\[DiscordLink](</[^>]+>)?\\s*", ""));
        }
        for (String card : new String[]{"verify-success", "verify-fail", "verify-limit", "private-message", "admin-log"}) {
            for (String field : new String[]{"title", "description", "color"}) {
                copy(raw, out, "embeds." + card + "." + field, "discord." + card + "." + field);
            }
        }
        copy(raw, out, "notifications.private-message.message", "discord.private-message.description");
        copy(raw, out, "notifications.admin-log.message", "discord.admin-log.description");
        copy(raw, out, "embeds.verify-success.footer", "discord.footer");
        return out;
    }

    private static void copy(YamlConfiguration raw, Map<String, Object> out, String from, String to) {
        String v = raw.getString(from);
        if (v != null && !v.isEmpty()) {
            out.put(to, v);
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static boolean isSnowflake(String s) {
        return s != null && SNOWFLAKE.matcher(s).matches();
    }

    public String prefix() { return prefix; }
    public String dbFile() { return dbFile; }
    public String token() { return token; }
    public boolean hasToken() { return !token.isEmpty() && !token.equalsIgnoreCase("YOUR_DISCORD_BOT_TOKEN"); }
    public String verifyChannelId() { return verifyChannelId; }
    public boolean privateMessage() { return privateMessage; }
    public boolean adminLog() { return adminLog && isSnowflake(adminLogChannelId); }
    public String adminLogChannelId() { return adminLogChannelId; }
    public boolean forceBinding() { return forceBinding; }
    public int maxBindings() { return maxBindings; }
    public int codeExpiryMinutes() { return codeExpiryMinutes; }
    public int maxFailedAttempts() { return maxFailedAttempts; }
    public int attemptWindowMinutes() { return attemptWindowMinutes; }
    public boolean apiEnabled() { return apiEnabled; }
    public String apiKey() { return apiKey; }
    public boolean allowQueryKey() { return allowQueryKey; }
    public boolean updateCheckOnStartup() { return updateCheckOnStartup; }
    public boolean updateNotifyOnJoin() { return updateNotifyOnJoin; }

    public int lastFilled() { return lastFilled; }
    public boolean migrated() { return migrated; }
    public boolean hadOldApiPort() { return hadOldApiPort; }
    public boolean badDbName() { return badDbName; }
    public String generatedKey() { return generatedKey; }
    public Map<String, Object> legacyMessages() { return legacyMessages; }
}
