package clre20.discordLink.data;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 綁定與免驗證名單。
 * <ul>
 *   <li>全部資料放在記憶體，查詢不碰硬碟，任何執行緒都可以讀。</li>
 *   <li>修改先改記憶體，再交給單一資料庫執行緒依序寫入 SQLite（同一條連線）；關服時等待寫完。</li>
 * </ul>
 */
public final class LinkStore {

    public record Binding(UUID uuid, String discordId, String name) {
    }

    public record Entry(UUID uuid, String name) {
    }

    public enum BindResult { OK, LIMIT, ALREADY_BOUND }

    private final File file;
    private final Consumer<String> onError;
    private final ExecutorService db = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiscordLink-Database");
        t.setDaemon(true);
        return t;
    });
    /** 只在資料庫執行緒使用。 */
    private Connection conn;

    private final Map<UUID, Binding> bindings = new ConcurrentHashMap<>();
    /** Discord ID → 綁定的 UUID（值為不可變集合，整份替換）。 */
    private final Map<String, Set<UUID>> byDiscord = new ConcurrentHashMap<>();
    /** 免驗證：UUID → 名稱（不知道時為空字串）。 */
    private final Map<UUID, String> bypass = new ConcurrentHashMap<>();
    /** 小寫名稱 → UUID（綁定與免驗證的玩家）。 */
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();

    public LinkStore(File file, Consumer<String> onError) {
        this.file = file;
        this.onError = onError;
    }

    /** 開啟資料庫並載入全部資料（在資料庫執行緒執行，這裡等待完成）。 */
    public void load() throws Exception {
        Future<?> f = db.submit(() -> {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("CREATE TABLE IF NOT EXISTS bindings (uuid VARCHAR(36) PRIMARY KEY, dc_id VARCHAR(20))");
                st.execute("CREATE TABLE IF NOT EXISTS bypass_list (uuid VARCHAR(36) PRIMARY KEY)");
            }
            addColumnIfMissing("bindings", "name");
            addColumnIfMissing("bypass_list", "name");

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, dc_id, name FROM bindings")) {
                while (rs.next()) {
                    UUID uuid = parse(rs.getString(1));
                    String dc = rs.getString(2);
                    if (uuid == null || dc == null) {
                        continue;
                    }
                    putBinding(new Binding(uuid, dc, rs.getString(3)));
                }
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, name FROM bypass_list")) {
                while (rs.next()) {
                    UUID uuid = parse(rs.getString(1));
                    if (uuid != null) {
                        String name = rs.getString(2);
                        bypass.put(uuid, name == null ? "" : name);
                        index(name, uuid);
                    }
                }
            }
            return null;
        });
        try {
            f.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    private void addColumnIfMissing(String table, String column) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " VARCHAR(16)");
        }
    }

    /** 等待寫完並關閉連線。 */
    public void close() {
        db.submit(() -> {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        });
        db.shutdown();
        try {
            if (!db.awaitTermination(15, TimeUnit.SECONDS)) {
                db.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 查詢（任何執行緒） ----------

    public boolean isBound(UUID uuid) {
        return bindings.containsKey(uuid);
    }

    public boolean isBypassed(UUID uuid) {
        return bypass.containsKey(uuid);
    }

    public Binding binding(UUID uuid) {
        return bindings.get(uuid);
    }

    public List<Binding> boundTo(String discordId) {
        Set<UUID> set = byDiscord.get(discordId);
        if (set == null) {
            return List.of();
        }
        List<Binding> out = new ArrayList<>(set.size());
        for (UUID u : set) {
            Binding b = bindings.get(u);
            if (b != null) {
                out.add(b);
            }
        }
        out.sort(Comparator.comparing(b -> b.name() == null ? "" : b.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    public int bindingCount() {
        return bindings.size();
    }

    /** 所有綁定（依名稱排序的複本）。 */
    public List<Binding> allBindings() {
        List<Binding> out = new ArrayList<>(bindings.values());
        out.sort(Comparator.comparing(b -> b.name() == null ? "~" : b.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    public List<Entry> bypassList() {
        List<Entry> out = new ArrayList<>(bypass.size());
        bypass.forEach((u, n) -> out.add(new Entry(u, n)));
        out.sort(Comparator.comparing(e -> e.name().isEmpty() ? "~" : e.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    public int bypassCount() {
        return bypass.size();
    }

    /** 用名稱找已綁定或免驗證的玩家（不分大小寫），找不到回傳 null。 */
    public UUID findByName(String name) {
        return name == null ? null : nameIndex.get(name.toLowerCase(Locale.ROOT));
    }

    /** 已知的名稱，沒有時回傳 null。 */
    public String nameOf(UUID uuid) {
        Binding b = bindings.get(uuid);
        if (b != null && b.name() != null && !b.name().isEmpty()) {
            return b.name();
        }
        String n = bypass.get(uuid);
        return n == null || n.isEmpty() ? null : n;
    }

    /** 已綁定或免驗證、但還不知道名稱的 UUID。 */
    public Set<UUID> unnamed() {
        Set<UUID> out = new HashSet<>();
        bindings.values().forEach(b -> {
            if (b.name() == null || b.name().isEmpty()) {
                out.add(b.uuid());
            }
        });
        bypass.forEach((u, n) -> {
            if (n.isEmpty()) {
                out.add(u);
            }
        });
        return out;
    }

    // ---------- 修改 ----------

    public synchronized BindResult tryBind(UUID uuid, String discordId, String name, int max) {
        if (bindings.containsKey(uuid)) {
            return BindResult.ALREADY_BOUND;
        }
        Set<UUID> set = byDiscord.get(discordId);
        if (set != null && set.size() >= max) {
            return BindResult.LIMIT;
        }
        putBinding(new Binding(uuid, discordId, name));
        write("INSERT OR REPLACE INTO bindings (uuid, dc_id, name) VALUES (?, ?, ?)", uuid.toString(), discordId, name);
        return BindResult.OK;
    }

    /** 解除一個玩家的綁定，回傳原本的綁定（沒有時為 null）。 */
    public synchronized Binding unbind(UUID uuid) {
        Binding b = bindings.remove(uuid);
        if (b == null) {
            return null;
        }
        Set<UUID> set = byDiscord.get(b.discordId());
        if (set != null) {
            Set<UUID> next = new HashSet<>(set);
            next.remove(uuid);
            if (next.isEmpty()) {
                byDiscord.remove(b.discordId());
            } else {
                byDiscord.put(b.discordId(), Set.copyOf(next));
            }
        }
        if (!bypass.containsKey(uuid)) {
            unindex(b.name(), uuid);
        }
        write("DELETE FROM bindings WHERE uuid = ?", uuid.toString());
        return b;
    }

    /** 解除一個 Discord 帳號的所有綁定，回傳解除數量。 */
    public synchronized int unbindDiscord(String discordId) {
        Set<UUID> set = byDiscord.remove(discordId);
        if (set == null) {
            return 0;
        }
        for (UUID u : set) {
            Binding b = bindings.remove(u);
            if (b != null && !bypass.containsKey(u)) {
                unindex(b.name(), u);
            }
        }
        write("DELETE FROM bindings WHERE dc_id = ?", discordId);
        return set.size();
    }

    public synchronized boolean addBypass(UUID uuid, String name) {
        String n = name == null ? "" : name;
        if (bypass.containsKey(uuid)) {
            return false;
        }
        bypass.put(uuid, n);
        index(n, uuid);
        write("INSERT OR REPLACE INTO bypass_list (uuid, name) VALUES (?, ?)", uuid.toString(), n);
        return true;
    }

    public synchronized boolean removeBypass(UUID uuid) {
        String n = bypass.remove(uuid);
        if (n == null) {
            return false;
        }
        Binding b = bindings.get(uuid);
        if (b == null || !n.equalsIgnoreCase(b.name())) {
            unindex(n, uuid);
        }
        write("DELETE FROM bypass_list WHERE uuid = ?", uuid.toString());
        return true;
    }

    /** 玩家改名時更新（只有綁定或免驗證的玩家，名稱有變才寫入）。 */
    public synchronized void updateName(UUID uuid, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        Binding b = bindings.get(uuid);
        if (b != null && !name.equals(b.name())) {
            unindex(b.name(), uuid);
            bindings.put(uuid, new Binding(uuid, b.discordId(), name));
            index(name, uuid);
            write("UPDATE bindings SET name = ? WHERE uuid = ?", name, uuid.toString());
        }
        String old = bypass.get(uuid);
        if (old != null && !name.equals(old)) {
            unindex(old, uuid);
            bypass.put(uuid, name);
            index(name, uuid);
            write("UPDATE bypass_list SET name = ? WHERE uuid = ?", name, uuid.toString());
        }
    }

    // ---------- 內部 ----------

    private void putBinding(Binding b) {
        bindings.put(b.uuid(), b);
        Set<UUID> set = byDiscord.get(b.discordId());
        Set<UUID> next = set == null ? new HashSet<>() : new HashSet<>(set);
        next.add(b.uuid());
        byDiscord.put(b.discordId(), Set.copyOf(next));
        index(b.name(), b.uuid());
    }

    private void index(String name, UUID uuid) {
        if (name != null && !name.isEmpty()) {
            nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
        }
    }

    private void unindex(String name, UUID uuid) {
        if (name != null && !name.isEmpty()) {
            nameIndex.remove(name.toLowerCase(Locale.ROOT), uuid);
        }
    }

    private void write(String sql, String... params) {
        try {
            db.execute(() -> {
            if (conn == null) {
                return;
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setString(i + 1, params[i]);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                onError.accept(e.getMessage());
            }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            onError.accept("database closed");
        }
    }

    private static UUID parse(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
