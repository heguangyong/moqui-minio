package org.moqui.impl.service.runner;

import io.minio.MinioClient;
import io.minio.MakeBucketArgs;
import io.minio.RemoveBucketArgs;
import io.minio.BucketExistsArgs;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityFind;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.sql.Timestamp;

public class MinioServiceRunner {

    // 创建 MinIO 客户端的辅助方法
    private static MinioClient createMinioClient() {
        // 从系统属性或环境变量读取配置
        String endpoint = System.getProperty("minio.endpoint", "http://localhost:9000");
        String accessKey = System.getProperty("minio.accessKey", "admin");
        String secretKey = System.getProperty("minio.secretKey", "admin123");

        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    public static Map<String, Object> createBucket(ExecutionContext ec) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> parameters = ec.getContext();

        try {
            String bucketId = (String) parameters.get("bucketId");
            String userId = (String) parameters.get("userId");
            String bucketName = (String) parameters.get("bucketName");
            Long quotaLimit = (Long) parameters.get("quotaLimit");

            // 参数验证
            if (bucketId == null || bucketId.trim().isEmpty()) {
                ec.getMessage().addError("bucketId 不能为空");
                return result;
            }
            if (userId == null || userId.trim().isEmpty()) {
                ec.getMessage().addError("userId 不能为空");
                return result;
            }

            // 设置默认值
            if (bucketName == null || bucketName.trim().isEmpty()) {
                bucketName = bucketId;
            }
            if (quotaLimit == null) {
                quotaLimit = 5368709120L; // 5GB
            }

            // 检查 bucketId 是否已存在
            EntityValue existingBucket = ec.getEntity().find("moqui.minio.Bucket")
                    .condition("bucketId", bucketId)
                    .one();
            if (existingBucket != null) {
                ec.getMessage().addError("bucketId " + bucketId + " 已存在");
                return result;
            }

            // 获取 MinIO 客户端
            MinioClient minioClient = createMinioClient();

            // 检查 MinIO 中是否已存在同名 bucket
            boolean bucketExists = minioClient.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(bucketId)
                            .build()
            );

            if (bucketExists) {
                ec.getMessage().addError("MinIO 中已存在名为 '" + bucketId + "' 的 bucket");
                return result;
            }

            // 创建 MinIO bucket
            minioClient.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(bucketId)
                            .build()
            );

            Timestamp now = new Timestamp(System.currentTimeMillis());

            // 保存到数据库
            ec.getEntity().makeValue("moqui.minio.Bucket")
                    .set("bucketId", bucketId)
                    .set("userId", userId)
                    .set("bucketName", bucketName)
                    .set("quotaLimit", quotaLimit)
                    .set("usedStorage", 0L)
                    .set("status", "ACTIVE")
                    .set("isPublic", "N")
                    .set("versioning", "N")
                    .set("encryption", "N")
                    .set("createdDate", now)
                    .set("lastModifiedDate", now)
                    .set("createdByUserId", ec.getUser().getUserId())
                    .create();

            // 创建默认权限：给所有者 ADMIN 权限
            ec.getEntity().makeValue("moqui.minio.BucketPermission")
                    .set("bucketId", bucketId)
                    .set("userId", userId)
                    .set("permissionType", "ADMIN")
                    .set("grantedDate", now)
                    .set("grantedByUserId", ec.getUser().getUserId())
                    .create();

            // 记录操作日志
            logBucketOperation(ec, bucketId, userId, "CREATE", null, 0L, "SUCCESS", null);

            ec.getLogger().info("成功创建 MinIO bucket: bucketId=" + bucketId +
                    ", userId=" + userId + ", bucketName=" + bucketName);

