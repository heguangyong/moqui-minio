/*
 * This software is in the public domain under CC0 1.0 Universal plus a Grant of Patent License.
 */
import io.minio.MinioClient
def minioClient = ec.getTool("Minio")
try {
    minioClient.makeBucket(bucketId)
    ec.entity.makeValue("moqui.netdisk.Bucket").setAll([
            bucketId: bucketId,
            userId: userId,
            bucketName: bucketName,
            quotaLimit: quotaLimit,
            createdDate: ec.user.nowTimestamp
    ]).create()
    return [bucketId: bucketId]
} catch (Exception e) {
    ec.message.addError("Failed to create bucket: ${e.message}")
    return null
}