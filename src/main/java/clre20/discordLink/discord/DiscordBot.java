package clre20.discordLink.discord;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.config.Messages;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Discord 機器人的連線與斷線，全部在單一背景執行緒進行，不會卡住伺服器啟動或 /dclink reload。
 * 使用 createLight（不快取成員），降低記憶體用量。
 */
public final class DiscordBot {

    private final DiscordLink plugin;
    private final DiscordListener listener;
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiscordLink-Discord");
        t.setDaemon(true);
        return t;
    });

    private volatile JDA jda;
    private volatile boolean ready;
    private volatile boolean connecting;
    private volatile String token = "";

    public DiscordBot(DiscordLink plugin, DiscordListener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    /** 連線（或換 Token 後重新連線）。 */
    public void connect(String newToken) {
        token = newToken;
        connecting = true;
        lifecycle.execute(() -> {
            disconnect();
            open(newToken);
        });
    }

    /** 斷線（Token 被清空時）。 */
    public void disconnectAsync() {
        token = "";
        lifecycle.execute(this::disconnect);
    }

    /** 正在連線中。 */
    public boolean connecting() {
        return connecting;
    }

    public String token() {
        return token;
    }

    /** 已連線且就緒時回傳 JDA，否則 null。 */
    public JDA jda() {
        return ready ? jda : null;
    }

    public boolean online() {
        return ready && jda != null;
    }

    /** 關服時呼叫（主執行緒）：等待連線執行緒結束並關閉連線，避免插件卸載後背景執行緒還在跑。 */
    public void shutdown() {
        lifecycle.shutdownNow();
        try {
            lifecycle.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        disconnect();
    }

    private void open(String t) {
        Messages msg = plugin.messages();
        plugin.getLogger().info(msg.console("discord-connecting"));
        JDA j = null;
        connecting = true;
        try {
            j = JDABuilder.createLight(t, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(listener)
                    .build();
            jda = j;
            j.awaitReady();
            ready = true;
            plugin.getLogger().info(msg.console("discord-ready", "name", j.getSelfUser().getName()));

            String verify = plugin.config().verifyChannelId();
            if (j.getTextChannelById(safeId(verify)) == null) {
                plugin.getLogger().warning(msg.console("verify-channel-missing", "id", verify));
            }
            j.updateCommands().addCommands(
                    Commands.slash("querydc", limit(msg.discord("slash.querydc-description")))
                            .addOption(OptionType.STRING, "player", limit(msg.discord("slash.querydc-option")), true)
                            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),
                    Commands.slash("querymc", limit(msg.discord("slash.querymc-description")))
                            .addOption(OptionType.STRING, "user", limit(msg.discord("slash.querymc-option")), true)
                            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
            ).queue(
                    ok -> plugin.getLogger().info(msg.console("slash-registered")),
                    err -> plugin.getLogger().warning(msg.console("slash-failed", "reason", err.getMessage()))
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close(j);
        } catch (Throwable e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            plugin.getLogger().severe(msg.console("discord-failed", "reason", reason));
            close(j);
        } finally {
            connecting = false;
        }
    }

    private void disconnect() {
        JDA j = jda;
        close(j);
    }

    private void close(JDA j) {
        ready = false;
        if (j == null) {
            return;
        }
        if (jda == j) {
            jda = null;
        }
        try {
            j.shutdown();
            if (!j.awaitShutdown(Duration.ofSeconds(5))) {
                j.shutdownNow();
                j.awaitShutdown(Duration.ofSeconds(2));
            }
        } catch (InterruptedException e) {
            j.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        }
    }

    /** 非數字 ID 會讓 JDA 丟出例外，先換成 0。 */
    static String safeId(String id) {
        return id != null && id.matches("\\d{1,20}") ? id : "0";
    }

    /** Discord 指令說明最多 100 字。 */
    private static String limit(String s) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        return s.length() > 100 ? s.substring(0, 100) : s;
    }
}
