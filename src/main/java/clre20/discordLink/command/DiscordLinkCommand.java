package clre20.discordLink.command;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.config.Messages;
import clre20.discordLink.config.PluginConfig;
import clre20.discordLink.data.LinkStore;
import clre20.discordLink.data.PlayerLookup;
import clre20.discordLink.update.UpdateNotifier;
import clre20.discordLink.util.Ids;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /discordlink（/dclink）。 */
public final class DiscordLinkCommand implements TabExecutor {

    public static final String PERM_HELP = "discordlink.help";
    public static final String PERM_USE = "discordlink.use";
    public static final String PERM_ADMIN = "discordlink.admin";

    private static final int PAGE_SIZE = 8;
    private static final int TAB_LIMIT = 50;

    private final DiscordLink plugin;

    public DiscordLinkCommand(DiscordLink plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "link" -> {
                if (check(sender, PERM_USE)) {
                    link(sender);
                }
            }
            case "version" -> {
                if (check(sender, UpdateNotifier.PERMISSION)) {
                    plugin.updateNotifier().showTo(sender);
                }
            }
            case "reload" -> {
                if (check(sender, PERM_ADMIN)) {
                    plugin.reload(sender);
                }
            }
            case "bypass" -> {
                if (check(sender, PERM_ADMIN)) {
                    bypass(sender, label, args);
                }
            }
            case "admin" -> {
                if (check(sender, PERM_ADMIN)) {
                    admin(sender, label, args);
                }
            }
            default -> {
                if (check(sender, PERM_HELP)) {
                    help(sender, label);
                }
            }
        }
        return true;
    }

    private boolean check(CommandSender sender, String perm) {
        if (sender.hasPermission(perm)) {
            return true;
        }
        msg().send(sender, "no-permission");
        return false;
    }

    private void help(CommandSender sender, String label) {
        msg().line(sender, "help.header", "version", plugin.getPluginMeta().getVersion());
        if (sender.hasPermission(PERM_USE)) {
            msg().line(sender, "help.link", "label", label);
        }
        if (sender.hasPermission(UpdateNotifier.PERMISSION)) {
            msg().line(sender, "help.version", "label", label);
        }
        if (sender.hasPermission(PERM_ADMIN)) {
            for (String k : new String[]{"query", "unbind", "list", "bypass", "reload"}) {
                msg().line(sender, "help." + k, "label", label);
            }
        }
    }

    // ---------- link ----------

    private void link(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "player-only");
            return;
        }
        UUID uuid = player.getUniqueId();
        LinkStore store = plugin.store();
        if (store.isBypassed(uuid)) {
            msg().send(sender, "link.bypassed");
            return;
        }
        if (store.isBound(uuid)) {
            msg().send(sender, "link.already");
            return;
        }
        String code = plugin.codes().generate(uuid, player.getName());
        msg().send(sender, "link.code", "code", code, "minutes", plugin.config().codeExpiryMinutes());
        if (!plugin.discord().online()) {
            msg().send(sender, "link.discord-offline");
        }
    }

    // ---------- bypass ----------

    private void bypass(CommandSender sender, String label, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        LinkStore store = plugin.store();
        if (action.equals("list")) {
            var list = store.bypassList();
            if (list.isEmpty()) {
                msg().send(sender, "bypass.list-empty");
                return;
            }
            msg().send(sender, "bypass.list-header", "count", list.size());
            for (LinkStore.Entry e : list) {
                msg().line(sender, "bypass.list-entry", "player", e.name().isEmpty() ? e.uuid() : e.name());
            }
            return;
        }
        if ((!action.equals("add") && !action.equals("remove")) || args.length < 3) {
            msg().send(sender, "usage.bypass", "label", label);
            return;
        }
        String name = args[2];
        if (action.equals("remove")) {
            UUID uuid = Ids.parseUuid(name);
            if (uuid == null) {
                uuid = store.findByName(name);
            }
            if (uuid != null && store.removeBypass(uuid)) {
                msg().send(sender, "bypass.removed", "player", name);
            } else {
                msg().send(sender, "bypass.not-in", "player", name);
            }
            return;
        }
        if (!PlayerLookup.validName(name)) {
            msg().send(sender, "lookup.invalid-name");
            return;
        }
        boolean waiting = plugin.lookup().lookup(name, (uuid, resolved) -> {
            if (uuid == null) {
                msg().send(sender, "lookup.not-found", "name", name);
            } else if (store.addBypass(uuid, resolved)) {
                msg().send(sender, "bypass.added", "player", resolved);
            } else {
                msg().send(sender, "bypass.already", "player", resolved);
            }
        });
        if (waiting) {
            msg().send(sender, "lookup.searching", "name", name);
        }
    }

    // ---------- admin ----------

    private void admin(CommandSender sender, String label, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "query", "unbind" -> {
                if (args.length < 3) {
                    msg().send(sender, "usage.target");
                    return;
                }
                if (action.equals("query")) {
                    query(sender, args[2]);
                } else {
                    unbind(sender, args[2]);
                }
            }
            case "list" -> list(sender, args.length >= 3 ? args[2] : "1");
            default -> msg().send(sender, "usage.admin", "label", label);
        }
    }

    private void query(CommandSender sender, String target) {
        LinkStore store = plugin.store();
        if (PluginConfig.isSnowflake(target)) {
            var list = store.boundTo(target);
            if (list.isEmpty()) {
                msg().send(sender, "admin.discord-unbound", "discord_id", target);
                return;
            }
            msg().send(sender, "admin.discord-header", "discord_id", target);
            for (LinkStore.Binding b : list) {
                msg().line(sender, "admin.discord-entry", "player", display(b), "uuid", b.uuid());
            }
            return;
        }
        LinkStore.Binding b = findBinding(target);
        if (b == null) {
            msg().send(sender, "admin.player-unbound", "player", target);
        } else {
            msg().send(sender, "admin.player-bound", "player", display(b), "discord_id", b.discordId());
        }
    }

    private void unbind(CommandSender sender, String target) {
        LinkStore store = plugin.store();
        if (PluginConfig.isSnowflake(target)) {
            int n = store.unbindDiscord(target);
            if (n == 0) {
                msg().send(sender, "admin.discord-unbound", "discord_id", target);
            } else {
                msg().send(sender, "admin.unbind-discord", "discord_id", target, "count", n);
            }
            return;
        }
        LinkStore.Binding b = findBinding(target);
        if (b == null || store.unbind(b.uuid()) == null) {
            msg().send(sender, "admin.player-unbound", "player", target);
        } else {
            msg().send(sender, "admin.unbind-player", "player", display(b));
        }
    }

    private void list(CommandSender sender, String pageArg) {
        List<LinkStore.Binding> all = plugin.store().allBindings();
        if (all.isEmpty()) {
            msg().send(sender, "admin.list-empty");
            return;
        }
        int pages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page;
        try {
            page = Integer.parseInt(pageArg);
        } catch (NumberFormatException e) {
            page = -1;
        }
        if (page < 1 || page > pages) {
            msg().send(sender, "admin.page-invalid", "pages", pages);
            return;
        }
        msg().send(sender, "admin.list-header", "page", page, "pages", pages, "total", all.size());
        int end = Math.min(page * PAGE_SIZE, all.size());
        for (int i = (page - 1) * PAGE_SIZE; i < end; i++) {
            LinkStore.Binding b = all.get(i);
            msg().line(sender, "admin.list-entry", "player", display(b), "uuid", b.uuid(), "discord_id", b.discordId());
        }
    }

    /** 名稱或 UUID → 綁定（只查已綁定的玩家，不查 Mojang）。 */
    private LinkStore.Binding findBinding(String target) {
        LinkStore store = plugin.store();
        UUID uuid = Ids.parseUuid(target);
        if (uuid == null) {
            uuid = store.findByName(target);
        }
        return uuid == null ? null : store.binding(uuid);
    }

    private String display(LinkStore.Binding b) {
        return b.name() == null || b.name().isEmpty() ? msg().raw("unknown-player") : b.name();
    }

    // ---------- Tab ----------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        boolean admin = sender.hasPermission(PERM_ADMIN);
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission(PERM_HELP)) {
                subs.add("help");
            }
            if (sender.hasPermission(PERM_USE)) {
                subs.add("link");
            }
            if (sender.hasPermission(UpdateNotifier.PERMISSION)) {
                subs.add("version");
            }
            if (admin) {
                subs.add("admin");
                subs.add("bypass");
                subs.add("reload");
            }
            return filter(subs, args[0]);
        }
        if (!admin) {
            return List.of();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("bypass")) {
                return filter(List.of("add", "remove", "list"), args[1]);
            }
            if (sub.equals("admin")) {
                return filter(List.of("query", "unbind", "list"), args[1]);
            }
            return List.of();
        }
        if (args.length == 3) {
            String action = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("bypass") && action.equals("add")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!plugin.store().isBypassed(p.getUniqueId())) {
                        names.add(p.getName());
                    }
                }
                return filter(names, args[2]);
            }
            if (sub.equals("bypass") && action.equals("remove")) {
                List<String> names = new ArrayList<>();
                plugin.store().bypassList().forEach(e -> {
                    if (!e.name().isEmpty()) {
                        names.add(e.name());
                    }
                });
                return filter(names, args[2]);
            }
            if (sub.equals("admin") && (action.equals("query") || action.equals("unbind"))) {
                List<String> names = new ArrayList<>();
                String prefix = args[2].toLowerCase(Locale.ROOT);
                for (LinkStore.Binding b : plugin.store().allBindings()) {
                    if (b.name() != null && b.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        names.add(b.name());
                        if (names.size() >= TAB_LIMIT) {
                            break;
                        }
                    }
                }
                return names;
            }
            if (sub.equals("admin") && action.equals("list")) {
                int pages = Math.max(1, (plugin.store().bindingCount() + PAGE_SIZE - 1) / PAGE_SIZE);
                List<String> nums = new ArrayList<>();
                for (int i = 1; i <= Math.min(pages, TAB_LIMIT); i++) {
                    nums.add(String.valueOf(i));
                }
                return filter(nums, args[2]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> list, String input) {
        String in = input.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase(Locale.ROOT).startsWith(in)) {
                out.add(s);
                if (out.size() >= TAB_LIMIT) {
                    break;
                }
            }
        }
        return out;
    }
}
