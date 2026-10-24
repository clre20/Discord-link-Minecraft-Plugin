package clre20.discordLink.discord;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.config.Messages;
import clre20.discordLink.config.PluginConfig;
import clre20.discordLink.data.LinkStore;
import clre20.discordLink.util.Ids;
import clre20.discordLink.verify.CodeManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** 驗證頻道的代碼與管理員斜線指令。在 JDA 的執行緒上執行，只讀寫記憶體資料。 */
public final class DiscordListener extends ListenerAdapter {

    private static final Pattern CODE = Pattern.compile("\\d{5}");
    private static final Pattern MENTION = Pattern.compile("<@!?(\\d{17,20})>");
    private static final int MAX_REPLY = 1900;

    private final DiscordLink plugin;

    public DiscordListener(DiscordLink plugin) {
        this.plugin = plugin;
    }

    // ---------- 驗證頻道 ----------

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        User author = event.getAuthor();
        if (author.isBot() || event.isWebhookMessage() || !event.isFromGuild()) {
            return;
        }
        PluginConfig config = plugin.config();
        if (!event.getChannel().getId().equals(config.verifyChannelId())) {
            return;
        }
        String text = event.getMessage().getContentRaw().trim();
        if (!CODE.matcher(text).matches()) {
            return;
        }
        CodeManager codes = plugin.codes();
        String discordId = author.getId();
        int maxAttempts = config.maxFailedAttempts();
        if (codes.locked(discordId, maxAttempts)) {
            return; // 暫停期間不回應，避免洗頻
        }

        CodeManager.Pending pending = codes.peek(text);
        if (pending == null) {
            int n = codes.fail(discordId);
            if (n >= maxAttempts) {
                reply(event, embed("too-many-attempts", Color.ORANGE,
                        "user", author.getAsMention(), "minutes", config.attemptWindowMinutes()));
            } else {
                reply(event, embed("verify-fail", Color.RED));
            }
            return;
        }

        String name = pending.name() == null || pending.name().isEmpty()
                ? plugin.messages().raw("unknown-player") : pending.name();
        LinkStore.BindResult result = plugin.store().tryBind(pending.uuid(), discordId, pending.name(), config.maxBindings());
        switch (result) {
            case LIMIT -> reply(event, embed("verify-limit", Color.ORANGE, "max", config.maxBindings()));
            case ALREADY_BOUND -> {
                codes.consume(text);
                reply(event, embed("verify-fail", Color.RED));
            }
            case OK -> {
                codes.consume(text);
                codes.clearFailures(discordId);
                onBound(event, author, pending.uuid(), name);
            }
        }
    }

    private void onBound(MessageReceivedEvent event, User author, UUID uuid, String name) {
        Messages msg = plugin.messages();
        PluginConfig config = plugin.config();
        String safeName = MarkdownSanitizer.escape(name);
        String avatar = "https://mc-heads.net/avatar/" + uuid.toString().replace("-", "") + "/128";

        reply(event, embed("verify-success", Color.GREEN, avatar,
                "player", safeName, "uuid", uuid.toString().substring(0, 8)));

        if (config.privateMessage()) {
            MessageEmbed dm = embed("private-message", Color.GREEN, avatar, "player", safeName);
            author.openPrivateChannel()
                    .flatMap(ch -> ch.sendMessageEmbeds(dm))
                    .queue(ok -> { },
                            err -> plugin.getLogger().warning(msg.console("dm-failed", "user", author.getName())));
        }

        if (config.adminLog()) {
            TextChannel log = event.getJDA().getTextChannelById(config.adminLogChannelId());
            if (log != null) {
                log.sendMessageEmbeds(embed("admin-log", new Color(52, 152, 219), avatar,
                        "discord_id", author.getId(), "player", safeName, "uuid", uuid.toString())).queue();
            } else {
                plugin.getLogger().warning(msg.console("log-channel-missing", "id", config.adminLogChannelId()));
            }
        }

        // 自由綁定模式：玩家在線上時告知結果
        String discordName = author.getName();
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                msg.send(p, "link.success", "discord_name", discordName);
            }
        });
    }

    private static void reply(MessageReceivedEvent event, MessageEmbed embed) {
        event.getChannel().sendMessageEmbeds(embed).queue();
    }

    private MessageEmbed embed(String card, Color fallback, Object... vars) {
        return embed(card, fallback, null, vars);
    }

    private MessageEmbed embed(String card, Color fallback, String thumbnail, Object... vars) {
        Messages msg = plugin.messages();
        EmbedBuilder b = new EmbedBuilder()
                .setTitle(cut(msg.discord(card + ".title", vars), MessageEmbed.TITLE_MAX_LENGTH))
                .setDescription(cut(msg.discord(card + ".description", vars), MessageEmbed.DESCRIPTION_MAX_LENGTH))
                .setColor(msg.discordColor(card, fallback))
                .setTimestamp(Instant.now());
        String footer = msg.discord("footer");
        if (!footer.isBlank()) {
            b.setFooter(cut(footer, MessageEmbed.TEXT_MAX_LENGTH));
        }
        if (thumbnail != null) {
            b.setThumbnail(thumbnail);
        }
        return b.build();
    }

    // ---------- 斜線指令 ----------

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        String name = event.getName();
        if (!name.equals("querydc") && !name.equals("querymc")) {
            return;
        }
        Messages msg = plugin.messages();
        if (!event.isFromGuild()) {
            event.reply(msg.discord("slash.guild-only")).setEphemeral(true).queue();
            return;
        }
        Member member = event.getMember();
        if (member == null || !(member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_SERVER))) {
            event.reply(msg.discord("slash.no-permission")).setEphemeral(true).queue();
            return;
        }
        String reply = name.equals("querydc")
                ? queryDc(option(event, "player"))
                : queryMc(option(event, "user"));
        event.reply(cut(reply, MAX_REPLY)).setEphemeral(true).queue();
    }

    private static String option(SlashCommandInteractionEvent event, String key) {
        OptionMapping o = event.getOption(key);
        return o == null ? "" : o.getAsString().trim();
    }

    private String queryDc(String input) {
        Messages msg = plugin.messages();
        LinkStore store = plugin.store();
        UUID uuid = Ids.parseUuid(input);
        if (uuid == null && input.length() <= 17) {
            uuid = store.findByName(input);
        }
        LinkStore.Binding b = uuid == null ? null : store.binding(uuid);
        if (b == null) {
            return msg.discord("slash.player-unbound", "player", MarkdownSanitizer.escape(cut(input, 40)));
        }
        return msg.discord("slash.player-bound", "player", MarkdownSanitizer.escape(display(b)), "discord_id", b.discordId());
    }

    private String queryMc(String input) {
        Messages msg = plugin.messages();
        String id = input;
        var m = MENTION.matcher(input);
        if (m.matches()) {
            id = m.group(1);
        }
        if (!PluginConfig.isSnowflake(id)) {
            return msg.discord("slash.invalid-id");
        }
        List<LinkStore.Binding> list = plugin.store().boundTo(id);
        if (list.isEmpty()) {
            return msg.discord("slash.discord-unbound", "discord_id", id);
        }
        StringBuilder sb = new StringBuilder(msg.discord("slash.discord-header", "discord_id", id));
        for (LinkStore.Binding b : list) {
            sb.append('\n').append(msg.discord("slash.discord-entry",
                    "player", MarkdownSanitizer.escape(display(b)), "uuid", b.uuid()));
        }
        return sb.toString();
    }

    private String display(LinkStore.Binding b) {
        return b.name() == null || b.name().isEmpty() ? plugin.messages().raw("unknown-player") : b.name();
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