            result.put("bucketId", bucketId);
            result.put("success", true);

        } catch (Exception e) {
            ec.getMessage().addError("创建 bucket 失败: " + e.getMessage());
            result.put("success", false);
            ec.getLogger().error("Failed to create MinIO bucket", e);
        }

        return result;
    }

    public static Map<String, Object> deleteBucket(ExecutionContext ec) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> parameters = ec.getContext();

        try {
            String bucketId = (String) parameters.get("bucketId");
            String userId = (String) parameters.get("userId");

            // 参数验证
            if (bucketId == null || bucketId.trim().isEmpty()) {
                ec.getMessage().addError("bucketId 不能为空");
                return result;
            }
            if (userId == null || userId.trim().isEmpty()) {
                ec.getMessage().addError("userId 不能为空");
                return result;
            }

            // 从数据库查询 bucket 信息
            EntityValue bucketRecord = ec.getEntity().find("moqui.minio.Bucket")
                    .condition("bucketId", bucketId)
                    .condition("userId", userId)
                    .one();

            if (bucketRecord == null) {
                ec.getMessage().addError("未找到 bucketId=" + bucketId + " 的 bucket 或无权限访问");
                return result;
            }

            // 获取 MinIO 客户端
            MinioClient minioClient = createMinioClient();

            // 检查并删除 MinIO bucket
            boolean bucketExists = minioClient.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(bucketId)
                            .build()
            );

            if (bucketExists) {
                try {
                    minioClient.removeBucket(
                            RemoveBucketArgs.builder()
                                    .bucket(bucketId)
                                    .build()
                    );
                } catch (Exception minioException) {
                    ec.getMessage().addError("删除 MinIO bucket 失败，可能 bucket 不为空: " + minioException.getMessage());
                    logBucketOperation(ec, bucketId, userId, "DELETE", null, 0L, "FAILURE", minioException.getMessage());
                    return result;
                }
            }

            // 更新 bucket 状态为 DELETED
            bucketRecord.set("status", "DELETED");
            bucketRecord.set("lastModifiedDate", new Timestamp(System.currentTimeMillis()));
            bucketRecord.update();

            // 删除相关权限记录
            EntityList permissionRecords = ec.getEntity().find("moqui.minio.BucketPermission")
                    .condition("bucketId", bucketId)
                    .list();
            for (EntityValue permission : permissionRecords) {
                permission.delete();
            }

            // 记录操作日志
            logBucketOperation(ec, bucketId, userId, "DELETE", null, 0L, "SUCCESS", null);

            ec.getLogger().info("成功删除 MinIO bucket: bucketId=" + bucketId + ", userId=" + userId);

            result.put("success", true);

        } catch (Exception e) {
            logBucketOperation(ec, (String) parameters.get("bucketId"), (String) parameters.get("userId"),
                    "DELETE", null, 0L, "FAILURE", e.getMessage());
            ec.getMessage().addError("删除 bucket 失败: " + e.getMessage());
            result.put("success", false);
            ec.getLogger().error("Failed to delete MinIO bucket", e);
        }

        return result;
    }

    public static Map<String, Object> listBucket(ExecutionContext ec) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> parameters = ec.getContext();

        try {
            String userId = (String) parameters.get("userId");
            String status = (String) parameters.get("status");

            // 参数验证
            if (userId == null || userId.trim().isEmpty()) {
                ec.getMessage().addError("userId 不能为空");
                return result;
            }

            // 构建查询
            EntityFind bucketFind = ec.getEntity().find("moqui.minio.Bucket")
                    .condition("userId", userId)
                    .condition("status", "!=", "DELETED")
                    .orderBy("createdDate");

            // 应用过滤条件
            if (status != null && !status.trim().isEmpty()) {
                bucketFind.condition("status", status);
            }

            EntityList bucketRecords = bucketFind.list();

            List<Map<String, Object>> bucketList = new ArrayList<>();

            // 获取 MinIO 客户端
            MinioClient minioClient = null;
            try {
                minioClient = createMinioClient();
            } catch (Exception e) {
                ec.getLogger().warn("无法获取 MinIO 客户端，将跳过状态检查", e);
            }

            for (EntityValue bucketRecord : bucketRecords) {
                Map<String, Object> bucketInfo = new HashMap<>();
                String bucketId = bucketRecord.getString("bucketId");

                // 基本信息
                bucketInfo.put("bucketId", bucketId);
                bucketInfo.put("userId", bucketRecord.getString("userId"));
                bucketInfo.put("bucketName", bucketRecord.getString("bucketName"));
                bucketInfo.put("quotaLimit", bucketRecord.getLong("quotaLimit"));
                bucketInfo.put("usedStorage", bucketRecord.getLong("usedStorage"));
                bucketInfo.put("status", bucketRecord.getString("status"));
                bucketInfo.put("isPublic", bucketRecord.getString("isPublic"));
                bucketInfo.put("versioning", bucketRecord.getString("versioning"));
                bucketInfo.put("encryption", bucketRecord.getString("encryption"));
                bucketInfo.put("createdDate", bucketRecord.getTimestamp("createdDate"));
                bucketInfo.put("lastModifiedDate", bucketRecord.getTimestamp("lastModifiedDate"));

                // 检查 MinIO 中的实际状态
                if (minioClient != null) {
                    try {
                        boolean exists = minioClient.bucketExists(
                                BucketExistsArgs.builder()
                                        .bucket(bucketId)
                                        .build()
                        );
                        bucketInfo.put("existsInMinio", exists);

                        // 如果状态不一致，更新数据库记录
                        if (!exists && "ACTIVE".equals(bucketRecord.getString("status"))) {
                            bucketRecord.set("status", "ERROR");
                            bucketRecord.set("lastModifiedDate", new Timestamp(System.currentTimeMillis()));
                            bucketRecord.update();
                            bucketInfo.put("status", "ERROR");
                        }

                    } catch (Exception e) {
                        ec.getLogger().warn("检查 bucket 状态失败: " + bucketId, e);
                        bucketInfo.put("existsInMinio", false);
                    }
                } else {
                    bucketInfo.put("existsInMinio", null);
                }

                bucketList.add(bucketInfo);
            }

            // 记录查询操作
            logBucketOperation(ec, null, userId, "LIST", null, 0L, "SUCCESS", null);

            ec.getLogger().info("查询用户 buckets 成功: userId=" + userId + ", 找到 " + bucketList.size() + " 个 buckets");

            result.put("bucketList", bucketList);
            result.put("totalCount", bucketList.size());
            result.put("success", true);

        } catch (Exception e) {
            logBucketOperation(ec, null, (String) parameters.get("userId"), "LIST", null, 0L, "FAILURE", e.getMessage());
            ec.getMessage().addError("查询 bucket 列表失败: " + e.getMessage());
            result.put("success", false);
            ec.getLogger().error("Failed to list MinIO buckets", e);
        }

        return result;
    }

    // 辅助方法：记录操作日志
    private static void logBucketOperation(ExecutionContext ec, String bucketId, String userId,
                                           String operation, String objectName, Long objectSize,
                                           String resultStatus, String errorMessage) {
        try {
            ec.getEntity().makeValue("moqui.minio.BucketUsageLog")
                    .set("logId", java.util.UUID.randomUUID().toString())
                    .set("bucketId", bucketId)
                    .set("userId", userId)
                    .set("operation", operation)
                    .set("objectName", objectName)
                    .set("objectSize", objectSize)
                    .set("operationDate", new Timestamp(System.currentTimeMillis()))
                    .set("ipAddress", ec.getWeb() != null ? ec.getWeb().getRequest().getRemoteAddr() : null)
                    .set("userAgent", ec.getWeb() != null ? ec.getWeb().getRequest().getHeader("User-Agent") : null)
                    .set("resultStatus", resultStatus)
                    .set("errorMessage", errorMessage)
                    .create();
        } catch (Exception e) {
            ec.getLogger().warn("记录 bucket 操作日志失败", e);
        }
    }
}