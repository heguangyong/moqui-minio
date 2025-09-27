/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.service.minio;

import io.minio.MinioClient;
import org.moqui.context.ExecutionContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * MinIO客户端连接池
 *
 * 管理MinIO客户端实例的生命周期，提供连接复用和性能优化
 * 基于配置哈希值缓存客户端实例，避免重复创建
 */
public class MinioClientPool {
    private static final Logger logger = LoggerFactory.getLogger(MinioClientPool.class);

    private static final ConcurrentHashMap<String, MinioClient> clientCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastAccessTime = new ConcurrentHashMap<>();
    private static final ReadWriteLock lock = new ReentrantReadWriteLock();

    // 缓存过期时间：30分钟
    private static final long CACHE_EXPIRY_MS = 30 * 60 * 1000;
    // 最大缓存客户端数量
    private static final int MAX_CACHED_CLIENTS = 10;

    /**
     * 获取或创建MinIO客户端实例
     *
     * @param ecf ExecutionContextFactory
     * @return MinIO客户端实例
     */
    public static MinioClient getClient(ExecutionContextFactory ecf) {
        MinioConfig config = new MinioConfig(ecf);
        return getClient(config);
    }

    /**
     * 获取或创建MinIO客户端实例
     *
     * @param config MinIO配置
     * @return MinIO客户端实例
     */
    public static MinioClient getClient(MinioConfig config) {
        String configHash = generateConfigHash(config);

        // 首先尝试从缓存获取
        lock.readLock().lock();
        try {
            MinioClient cachedClient = clientCache.get(configHash);
            if (cachedClient != null) {
                // 更新访问时间
                lastAccessTime.put(configHash, System.currentTimeMillis());
                logger.debug("返回缓存的MinIO客户端: {}", configHash);
                return cachedClient;
            }
        } finally {
            lock.readLock().unlock();
        }

        // 缓存中没有，需要创建新的客户端
        lock.writeLock().lock();
        try {
            // 双重检查锁定模式
            MinioClient cachedClient = clientCache.get(configHash);
            if (cachedClient != null) {
                lastAccessTime.put(configHash, System.currentTimeMillis());
                return cachedClient;
            }

            // 清理过期的客户端
            cleanupExpiredClients();

            // 检查缓存大小限制
            if (clientCache.size() >= MAX_CACHED_CLIENTS) {
                evictOldestClient();
            }

            // 创建新客户端
            logger.debug("创建新的MinIO客户端: {}", configHash);
            MinioClient newClient = MinioClientFactory.createClient(config);

            // 验证连接
            if (MinioClientFactory.validateConnection(newClient)) {
                clientCache.put(configHash, newClient);
                lastAccessTime.put(configHash, System.currentTimeMillis());
                logger.info("新MinIO客户端已缓存: {}", configHash);
                return newClient;
            } else {
                logger.warn("MinIO客户端连接验证失败，不缓存: {}", configHash);
                return newClient; // 即使验证失败也返回客户端，让调用者处理
            }

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 生成配置的哈希值
     */
    private static String generateConfigHash(MinioConfig config) {
        return String.format("%s:%s:%s:%s",
                config.getEndpoint(),
                config.getAccessKey(),
                config.getRegion() != null ? config.getRegion() : "default",
                config.isSecure());
    }

    /**
     * 清理过期的客户端
     */
    private static void cleanupExpiredClients() {
        long currentTime = System.currentTimeMillis();
        int cleanedCount = 0;

        // 创建一个副本来避免并发修改异常
        for (String configHash : clientCache.keySet()) {
            Long lastAccess = lastAccessTime.get(configHash);
            if (lastAccess != null && (currentTime - lastAccess) > CACHE_EXPIRY_MS) {
                MinioClient client = clientCache.remove(configHash);
                lastAccessTime.remove(configHash);
                if (client != null) {
                    MinioClientFactory.closeClient(client);
                    cleanedCount++;
                    logger.debug("清理过期的MinIO客户端: {}", configHash);
                }
            }
        }

        if (cleanedCount > 0) {
            logger.info("清理了 {} 个过期的MinIO客户端", cleanedCount);
        }
    }

    /**
     * 移除最旧的客户端
     */
    private static void evictOldestClient() {
        String oldestConfigHash = null;
        long oldestTime = Long.MAX_VALUE;

        for (String configHash : lastAccessTime.keySet()) {
            Long accessTime = lastAccessTime.get(configHash);
            if (accessTime != null && accessTime < oldestTime) {
                oldestTime = accessTime;
                oldestConfigHash = configHash;
            }
        }

        if (oldestConfigHash != null) {
            MinioClient client = clientCache.remove(oldestConfigHash);
            lastAccessTime.remove(oldestConfigHash);
            if (client != null) {
                MinioClientFactory.closeClient(client);
                logger.info("移除最旧的MinIO客户端: {}", oldestConfigHash);
            }
        }
    }

    /**
     * 获取缓存统计信息
     */
    public static String getCacheStats() {
        lock.readLock().lock();
        try {
            int cacheSize = clientCache.size();
            long currentTime = System.currentTimeMillis();
            int activeConnections = 0;

            for (Long lastAccess : lastAccessTime.values()) {
                if (lastAccess != null && (currentTime - lastAccess) <= CACHE_EXPIRY_MS) {
                    activeConnections++;
                }
            }

            return String.format("MinIO客户端缓存统计: 总计=%d, 活跃=%d, 最大=%d",
                    cacheSize, activeConnections, MAX_CACHED_CLIENTS);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 强制清理所有缓存的客户端
     */
    public static void clearCache() {
        lock.writeLock().lock();
        try {
            int clearedCount = clientCache.size();
            for (MinioClient client : clientCache.values()) {
                MinioClientFactory.closeClient(client);
            }
            clientCache.clear();
            lastAccessTime.clear();
            logger.info("清理了所有缓存的MinIO客户端: {}", clearedCount);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 预热连接池（可选）
     */
    public static void warmupPool(ExecutionContextFactory ecf) {
        try {
            logger.info("开始预热MinIO连接池");
            MinioClient client = getClient(ecf);
            // 执行一个轻量级操作来验证连接
            MinioClientFactory.validateConnection(client);
            logger.info("MinIO连接池预热完成");
        } catch (Exception e) {
            logger.warn("MinIO连接池预热失败: {}", e.getMessage());
        }
    }
}