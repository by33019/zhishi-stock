package cn.zhishi.stock.system.idempotency;

import java.util.Optional;

/**
 * 幂等键存储端口。
 *
 * <p>契约 §3.7 要求 {@code Idempotency-Key} 在**同一用户和业务范围内**唯一，
 * 因此键由 {@code scope}（业务范围，如 {@code watchlist-group:create}）与 {@code userId} 共同限定。
 */
public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(String scope, long userId, String key);

    void save(String scope, long userId, String key, IdempotencyRecord record);
}
