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
package org.moqui.impl.service.runner

import groovy.transform.CompileStatic
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.service.ServiceRunner
import org.moqui.service.ServiceException
import org.moqui.impl.service.minio.MinioToolFactory
import io.minio.MinioClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class MinioServiceRunner implements ServiceRunner {
    protected final static Logger logger = LoggerFactory.getLogger(MinioServiceRunner.class)

    protected ServiceFacadeImpl sfi
    protected MinioToolFactory minioToolFactory

    MinioServiceRunner() {}

    @Override
    ServiceRunner init(ServiceFacadeImpl sfi) {
        this.sfi = sfi
        minioToolFactory = (MinioToolFactory) sfi.ecfi.getToolFactory(MinioToolFactory.TOOL_NAME)
        if (minioToolFactory == null) {
            logger.warn("Minio not initialized, MinioServiceRunner disabled")
        }
        return this
    }

    @Override
    Map<String, Object> runService(ServiceDefinition sd, Map<String, Object> parameters) {
        if (minioToolFactory == null) throw new IllegalStateException("MinioServiceRunner disabled, Minio not initialized")
        String location = sd.location
        if (!location) throw new ServiceException("Service [${sd.serviceName}] missing location attribute")

        MinioClient minioClient = minioToolFactory.getInstance()
        Map<String, Object> result = new HashMap<>()

        try {
            switch (location) {
                case "minio://makeBucket":
                    minioClient.makeBucket((String) parameters.bucketId)
                    sfi.ecfi.entity.makeValue("moqui.netdisk.Bucket").setAll([
                            bucketId: parameters.bucketId,
                            userId: parameters.userId,
                            bucketName: parameters.bucketName,
                            quotaLimit: parameters.quotaLimit,
                            createdDate: sfi.ecfi.executionContext.user.nowTimestamp
                    ]).create()
                    result.bucketId = parameters.bucketId
                    break
                case "minio://removeBucket":
                    minioClient.removeBucket((String) parameters.bucketId)
                    sfi.ecfi.entity.find("moqui.netdisk.Bucket")
                            .condition("bucketId", parameters.bucketId)
                            .condition("userId", parameters.userId)
                            .delete()
                    break
                default:
                    throw new ServiceException("Unsupported MinIO location: ${location}")
            }
            return result
        } catch (Exception e) {
            sfi.ecfi.executionContext.message.addError("MinIO operation failed: ${e.message}")
            return null
        }
    }

    @Override
    void destroy() {
        logger.info("MinioServiceRunner stopped")
    }
}