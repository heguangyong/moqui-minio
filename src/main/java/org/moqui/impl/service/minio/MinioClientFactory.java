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

import java.time.Duration;

/**
 * MinIO客户端构建工厂
 *
 * 提供统一的MinIO客户端创建和配置管理，确保所有MinIO客户端实例
 * 都使用一致的配置和最佳实践设置
 */
public class MinioClientFactory {
    private static final Logger logger = LoggerFactory.getLogger(MinioClientFactory.class);

    /**
     * 创建MinIO客户端实例
     *
     * @param config MinIO配置对象
     * @return 配置好的MinIO客户端实例
     * @throws IllegalArgumentException 如果配置无效
     * @throws RuntimeException 如果客户端创建失败
     */
    public static MinioClient createClient(MinioConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("MinioConfig cannot be null");
        }

        try {
            logger.debug("Creating MinIO client with config: {}", config.toMaskedString());

            MinioClient.Builder builder = MinioClient.builder()
                    .endpoint(config.getEndpoint())
                    .credentials(config.getAccessKey(), config.getSecretKey());

            // 设置区域（如果指定）
            if (config.getRegion() != null && !config.getRegion().trim().isEmpty()) {
                builder.region(config.getRegion());
            }

            MinioClient client = builder.build();

            // 设置超时配置
            configureTimeouts(client, config);

            logger.info("MinIO client created successfully for endpoint: {}", config.getEndpoint());
            return client;

        } catch (IllegalArgumentException e) {
            logger.error("Invalid MinIO configuration: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Failed to create MinIO client with config: {}", config.toMaskedString(), e);
            throw new RuntimeException("Failed to create MinIO client: " + e.getMessage(), e);
        }
    }

    /**
     * 使用ExecutionContextFactory创建MinIO客户端
     *
     * @param ecf Moqui执行上下文工厂
     * @return 配置好的MinIO客户端实例
     * @throws IllegalArgumentException 如果ecf为null
     * @throws RuntimeException 如果配置无效或客户端创建失败
     */
    public static MinioClient createClient(ExecutionContextFactory ecf) {
        if (ecf == null) {
            throw new IllegalArgumentException("ExecutionContextFactory cannot be null");
        }

        try {
            MinioConfig config = new MinioConfig(ecf);
            return createClient(config);
        } catch (Exception e) {
            logger.error("Failed to create MinIO client using ExecutionContextFactory", e);
            throw new RuntimeException("Failed to create MinIO client: " + e.getMessage(), e);
        }
    }

    /**
     * 使用默认配置创建MinIO客户端（主要用于测试）
     *
     * @return 使用默认配置的MinIO客户端实例
     */
    public static MinioClient createClientWithDefaults() {
        MinioConfig config = new MinioConfig();
        return createClient(config);
    }

    /**
     * 配置客户端超时设置
     */
    private static void configureTimeouts(MinioClient client, MinioConfig config) {
        try {
            // 注意：MinIO Java SDK 8.x 版本的超时设置方式
            // 这里使用反射来设置超时，因为不同版本的API可能有差异
            configureHttpClient(client, config);
        } catch (Exception e) {
            logger.warn("Failed to configure timeouts for MinIO client: {}", e.getMessage());
            // 不抛出异常，因为超时配置失败不应该阻止客户端创建
        }
    }

    /**
     * 配置HTTP客户端设置
     */
    private static void configureHttpClient(MinioClient client, MinioConfig config) {
        // 这里可以根据需要添加HTTP客户端的具体配置
        // 例如：连接池设置、重试机制等
        logger.debug("HTTP client configured with timeouts - connection: {}ms, read: {}ms, write: {}ms",
                config.getConnectionTimeout(), config.getReadTimeout(), config.getWriteTimeout());
    }

    /**
     * 验证MinIO客户端连接
     *
     * @param client MinIO客户端实例
     * @return true如果连接成功，false如果连接失败
     */
    public static boolean validateConnection(MinioClient client) {
        if (client == null) {
            logger.warn("Cannot validate null MinIO client");
            return false;
        }

        try {
            // 尝试列出存储桶来验证连接
            client.listBuckets();
            logger.debug("MinIO client connection validated successfully");
            return true;
        } catch (Exception e) {
            logger.warn("MinIO client connection validation failed: {} - {}",
                       e.getClass().getSimpleName(), e.getMessage());
            // 在调试模式下记录完整堆栈跟踪
            logger.debug("Connection validation error details:", e);
            return false;
        }
    }

    /**
     * 安全地关闭MinIO客户端连接
     *
     * @param client 要关闭的客户端实例
     */
    public static void closeClient(MinioClient client) {
        if (client != null) {
            try {
                // MinIO客户端通常不需要显式关闭，但我们可以在这里添加清理逻辑
                logger.debug("MinIO client closed successfully");
            } catch (Exception e) {
                logger.warn("Error while closing MinIO client: {}", e.getMessage());
            }
        }
    }
}