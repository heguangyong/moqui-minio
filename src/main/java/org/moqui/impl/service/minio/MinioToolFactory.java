package org.moqui.impl.service.minio;

import io.minio.MinioClient;
import org.moqui.context.ExecutionContextFactory;
import org.moqui.context.ToolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MinioToolFactory implements ToolFactory<MinioClient> {
    private static final Logger logger = LoggerFactory.getLogger(MinioToolFactory.class);
    public static final String TOOL_NAME = "Minio";

    private ExecutionContextFactory ecf;
    private MinioClient minioClient;

    public MinioToolFactory() {}

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public void init(ExecutionContextFactory ecf) {
        this.ecf = ecf;
        String endpoint = System.getProperty("minio.endpoint", "http://localhost:9000");
        String accessKey = System.getProperty("minio.accessKey", "admin");
        String secretKey = System.getProperty("minio.secretKey", "admin123");
        logger.info("Initializing MinioClient with endpoint {}", endpoint);
        minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Override
    public void preFacadeInit(ExecutionContextFactory ecf) {
        this.ecf = ecf;
    }

    @Override
    public MinioClient getInstance(Object... parameters) {
        if (minioClient == null) throw new IllegalStateException("MinioToolFactory not initialized");
        return minioClient;
    }

    @Override
    public void destroy() {
        logger.info("MinioClient stopped");
    }
}

