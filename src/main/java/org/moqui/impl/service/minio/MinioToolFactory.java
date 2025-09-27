package org.moqui.impl.service.minio;

import io.minio.MinioClient;
import org.moqui.context.ExecutionContextFactory;
import org.moqui.context.ToolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MinIO工具工厂
 *
 * 作为Moqui工具工厂，负责创建和管理MinIO客户端实例
 * 提供单例模式的MinIO客户端访问
 */
public class MinioToolFactory implements ToolFactory<MinioClient> {
    private static final Logger logger = LoggerFactory.getLogger(MinioToolFactory.class);
    public static final String TOOL_NAME = "Minio";

    private ExecutionContextFactory ecf;
    private MinioClient minioClient;
    private MinioConfig config;

    public MinioToolFactory() {}

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public void init(ExecutionContextFactory ecf) {
        this.ecf = ecf;

        try {
            logger.info("Initializing MinIO Tool Factory");

            // 创建配置
            this.config = new MinioConfig(ecf);

            // 创建客户端
            this.minioClient = MinioClientPool.getClient(ecf);

            // 验证连接
            if (MinioClientFactory.validateConnection(minioClient)) {
                logger.info("MinIO Tool Factory initialized successfully");
            } else {
                logger.warn("MinIO Tool Factory initialized but connection validation failed");
            }

        } catch (Exception e) {
            logger.error("Failed to initialize MinIO Tool Factory", e);
            throw new RuntimeException("MinIO Tool Factory initialization failed", e);
        }
    }

    @Override
    public void preFacadeInit(ExecutionContextFactory ecf) {
        this.ecf = ecf;
    }

    @Override
    public MinioClient getInstance(Object... parameters) {
        if (minioClient == null) {
            throw new IllegalStateException("MinioToolFactory not initialized properly");
        }
        return minioClient;
    }

    @Override
    public void destroy() {
        try {
            logger.info("Destroying MinIO Tool Factory");

            if (minioClient != null) {
                MinioClientFactory.closeClient(minioClient);
                minioClient = null;
            }

            config = null;
            ecf = null;

            logger.info("MinIO Tool Factory destroyed successfully");

        } catch (Exception e) {
            logger.error("Error during MinIO Tool Factory destruction", e);
        }
    }

    /**
     * 获取MinIO配置
     *
     * @return 当前的MinIO配置实例
     */
    public MinioConfig getConfig() {
        return config;
    }

    /**
     * 检查工具工厂是否已初始化
     *
     * @return true如果已初始化，否则false
     */
    public boolean isInitialized() {
        return minioClient != null && config != null;
    }
}

