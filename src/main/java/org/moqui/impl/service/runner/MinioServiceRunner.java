package org.moqui.impl.service.runner;

import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Item;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityFind;
import org.moqui.impl.service.minio.MinioClientFactory;
import org.moqui.impl.service.minio.MinioClientPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.sql.Timestamp;

/**
 * MinIO服务运行器
 *
 * 包含所有MinIO相关的服务实现方法，提供存储桶管理、对象操作等功能
 */
public class MinioServiceRunner {
    private static final Logger logger = LoggerFactory.getLogger(MinioServiceRunner.class);

    /**
     * 创建MinIO客户端的辅助方法
     * 使用连接池来提升性能和资源利用率
     */
    private static MinioClient createMinioClient(ExecutionContext ec) {
        try {
            return MinioClientPool.getClient(ec.getFactory());
        } catch (Exception e) {
            logger.error("Failed to create MinIO client", e);
            throw new RuntimeException("Failed to create MinIO client: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> createBucket(ExecutionContext ec) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> parameters = ec.getContext();

        try {
            String bucketId = (String) parameters.get("bucketId");
            String userId = (String) parameters.get("userId");
            String bucketName = (String) parameters.get("bucketName");
            String description = (String) parameters.get("description");
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

            // S3 存储桶命名规则验证
            if (!isValidS3BucketName(bucketId)) {
                ec.getMessage().addError("存储桶ID不符合S3命名规范：只能包含小写字母、数字和连字符(-)，长度3-63字符，不能以连字符开头或结尾");
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
            MinioClient minioClient = createMinioClient(ec);

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
                    .set("description", description)
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
                    ", userId=" + userId + ", bucketName=" + bucketName +
                    ", description=" + description);

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

            ec.getLogger().info("开始删除 bucket: bucketId=" + bucketId + ", userId=" + userId);

            // 参数验证
            if (bucketId == null || bucketId.trim().isEmpty()) {
                ec.getMessage().addError("bucketId 不能为空");
                ec.getLogger().warn("删除 bucket 失败: bucketId 为空");
                return result;
            }
            if (userId == null || userId.trim().isEmpty()) {
                ec.getMessage().addError("userId 不能为空");
                ec.getLogger().warn("删除 bucket 失败: userId 为空");
                return result;
            }

            // 从数据库查询 bucket 信息
            EntityValue bucketRecord = ec.getEntity().find("moqui.minio.Bucket")
                    .condition("bucketId", bucketId)
                    .one();

            if (bucketRecord == null) {
                ec.getMessage().addError("未找到 bucketId=" + bucketId + " 的 bucket");
                return result;
            }

            // 检查权限：要么是桶的所有者，要么是管理员
            String bucketOwnerId = bucketRecord.getString("userId");
            boolean isOwner = userId.equals(bucketOwnerId);
            boolean isAdmin = ec.getUser().isInGroup("ADMIN") || ec.getUser().isInGroup("ADMIN_ADV");

            if (!isOwner && !isAdmin) {
                ec.getMessage().addError("没有权限删除此bucket");
                return result;
            }

            ec.getLogger().info("找到要删除的 bucket 记录: " + bucketId);

            // 获取 MinIO 客户端
            MinioClient minioClient = createMinioClient(ec);
            ec.getLogger().info("成功创建 MinIO 客户端");

            // 检查并删除 MinIO bucket
            boolean bucketExists = false;
            try {
                bucketExists = minioClient.bucketExists(
                        BucketExistsArgs.builder()
                                .bucket(bucketId)
                                .build()
                );
                ec.getLogger().info("检查 bucket 是否存在: " + bucketId + ", exists=" + bucketExists);
            } catch (Exception e) {
                String errorMsg = "检查 MinIO bucket 状态失败: " + e.getMessage();
                ec.getMessage().addError(errorMsg);
                logBucketOperation(ec, bucketId, userId, "DELETE", null, 0L, "FAILURE", e.getMessage());
                ec.getLogger().error(errorMsg, e);
                return result;
            }

            if (bucketExists) {
                try {
                    ec.getLogger().info("开始删除 MinIO 中的 bucket: " + bucketId);
                    minioClient.removeBucket(
                            RemoveBucketArgs.builder()
                                    .bucket(bucketId)
                                    .build()
                    );
                    ec.getLogger().info("成功删除 MinIO 中的 bucket: " + bucketId);
                } catch (Exception minioException) {
                    String errorMsg = "删除 MinIO bucket 失败，可能 bucket 不为空: " + minioException.getMessage();
                    ec.getMessage().addError(errorMsg);
                    logBucketOperation(ec, bucketId, userId, "DELETE", null, 0L, "FAILURE", minioException.getMessage());
                    ec.getLogger().error(errorMsg, minioException);
                    return result;
                }
            } else {
                ec.getLogger().info("MinIO 中的 bucket 不存在: " + bucketId);
            }

            // 更新 bucket 状态为 DELETED
            ec.getLogger().info("开始更新数据库中的 bucket 状态为 DELETED: " + bucketId);
            bucketRecord.set("status", "DELETED");
            bucketRecord.set("lastModifiedDate", new Timestamp(System.currentTimeMillis()));
            bucketRecord.update();
            ec.getLogger().info("成功更新数据库中的 bucket 状态为 DELETED: " + bucketId);

            // 删除相关权限记录
            ec.getLogger().info("开始删除相关的权限记录: " + bucketId);
            EntityList permissionRecords = ec.getEntity().find("moqui.minio.BucketPermission")
                    .condition("bucketId", bucketId)
                    .list();
            for (EntityValue permission : permissionRecords) {
                permission.delete();
            }
            ec.getLogger().info("成功删除相关的权限记录: " + bucketId + ", 数量=" + permissionRecords.size());

            // 记录操作日志
            logBucketOperation(ec, bucketId, userId, "DELETE", null, 0L, "SUCCESS", null);

            ec.getLogger().info("成功删除 bucket: bucketId=" + bucketId + ", userId=" + userId);

            result.put("success", true);

        } catch (Exception e) {
            String errorMsg = "删除 bucket 失败: " + e.getMessage();
            logBucketOperation(ec, (String) parameters.get("bucketId"), (String) parameters.get("userId"),
                    "DELETE", null, 0L, "FAILURE", e.getMessage());
            ec.getMessage().addError(errorMsg);
            result.put("success", false);
            ec.getLogger().error("Failed to delete MinIO bucket", e);
        }

        return result;
    }

    public static Map<String, Object> updateBucket(ExecutionContext ec) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> parameters = ec.getContext();

        try {
            String bucketId = (String) parameters.get("bucketId");
            String userId = (String) parameters.get("userId");

            if (bucketId == null || bucketId.trim().isEmpty()) {
                ec.getMessage().addError("bucketId 不能为空");
                return result;
            }
            if (userId == null || userId.trim().isEmpty()) {
                ec.getMessage().addError("userId 不能为空");
                return result;
            }

            // 查找数据库中的 bucket
            EntityValue bucketRecord = ec.getEntity().find("moqui.minio.Bucket")
                    .condition("bucketId", bucketId)
                    .condition("userId", userId)
                    .one();
            if (bucketRecord == null) {
                ec.getMessage().addError("未找到 bucketId=" + bucketId + " 的 bucket 或无权限访问");
                return result;
            }

            // 更新允许的字段
            if (parameters.get("bucketName") != null)
                bucketRecord.set("bucketName", parameters.get("bucketName"));
            if (parameters.get("description") != null)
                bucketRecord.set("description", parameters.get("description"));
            if (parameters.get("quotaLimit") != null)
                bucketRecord.set("quotaLimit", parameters.get("quotaLimit"));
            if (parameters.get("tags") != null)
                bucketRecord.set("tags", parameters.get("tags"));

            bucketRecord.set("lastModifiedDate", new Timestamp(System.currentTimeMillis()));
            bucketRecord.update();

            // 暂不直接修改 MinIO bucket 属性（MinIO API 不支持 quota/tag 原生管理）
            logBucketOperation(ec, bucketId, userId, "UPDATE", null, 0L, "SUCCESS", null);

            result.put("success", true);
        } catch (Exception e) {
            logBucketOperation(ec, (String) parameters.get("bucketId"), (String) parameters.get("userId"),
                    "UPDATE", null, 0L, "FAILURE", e.getMessage());
            ec.getMessage().addError("更新 bucket 失败: " + e.getMessage());
            result.put("success", false);
            ec.getLogger().error("Failed to update MinIO bucket", e);
        }
        return result;
    }

    public static Map<String, Object> getBucket(ExecutionContext ec) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> parameters = ec.getContext();

        try {
            String bucketId = (String) parameters.get("bucketId");
            String userId = (String) parameters.get("userId");

            if (bucketId == null || bucketId.trim().isEmpty()) {
                ec.getMessage().addError("bucketId 不能为空");
                return result;
            }
            if (userId == null || userId.trim().isEmpty()) {
                ec.getMessage().addError("userId 不能为空");
                return result;
            }

            // 查找数据库中的 bucket
            EntityValue bucketRecord = ec.getEntity().find("moqui.minio.Bucket")
                    .condition("bucketId", bucketId)
                    .condition("userId", userId)
                    .one();
            if (bucketRecord == null) {
                ec.getMessage().addError("未找到 bucketId=" + bucketId + " 的 bucket 或无权限访问");
                return result;
            }

            Map<String, Object> bucketInfo = new HashMap<>();
            bucketInfo.put("bucketId", bucketRecord.getString("bucketId"));
            bucketInfo.put("userId", bucketRecord.getString("userId"));
            bucketInfo.put("bucketName", bucketRecord.getString("bucketName"));
            bucketInfo.put("description", bucketRecord.getString("description"));
            bucketInfo.put("quotaLimit", bucketRecord.getLong("quotaLimit"));
            bucketInfo.put("usedStorage", bucketRecord.getLong("usedStorage"));
            bucketInfo.put("status", bucketRecord.getString("status"));
            bucketInfo.put("isPublic", bucketRecord.getString("isPublic"));
            bucketInfo.put("versioning", bucketRecord.getString("versioning"));
            bucketInfo.put("encryption", bucketRecord.getString("encryption"));
            bucketInfo.put("createdDate", bucketRecord.getTimestamp("createdDate"));
            bucketInfo.put("lastModifiedDate", bucketRecord.getTimestamp("lastModifiedDate"));
            bucketInfo.put("tags", bucketRecord.getString("tags"));

            // 检查 MinIO 中的实际状态
            boolean existsInMinio = false;
            try {
                MinioClient minioClient = createMinioClient(ec);
                existsInMinio = minioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(bucketId).build()
                );
            } catch (Exception e) {
                ec.getLogger().warn("检查 MinIO bucket 状态失败: " + bucketId, e);
            }
            bucketInfo.put("existsInMinio", existsInMinio);

            // 如果 MinIO 已删除但数据库是 ACTIVE，更新为 ERROR
            if (!existsInMinio && "ACTIVE".equals(bucketRecord.getString("status"))) {
                bucketRecord.set("status", "ERROR");
                bucketRecord.set("lastModifiedDate", new Timestamp(System.currentTimeMillis()));
                bucketRecord.update();
                bucketInfo.put("status", "ERROR");
            }

            logBucketOperation(ec, bucketId, userId, "GET", null, 0L, "SUCCESS", null);

            result.put("bucketInfo", bucketInfo);
            result.put("success", true);
        } catch (Exception e) {
            logBucketOperation(ec, (String) parameters.get("bucketId"), (String) parameters.get("userId"),
                    "GET", null, 0L, "FAILURE", e.getMessage());
            ec.getMessage().addError("获取 bucket 失败: " + e.getMessage());
            result.put("success", false);
            ec.getLogger().error("Failed to get MinIO bucket", e);
        }

