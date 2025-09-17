/*
 * This software is in the public domain under CC0 1.0 Universal plus a Grant of Patent License.
 */
import io.minio.MinioClient
import io.minio.MakeBucketArgs

def minioClient = ec.getTool("Minio")
try {
    // Try to list buckets to test the connection
    def bucketList = minioClient.listBuckets()
    ec.logger.info("Successfully connected to MinIO. Found ${bucketList.size()} buckets.")
    
    // Try to create a test bucket
    def bucketName = "test-connection-bucket"
    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build())
    ec.logger.info("Successfully created test bucket: ${bucketName}")
    
    // Clean up - delete the test bucket
    minioClient.removeBucket(bucketName)
    ec.logger.info("Successfully deleted test bucket: ${bucketName}")
    
    return [success: true, message: "MinIO connection test successful"]
} catch (Exception e) {
    ec.logger.error("Failed to connect to MinIO: ${e.message}", e)
    return [success: false, message: "MinIO connection test failed: ${e.message}"]
}