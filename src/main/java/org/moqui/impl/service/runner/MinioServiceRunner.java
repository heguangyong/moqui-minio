package org.moqui.impl.service.runner;

import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityFind;

import java.io.ByteArrayInputStream;
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
            MinioClient minioClient = createMinioClient();
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
                MinioClient minioClient = createMinioClient();
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
            String status = (String) parameters.get("status");
            String scope = (String) parameters.get("scope");

            // 获取分页参数
            Integer pageIndex = parameters.get("pageIndex") != null ?
                    Integer.valueOf(parameters.get("pageIndex").toString()) : 0;
            Integer pageSize = parameters.get("pageSize") != null ?
                    Integer.valueOf(parameters.get("pageSize").toString()) : 20;

            ec.getLogger().info("listBucket - 参数: userId=" + userId +
                    ", scope=" + scope + ", pageIndex=" + pageIndex + ", pageSize=" + pageSize);

            // 参数验证
            if (userId == null || userId.trim().isEmpty()) {
                ec.getMessage().addError("userId 不能为空");
                return result;
            }

            // 检查用户是否具有管理员权限
            boolean isAdmin = ec.getUser().isInGroup("ADMIN") || ec.getUser().isInGroup("ADMIN_ADV");

            // 第一步：获取MinIO中的所有存储桶
            MinioClient minioClient = null;
            List<String> minioBucketNames = new ArrayList<>();

            try {
                minioClient = createMinioClient();
                // 获取MinIO中的所有存储桶
                List<Bucket> minioBuckets = minioClient.listBuckets();
                for (Bucket bucket : minioBuckets) {
                    minioBucketNames.add(bucket.name());
                }
                ec.getLogger().info("从MinIO获取到 " + minioBucketNames.size() + " 个存储桶");
            } catch (Exception e) {
                ec.getLogger().warn("无法获取MinIO存储桶列表，将只显示数据库记录", e);
            }

            // 第二步：查询数据库记录
            EntityFind bucketFind = ec.getEntity().find("moqui.minio.Bucket")
                    .condition("status", "!=", "DELETED")
                    .orderBy("createdDate");

            // 权限控制
            if (isAdmin && "all".equals(scope)) {
                // 管理员查看所有桶，不添加userId条件
                ec.getLogger().info("管理员用户 " + userId + " 查看所有网盘");
            } else {
                // 普通用户只查看自己的桶
                bucketFind.condition("userId", userId);
            }

            // 应用过滤条件
            if (status != null && !status.trim().isEmpty()) {
                bucketFind.condition("status", status);
            }

            EntityList bucketRecords = bucketFind.list();

            // 第三步：合并MinIO和数据库数据
            Map<String, EntityValue> dbBucketMap = new HashMap<>();
            for (EntityValue bucketRecord : bucketRecords) {
                dbBucketMap.put(bucketRecord.getString("bucketId"), bucketRecord);
            }

            List<Map<String, Object>> allBucketList = new ArrayList<>();

            // 处理MinIO中的存储桶
            for (String bucketName : minioBucketNames) {
                EntityValue dbRecord = dbBucketMap.get(bucketName);
                Map<String, Object> bucketInfo = new HashMap<>();

                if (dbRecord != null) {
                    // 数据库中有记录
                    bucketInfo.put("bucketId", dbRecord.getString("bucketId"));
                    bucketInfo.put("userId", dbRecord.getString("userId"));
                    bucketInfo.put("bucketName", dbRecord.getString("bucketName"));
                    bucketInfo.put("quotaLimit", dbRecord.getLong("quotaLimit"));
                    bucketInfo.put("usedStorage", dbRecord.getLong("usedStorage"));
                    bucketInfo.put("status", dbRecord.getString("status"));
                    bucketInfo.put("isPublic", dbRecord.getString("isPublic"));
                    bucketInfo.put("versioning", dbRecord.getString("versioning"));
                    bucketInfo.put("encryption", dbRecord.getString("encryption"));
                    bucketInfo.put("createdDate", dbRecord.getTimestamp("createdDate"));
                    bucketInfo.put("lastModifiedDate", dbRecord.getTimestamp("lastModifiedDate"));
                    bucketInfo.put("description", dbRecord.getString("description"));
                    bucketInfo.put("existsInMinio", true);

                    // 如果状态不是ACTIVE，更新为ACTIVE
                    if (!"ACTIVE".equals(dbRecord.getString("status"))) {
                        dbRecord.set("status", "ACTIVE");
                        dbRecord.set("lastModifiedDate", new Timestamp(System.currentTimeMillis()));
                        dbRecord.update();
                        bucketInfo.put("status", "ACTIVE");
                    }

                    // 权限检查：如果不是管理员且scope不是all，只显示用户自己的桶
                    if (!isAdmin || !"all".equals(scope)) {
                        if (!userId.equals(dbRecord.getString("userId"))) {
                            continue; // 跳过不属于当前用户的桶
                        }
                    }

                } else {
                    // MinIO中存在但数据库中没有记录，创建临时记录显示
                    if (isAdmin && "all".equals(scope)) {
                        bucketInfo.put("bucketId", bucketName);
                        bucketInfo.put("userId", "unknown");
                        bucketInfo.put("bucketName", bucketName);
                        bucketInfo.put("quotaLimit", 0L);
                        bucketInfo.put("usedStorage", 0L);
                        bucketInfo.put("status", "UNKNOWN");
                        bucketInfo.put("isPublic", "N");
                        bucketInfo.put("versioning", "N");
                        bucketInfo.put("encryption", "N");
                        bucketInfo.put("createdDate", null);
                        bucketInfo.put("lastModifiedDate", null);
                        bucketInfo.put("description", "MinIO中存在，但数据库无记录");
                        bucketInfo.put("existsInMinio", true);
                    } else {
                        continue; // 普通用户跳过未知桶
                    }
                }

                allBucketList.add(bucketInfo);
            }

            // 处理数据库中存在但MinIO中不存在的桶
            for (EntityValue dbRecord : bucketRecords) {
                String bucketId = dbRecord.getString("bucketId");
                if (!minioBucketNames.contains(bucketId)) {
                    // 权限检查
                    if (!isAdmin || !"all".equals(scope)) {
                        if (!userId.equals(dbRecord.getString("userId"))) {
                            continue;
                        }
                    }

                    Map<String, Object> bucketInfo = new HashMap<>();
                    bucketInfo.put("bucketId", dbRecord.getString("bucketId"));
                    bucketInfo.put("userId", dbRecord.getString("userId"));
                    bucketInfo.put("bucketName", dbRecord.getString("bucketName"));
                    bucketInfo.put("quotaLimit", dbRecord.getLong("quotaLimit"));
                    bucketInfo.put("usedStorage", dbRecord.getLong("usedStorage"));
                    bucketInfo.put("status", "ERROR"); // MinIO中不存在，状态标记为ERROR
                    bucketInfo.put("isPublic", dbRecord.getString("isPublic"));
                    bucketInfo.put("versioning", dbRecord.getString("versioning"));
                    bucketInfo.put("encryption", dbRecord.getString("encryption"));
                    bucketInfo.put("createdDate", dbRecord.getTimestamp("createdDate"));
                    bucketInfo.put("lastModifiedDate", dbRecord.getTimestamp("lastModifiedDate"));
                    bucketInfo.put("description", dbRecord.getString("description"));
                    bucketInfo.put("existsInMinio", false);

                    // 更新数据库状态为ERROR
                    if (!"ERROR".equals(dbRecord.getString("status"))) {
                        dbRecord.set("status", "ERROR");
                        dbRecord.set("lastModifiedDate", new Timestamp(System.currentTimeMillis()));
                        dbRecord.update();
                    }

                    allBucketList.add(bucketInfo);
                }
            }

            // 第四步：实现分页
            int totalCount = allBucketList.size();
            int startIndex = pageIndex * pageSize;
            int endIndex = Math.min(startIndex + pageSize, totalCount);

            List<Map<String, Object>> pagedBucketList = new ArrayList<>();
            if (startIndex < totalCount) {
                pagedBucketList = allBucketList.subList(startIndex, endIndex);
            }

            // 记录查询操作
            logBucketOperation(ec, null, userId, "LIST", null, 0L, "SUCCESS", null);

            ec.getLogger().info("查询存储桶成功: userId=" + userId +
                    ", 总数=" + totalCount + ", 分页=" + pagedBucketList.size() +
                    ", pageIndex=" + pageIndex + ", pageSize=" + pageSize);

            result.put("bucketList", pagedBucketList);
            result.put("totalCount", totalCount);
            result.put("bucketListCount", totalCount); // 为兼容性添加
            result.put("bucketListPageIndex", pageIndex);
            result.put("bucketListPageSize", pageSize);
            result.put("success", true);

        } catch (Exception e) {
            logBucketOperation(ec, null, (String) parameters.get("userId"), "LIST", null, 0L, "FAILURE", e.getMessage());
            ec.getMessage().addError("查询 bucket 列表失败: " + e.getMessage());
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
            MinioClient client = createMinioClient();

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
            MinioClient client = createMinioClient();

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
            MinioClient client = createMinioClient();
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
            MinioClient client = createMinioClient();

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
}