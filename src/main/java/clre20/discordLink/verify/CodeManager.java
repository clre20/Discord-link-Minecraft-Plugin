package clre20.discordLink.verify;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 5 位數綁定代碼與輸錯次數限制。
 * 代碼與次數都放在有上限、會自動過期的快取裡，不會無限增長。
 */
public final class CodeManager {

    /** 一個代碼對應的玩家。 */
    public record Pending(UUID uuid, String name) {
    }

    private static final int MAX_PENDING = 20_000;
    private static final int MAX_TRACKED_USERS = 10_000;

    private final SecureRandom random = new SecureRandom();
    private Cache<String, Pending> byCode;
    private Cache<UUID, String> byPlayer;
    private Cache<String, AtomicInteger> failures;
    private int expiryMinutes = -1;
    private int windowMinutes = -1;

    public CodeManager(int expiryMinutes, int windowMinutes) {
        configure(expiryMinutes, windowMinutes);
    }

    /** 重新載入時呼叫；時間有變才重建快取（會清掉現有代碼）。 */
    public synchronized void configure(int expiryMinutes, int windowMinutes) {
        if (expiryMinutes != this.expiryMinutes) {
            this.expiryMinutes = expiryMinutes;
            byCode = CacheBuilder.newBuilder().maximumSize(MAX_PENDING)
                    .expireAfterWrite(expiryMinutes, TimeUnit.MINUTES).build();
            byPlayer = CacheBuilder.newBuilder().maximumSize(MAX_PENDING)
                    .expireAfterWrite(expiryMinutes, TimeUnit.MINUTES).build();
        }
        if (windowMinutes != this.windowMinutes) {
            this.windowMinutes = windowMinutes;
            failures = CacheBuilder.newBuilder().maximumSize(MAX_TRACKED_USERS)
                    .expireAfterWrite(windowMinutes, TimeUnit.MINUTES).build();
        }
    }

    /** 產生新代碼（舊代碼作廢）。 */
    public synchronized String generate(UUID uuid, String name) {
        String old = byPlayer.getIfPresent(uuid);
        if (old != null) {
            byCode.invalidate(old);
        }
        String code;
        int tries = 0;
        do {
            code = String.format("%05d", random.nextInt(100_000));
        } while (byCode.getIfPresent(code) != null && ++tries < 50);
        byCode.put(code, new Pending(uuid, name));
        byPlayer.put(uuid, code);
        return code;
    }

    /** 查看代碼對應的玩家（不作廢）；無效時回傳 null。 */
    public synchronized Pending peek(String code) {
        return byCode.getIfPresent(code);
    }

    /** 取出並作廢代碼；無效時回傳 null。 */
    public synchronized Pending consume(String code) {
        Pending p = byCode.getIfPresent(code);
        if (p != null) {
            byCode.invalidate(code);
            byPlayer.invalidate(p.uuid());
        }
        return p;
    }

    /** 這個 Discord 帳號是否已因輸錯太多次而暫停。 */
    public synchronized boolean locked(String discordId, int max) {
        AtomicInteger n = failures.getIfPresent(discordId);
        return n != null && n.get() >= max;
    }

    /** 記錄一次輸錯，回傳目前次數。 */
    public synchronized int fail(String discordId) {
        AtomicInteger n = failures.getIfPresent(discordId);
        if (n == null) {
            n = new AtomicInteger();
            failures.put(discordId, n);
        }
        return n.incrementAndGet();
    }

    public synchronized void clearFailures(String discordId) {
        failures.invalidate(discordId);
    }

    public synchronized void clear() {
        byCode.invalidateAll();
        byPlayer.invalidateAll();
        failures.invalidateAll();
    }
}
