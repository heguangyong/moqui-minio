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

import org.moqui.context.ExecutionContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MinIO配置管理类
 *
 * 负责管理MinIO连接相关的所有配置参数，提供统一的配置访问接口
 * 支持多种配置来源：系统属性、环境变量、Moqui配置文件
 */
public class MinioConfig {
    private static final Logger logger = LoggerFactory.getLogger(MinioConfig.class);

    // 配置键常量
    public static final String PROP_ENDPOINT = "minio.endpoint";
    public static final String PROP_ACCESS_KEY = "minio.accessKey";
    public static final String PROP_SECRET_KEY = "minio.secretKey";
    public static final String PROP_REGION = "minio.region";
    public static final String PROP_SECURE = "minio.secure";
    public static final String PROP_CONNECTION_TIMEOUT = "minio.connectionTimeout";
    public static final String PROP_READ_TIMEOUT = "minio.readTimeout";
    public static final String PROP_WRITE_TIMEOUT = "minio.writeTimeout";

    // 默认值常量
    public static final String DEFAULT_ENDPOINT = "http://localhost:9000";
    public static final String DEFAULT_ACCESS_KEY = "admin";
    public static final String DEFAULT_SECRET_KEY = "admin123";
    public static final String DEFAULT_REGION = "us-east-1";
    public static final boolean DEFAULT_SECURE = false;
    public static final int DEFAULT_CONNECTION_TIMEOUT = 10000; // 10秒
    public static final int DEFAULT_READ_TIMEOUT = 10000; // 10秒
    public static final int DEFAULT_WRITE_TIMEOUT = 10000; // 10秒

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final boolean secure;
    private final int connectionTimeout;
    private final int readTimeout;
    private final int writeTimeout;

    /**
     * 从ExecutionContextFactory创建配置
     */
    public MinioConfig(ExecutionContextFactory ecf) {
        this.endpoint = getConfigValue(ecf, PROP_ENDPOINT, DEFAULT_ENDPOINT);
        this.accessKey = getConfigValue(ecf, PROP_ACCESS_KEY, DEFAULT_ACCESS_KEY);
        this.secretKey = getConfigValue(ecf, PROP_SECRET_KEY, DEFAULT_SECRET_KEY);
        this.region = getConfigValue(ecf, PROP_REGION, DEFAULT_REGION);
        this.secure = Boolean.parseBoolean(getConfigValue(ecf, PROP_SECURE, String.valueOf(DEFAULT_SECURE)));
        this.connectionTimeout = Integer.parseInt(getConfigValue(ecf, PROP_CONNECTION_TIMEOUT, String.valueOf(DEFAULT_CONNECTION_TIMEOUT)));
        this.readTimeout = Integer.parseInt(getConfigValue(ecf, PROP_READ_TIMEOUT, String.valueOf(DEFAULT_READ_TIMEOUT)));
        this.writeTimeout = Integer.parseInt(getConfigValue(ecf, PROP_WRITE_TIMEOUT, String.valueOf(DEFAULT_WRITE_TIMEOUT)));

        validateConfiguration();
        logConfiguration();
    }

    /**
     * 使用默认配置创建（主要用于测试）
     */
    public MinioConfig() {
        this.endpoint = DEFAULT_ENDPOINT;
        this.accessKey = DEFAULT_ACCESS_KEY;
        this.secretKey = DEFAULT_SECRET_KEY;
        this.region = DEFAULT_REGION;
        this.secure = DEFAULT_SECURE;
        this.connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
        this.readTimeout = DEFAULT_READ_TIMEOUT;
        this.writeTimeout = DEFAULT_WRITE_TIMEOUT;

        validateConfiguration();
        logConfiguration();
    }

    /**
     * 获取配置值，优先级：系统属性 > 环境变量 > Moqui配置 > 默认值
     */
    private String getConfigValue(ExecutionContextFactory ecf, String key, String defaultValue) {
        // 1. 检查系统属性
        String value = System.getProperty(key);
        if (value != null && !value.trim().isEmpty()) {
            logger.debug("Found config {} from system property: {}", key, value);
            return value.trim();
        }

        // 2. 检查环境变量（将点号替换为下划线并转大写）
        String envKey = key.replace('.', '_').toUpperCase();
        value = System.getenv(envKey);
        if (value != null && !value.trim().isEmpty()) {
            logger.debug("Found config {} from environment variable {}: {}", key, envKey, value);
            return value.trim();
        }

        // 3. TODO: 在后续版本中可以添加Moqui配置文件支持
        // 目前跳过Moqui配置访问以避免API兼容性问题

        // 4. 返回默认值
        logger.debug("Using default value for {}: {}", key, defaultValue);
        return defaultValue;
    }

    /**
     * 验证配置有效性
     */
    private void validateConfiguration() {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("MinIO endpoint cannot be null or empty");
        }

        if (accessKey == null || accessKey.trim().isEmpty()) {
            throw new IllegalArgumentException("MinIO access key cannot be null or empty");
        }

        if (secretKey == null || secretKey.trim().isEmpty()) {
            throw new IllegalArgumentException("MinIO secret key cannot be null or empty");
        }

        if (connectionTimeout < 0) {
            throw new IllegalArgumentException("Connection timeout must be non-negative");
        }

        if (readTimeout < 0) {
            throw new IllegalArgumentException("Read timeout must be non-negative");
        }

        if (writeTimeout < 0) {
            throw new IllegalArgumentException("Write timeout must be non-negative");
        }

        // 验证endpoint格式
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new IllegalArgumentException("MinIO endpoint must start with http:// or https://");
        }
    }

    /**
     * 记录配置信息（不包含敏感信息）
     */
    private void logConfiguration() {
        logger.info("MinIO Configuration initialized:");
        logger.info("  Endpoint: {}", endpoint);
        logger.info("  Access Key: {}***", accessKey.length() > 3 ? accessKey.substring(0, 3) : "***");
        logger.info("  Region: {}", region);
        logger.info("  Secure: {}", secure);
        logger.info("  Connection Timeout: {}ms", connectionTimeout);
        logger.info("  Read Timeout: {}ms", readTimeout);
        logger.info("  Write Timeout: {}ms", writeTimeout);
    }

    // Getter方法
    public String getEndpoint() { return endpoint; }
    public String getAccessKey() { return accessKey; }
    public String getSecretKey() { return secretKey; }
    public String getRegion() { return region; }
    public boolean isSecure() { return secure; }
    public int getConnectionTimeout() { return connectionTimeout; }
    public int getReadTimeout() { return readTimeout; }
    public int getWriteTimeout() { return writeTimeout; }

    /**
     * 获取掩码后的配置字符串（用于日志记录）
     */
    public String toMaskedString() {
        return String.format("MinioConfig{endpoint='%s', accessKey='%s***', region='%s', secure=%s, " +
                "connectionTimeout=%d, readTimeout=%d, writeTimeout=%d}",
                endpoint,
                accessKey.length() > 3 ? accessKey.substring(0, 3) : "***",
                region, secure, connectionTimeout, readTimeout, writeTimeout);
    }

    @Override
    public String toString() {
        return toMaskedString();
    }
}