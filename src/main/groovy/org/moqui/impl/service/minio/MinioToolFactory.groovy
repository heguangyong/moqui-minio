/*
 * This software is in the public domain under CC0 1.0 Universal plus a Grant of Patent License.
 */
package org.moqui.impl.service.minio

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import io.minio.MinioClient

@CompileStatic
class MinioToolFactory implements ToolFactory<MinioClient> {
    protected final static Logger logger = LoggerFactory.getLogger(MinioToolFactory.class)
    final static String TOOL_NAME = "Minio"

    protected ExecutionContextFactory ecf = null
    protected MinioClient minioClient = null

    MinioToolFactory() {}

    @Override
    String getName() { return TOOL_NAME }

    @Override
    void init(ExecutionContextFactory ecf) {
        this.ecf = ecf
        String endpoint = System.getProperty("minio.endpoint") ?: "http://localhost:9000"
        String accessKey = System.getProperty("minio.accessKey") ?: "minioadmin"
        String secretKey = System.getProperty("minio.secretKey") ?: "minioadmin"
        logger.info("Initializing MinioClient with endpoint ${endpoint}")
        minioClient = new MinioClient.Builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build()
    }

    @Override
    void preFacadeInit(ExecutionContextFactory ecf) {
        this.ecf = ecf
    }

    @Override
    MinioClient getInstance(Object... parameters) {
        if (minioClient == null) throw new IllegalStateException("MinioToolFactory not initialized")
        return minioClient
    }

    @Override
    void destroy() {
        if (minioClient != null) {
            logger.info("MinioClient stopped")
        }
    }
}