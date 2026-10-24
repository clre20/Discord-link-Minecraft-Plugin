package clre20.discordLink.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * 讀取插件資料夾裡的 yml 並自動補齊。
 * 有缺少的項目、多出新版不用的項目、或版本號不同時：以內建預設檔（含註解）為底，填回使用者的值後寫回。
 */
final class YamlFiles {

    record Loaded(YamlConfiguration yaml, YamlConfiguration defaults, int filled) {
    }

    private YamlFiles() {
    }

    static YamlConfiguration defaults(JavaPlugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    /**
     * @param versionKey 版本號的鍵（例如 config-version）
     * @param migrate    寫回前把舊設定搬到新位置（使用者設定、合併後設定），可為 null
     */
    static Loaded load(JavaPlugin plugin, String name, String versionKey,
                       BiConsumer<YamlConfiguration, YamlConfiguration> migrate) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration defaults = defaults(plugin, name);
        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            // 格式壞掉：先備份原檔，避免之後寫回時蓋掉使用者的內容
            File broken = new File(plugin.getDataFolder(),
                    name + ".broken-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
            try {
                Files.copy(file.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            plugin.getLogger().warning(name + " 格式錯誤，已備份為 " + broken.getName() + " 並改用預設值：" + e.getMessage());
            user = new YamlConfiguration();
        }

        int missing = 0;
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && !user.contains(key, true)) {
                missing++;
            }
        }
        boolean obsolete = false;
        for (String key : user.getKeys(true)) {
            if (!user.isConfigurationSection(key) && !defaults.contains(key, true)) {
                obsolete = true;
                break;
            }
        }
        boolean versionChanged = user.getInt(versionKey, -1) != defaults.getInt(versionKey, -1);

        if (missing > 0 || obsolete || versionChanged) {
            YamlConfiguration merged = defaults(plugin, name);
            for (String key : user.getKeys(true)) {
                if (user.isConfigurationSection(key) || key.equals(versionKey)) {
                    continue;
                }
                if (!merged.contains(key, true) || merged.isConfigurationSection(key)) {
                    continue; // 新版不用的舊項目不寫回
                }
                merged.set(key, user.get(key));
            }
            if (migrate != null) {
                migrate.accept(user, merged);
            }
            try {
                if (versionChanged && file.exists()) {
                    Files.copy(file.toPath(), new File(plugin.getDataFolder(), name + ".bak").toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                merged.save(file);
                user = merged;
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, name + ": " + e.getMessage());
                missing = 0;
            }
        }
        user.setDefaults(defaults);
        return new Loaded(user, defaults, missing);
    }
}
