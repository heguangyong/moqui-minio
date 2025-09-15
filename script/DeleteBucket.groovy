/*
 * This software is in the public domain under CC0 1.0 Universal plus a Grant of Patent License.
 */
import io.minio.MinioClient
def minioClient = ec.getTool("Minio")
try {
    minioClient.removeBucket(bucketId)
    ec.entity.find("moqui.netdisk.Bucket")
            .condition("bucketId", bucketId)
            .condition("userId", userId)
            .delete()
} catch (Exception e) {
    ec.message.addError("Failed to delete bucket: ${e.message}")
}