        return result;
    }


    public static Map<String, Object> listBucket(ExecutionContext ec) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> parameters = ec.getContext();

        try {
            String userId = (String) parameters.get("userId");
            String bucketId = (String) parameters.get("bucketId");
            String bucketName = (String) parameters.get("bucketName");
            String description = (String) parameters.get("description");
            String status = (String) parameters.get("status");
            String isPublic = (String) parameters.get("isPublic");

            // 检查用户是否具有管理员权限
            boolean isAdmin = ec.getUser().isInGroup("ADMIN") || ec.getUser().isInGroup("ADMIN_ADV");
            String currentUserId = ec.getUser().getUserId();

            // 权限逻辑：
            // 1. 如果userId为null且是管理员 -> 查看所有用户的存储桶
            // 2. 如果userId为null且不是管理员 -> 只查看当前用户的存储桶
            // 3. 如果userId不为null -> 查看指定用户的存储桶（需要权限验证）
            String queryUserId = userId;
            boolean viewAllUsers = false;

            if (userId == null || userId.trim().isEmpty()) {
                if (isAdmin) {
                    // 管理员可以查看所有用户的存储桶
                    viewAllUsers = true;
                    ec.getLogger().info("管理员用户 " + currentUserId + " 查看所有用户的存储桶");
                } else {
                    // 普通用户只能查看自己的存储桶
                    queryUserId = currentUserId;
                    ec.getLogger().info("普通用户 " + currentUserId + " 查看自己的存储桶");
                }
            } else {
                // 指定了特定用户ID
                if (!isAdmin && !userId.equals(currentUserId)) {
                    ec.getMessage().addError("没有权限查看其他用户的存储桶");
                    return result;
                }
                ec.getLogger().info("查看用户 " + userId + " 的存储桶");
            }

            Integer pageIndex = (Integer) parameters.getOrDefault("pageIndex", 0);
            Integer pageSize = (Integer) parameters.getOrDefault("pageSize", 20);
            int offset = pageIndex * pageSize;

            // 构建查询
            EntityFind bucketFind = ec.getEntity().find("moqui.minio.Bucket")
                    .condition("status", "!=", "DELETED")
                    .orderBy("createdDate");

            // 根据权限逻辑添加userId条件
            if (!viewAllUsers) {
                bucketFind.condition("userId", queryUserId);
            }

            // 应用其他过滤条件
            if (bucketId != null && !bucketId.trim().isEmpty()) {
                bucketFind.condition("bucketId", "like", "%" + bucketId + "%");
            }
            if (bucketName != null && !bucketName.trim().isEmpty()) {
                bucketFind.condition("bucketName", "like", "%" + bucketName + "%");
            }
            if (description != null && !description.trim().isEmpty()) {
                bucketFind.condition("description", "like", "%" + description + "%");
            }
            if (status != null && !status.trim().isEmpty()) {
                bucketFind.condition("status", status);
            }
            if (isPublic != null) {
                bucketFind.condition("isPublic", isPublic);
            }

            // 先计算总数（clone 一份，避免 offset/limit 影响）
            long totalCount = bucketFind.useClone(true).count();

            EntityList bucketRecords = bucketFind.offset(offset).limit(pageSize).orderBy("createdDate DESC").list();

            List<Map<String, Object>> bucketList = new ArrayList<>();

            // 获取 MinIO 客户端
            MinioClient minioClient = null;
            try {
                minioClient = createMinioClient(ec);
            } catch (Exception e) {
                ec.getLogger().warn("无法获取 MinIO 客户端，将跳过状态检查", e);
            }

            for (EntityValue bucketRecord : bucketRecords) {
                Map<String, Object> bucketInfo = new HashMap<>();
                String currentBucketId = bucketRecord.getString("bucketId");

                // 基本信息
                bucketInfo.put("bucketId", currentBucketId);
                bucketInfo.put("userId", bucketRecord.getString("userId"));
                bucketInfo.put("bucketName", bucketRecord.getString("bucketName"));
                bucketInfo.put("description", bucketRecord.getString("description"));
                bucketInfo.put("quotaLimit", bucketRecord.getLong("quotaLimit"));

                // 实时统计文件信息
                long actualUsedStorage = 0L;
                int fileCount = 0;
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
                                        .bucket(currentBucketId)
                                        .build()
                        );
                        bucketInfo.put("existsInMinio", exists);

                        // 如果bucket存在，统计文件信息
                        if (exists) {
                            try {
                                Iterable<Result<Item>> objects = minioClient.listObjects(
                                        ListObjectsArgs.builder()
                                                .bucket(currentBucketId)
                                                .recursive(true)
                                                .build()
                                );

                                for (Result<Item> objectResult : objects) {
                                    Item item = objectResult.get();
                                    if (!item.isDir()) {
                                        fileCount++;
                                        actualUsedStorage += item.size();
                                    }
                                }
                            } catch (Exception e) {
                                ec.getLogger().warn("获取bucket " + currentBucketId + " 文件统计失败", e);
                            }
                        }

                        // 如果状态不一致，更新数据库记录
                        if (!exists && "ACTIVE".equals(bucketRecord.getString("status"))) {
                            bucketRecord.set("status", "ERROR");
                            bucketRecord.set("lastModifiedDate", new Timestamp(System.currentTimeMillis()));
                            bucketRecord.update();
                            bucketInfo.put("status", "ERROR");
                        }

                        // 更新数据库中的usedStorage（如果实际值与数据库不同）
                        Long dbUsedStorage = bucketRecord.getLong("usedStorage");
                        if (exists && dbUsedStorage != null && Math.abs(dbUsedStorage - actualUsedStorage) > 1024) { // 差异超过1KB才更新
                            bucketRecord.set("usedStorage", actualUsedStorage);
                            bucketRecord.set("lastModifiedDate", new Timestamp(System.currentTimeMillis()));
                            bucketRecord.update();
                        }

                    } catch (Exception e) {
                        ec.getLogger().warn("检查 bucket 状态失败: " + currentBucketId, e);
                        bucketInfo.put("existsInMinio", false);
                    }
                } else {
                    bucketInfo.put("existsInMinio", null);
                }

                // 设置统计信息
                bucketInfo.put("usedStorage", actualUsedStorage);
                bucketInfo.put("fileCount", fileCount);

                bucketList.add(bucketInfo);
            }

            // 记录查询操作
            logBucketOperation(ec, null, queryUserId != null ? queryUserId : currentUserId, "LIST", null, 0L, "SUCCESS", null);

            ec.getLogger().info("查询存储桶成功: 当前用户=" + currentUserId +
                    ", 查询用户=" + (queryUserId != null ? queryUserId : "所有用户") +
                    ", 找到 " + bucketList.size() + " 个存储桶");

            // 设置标准的分页输出参数
            result.put("bucketList", bucketList);
            result.put("bucketListCount", (int) totalCount);
            result.put("bucketListPageIndex", pageIndex);
            result.put("bucketListPageSize", pageSize);
            result.put("bucketListPageMaxIndex", pageSize > 0 ? (int) Math.ceil((double) totalCount / pageSize) - 1 : 0);
            result.put("bucketListPageRangeLow", pageIndex * pageSize + 1);
            result.put("bucketListPageRangeHigh", Math.min((pageIndex + 1) * pageSize, (int) totalCount));
            result.put("success", true);

        } catch (Exception e) {
            logBucketOperation(ec, null, (String) parameters.get("userId"), "LIST", null, 0L, "FAILURE", e.getMessage());
            ec.getMessage().addError("查询存储桶列表失败: " + e.getMessage());
            result.put("success", false);
            ec.getLogger().error("Failed to list MinIO buckets", e);
        }

        return result;
    }

    public static Map<String, Object> uploadObject(ExecutionContext ec) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> parameters = ec.getContext();

        String bucketId = (String) parameters.get("bucketId");
        String userId = (String) parameters.get("userId");
        String objectName = (String) parameters.get("objectName");
        byte[] fileBytes = (byte[]) parameters.get("fileBytes"); // Moqui 传输的文件内容

        try {
            MinioClient client = createMinioClient(ec);

            // 检查桶存在
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucketId).build())) {
                ec.getMessage().addError("Bucket 不存在: " + bucketId);
                return result;
            }

            // 上传文件
            client.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketId)
                            .object(objectName)
                            .stream(new ByteArrayInputStream(fileBytes), fileBytes.length, -1)
                            .build()
            );

            long fileSize = fileBytes.length;

            // 更新数据库 usedStorage
            EntityValue bucketRecord = ec.getEntity().find("moqui.minio.Bucket")
                    .condition("bucketId", bucketId).one();
            if (bucketRecord != null) {
                long usedStorage = bucketRecord.getLong("usedStorage") != null ? bucketRecord.getLong("usedStorage") : 0L;
                bucketRecord.set("usedStorage", usedStorage + fileSize);
                bucketRecord.set("lastModifiedDate", new Timestamp(System.currentTimeMillis()));
                bucketRecord.update();
            }

            // 写日志
            logBucketOperation(ec, bucketId, userId, "UPLOAD", objectName, fileSize, "SUCCESS", null);

            result.put("success", true);
            result.put("objectName", objectName);
            result.put("objectSize", fileSize);
        } catch (Exception e) {
            logBucketOperation(ec, bucketId, userId, "UPLOAD", objectName, 0L, "FAILURE", e.getMessage());
            result.put("success", false);
            ec.getMessage().addError("上传对象失败: " + e.getMessage());
            ec.getLogger().error("Upload failed", e);
        }
        return result;
    }

    public static Map<String, Object> deleteObject(ExecutionContext ec) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> parameters = ec.getContext();

        String bucketId = (String) parameters.get("bucketId");
        String userId = (String) parameters.get("userId");
        String objectName = (String) parameters.get("objectName");

        try {
            MinioClient client = createMinioClient(ec);

            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketId)
                    .object(objectName)
                    .build());

            // ⚠️ 这里没有直接获取 objectSize，只能在上传时记录（可考虑扩展 ObjectMeta 表）
            long fileSize = 0L;

            // 更新 usedStorage（如果能知道对象大小）
            // TODO: 可在 BucketUsageLog 中查询最后一次 UPLOAD 记录获取 objectSize
            EntityValue bucketRecord = ec.getEntity().find("moqui.minio.Bucket")
                    .condition("bucketId", bucketId).one();
            if (bucketRecord != null) {
                Long usedStorage = bucketRecord.getLong("usedStorage");
                if (usedStorage != null && usedStorage > fileSize) {
                    bucketRecord.set("usedStorage", usedStorage - fileSize);
                    bucketRecord.update();
                }
            }

            logBucketOperation(ec, bucketId, userId, "DELETE", objectName, fileSize, "SUCCESS", null);

            result.put("success", true);
        } catch (Exception e) {
            logBucketOperation(ec, bucketId, userId, "DELETE", objectName, 0L, "FAILURE", e.getMessage());
            result.put("success", false);
            ec.getMessage().addError("删除对象失败: " + e.getMessage());
        }
        return result;
    }

    public static Map<String, Object> listObjects(ExecutionContext ec) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> parameters = ec.getContext();

        String bucketId = (String) parameters.get("bucketId");
        String userId = (String) parameters.get("userId");

        try {
            MinioClient client = createMinioClient(ec);
            Iterable<Result<Item>> objects = client.listObjects(
                    ListObjectsArgs.builder().bucket(bucketId).recursive(true).build()
            );

            List<Map<String, Object>> objectList = new ArrayList<>();
            for (Result<Item> itemResult : objects) {
                Item item = itemResult.get();
                Map<String, Object> obj = new HashMap<>();
                obj.put("objectName", item.objectName());
                obj.put("size", item.size());
                obj.put("lastModified", item.lastModified());
                obj.put("etag", item.etag());
                obj.put("isDir", item.isDir());
                objectList.add(obj);
            }

            logBucketOperation(ec, bucketId, userId, "LIST", null, 0L, "SUCCESS", null);

            result.put("success", true);
            result.put("objects", objectList);
        } catch (Exception e) {
            logBucketOperation(ec, bucketId, userId, "LIST", null, 0L, "FAILURE", e.getMessage());
            result.put("success", false);
            ec.getMessage().addError("列举对象失败: " + e.getMessage());
        }
        return result;
    }

    public static Map<String, Object> downloadObject(ExecutionContext ec) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> parameters = ec.getContext();

        String bucketId = (String) parameters.get("bucketId");
        String userId = (String) parameters.get("userId");
        String objectName = (String) parameters.get("objectName");

        try {
            MinioClient client = createMinioClient(ec);

            // 生成预签名 URL（有效期1小时）
            String url = client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketId)
                            .object(objectName)
                            .expiry(60 * 60) // 秒
                            .build()
            );

            logBucketOperation(ec, bucketId, userId, "DOWNLOAD", objectName, 0L, "SUCCESS", null);

            result.put("success", true);
            result.put("downloadUrl", url);
        } catch (Exception e) {
            logBucketOperation(ec, bucketId, userId, "DOWNLOAD", objectName, 0L, "FAILURE", e.getMessage());
            result.put("success", false);
            ec.getMessage().addError("下载对象失败: " + e.getMessage());
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

    // 验证S3存储桶命名规则
    private static boolean isValidS3BucketName(String bucketName) {
        if (bucketName == null || bucketName.length() < 3 || bucketName.length() > 63) {
            return false;
        }

        // 只能包含小写字母、数字和连字符
        if (!bucketName.matches("^[a-z0-9-]+$")) {
            return false;
        }

        // 不能以连字符开头或结尾
        if (bucketName.startsWith("-") || bucketName.endsWith("-")) {
            return false;
        }

        // 不能包含连续的连字符
        if (bucketName.contains("--")) {
            return false;
        }

        return true;
    }
}