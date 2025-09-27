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
package org.moqui.impl.service.minio;

import org.apache.commons.fileupload.FileItem;
import org.moqui.context.ExecutionContext;
import org.moqui.resource.ResourceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import io.minio.StatObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.errors.MinioException;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.Scanner;

/** Used by the minio.MinioElFinderServices.run#ElfinderCommand service. */
public class MinioElFinderConnector {
    protected final static Logger logger = LoggerFactory.getLogger(MinioElFinderConnector.class);

    ExecutionContext ec;
    String bucketName;
    MinioClient minioClient;

    public MinioElFinderConnector(ExecutionContext ec, String bucketName) {
        this.ec = ec;
        this.bucketName = bucketName;
        // 创建MinioClient实例，与MinioServiceRunner中的方法一致
        this.minioClient = createMinioClient();
    }

    // 创建 MinIO 客户端的辅助方法，与MinioServiceRunner保持一致
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

    public String hash(String str) {
        try {
            byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
            String hashed = Base64.getEncoder().encodeToString(bytes);
            hashed = hashed.replace("=", "");
            hashed = hashed.replace("+", "-");
            hashed = hashed.replace("/", "_");
            // 使用bucketName作为前缀，而不是固定的volumeId
            return bucketName + "_" + hashed;
        } catch (Exception e) {
            logger.error("Error hashing string: " + str, e);
            return "";
        }
    }

    public static String unhash(String hashed) {
        if (hashed == null || hashed.isEmpty()) return "";
        try {
            // 移除bucketName前缀
            int prefixIndex = hashed.indexOf("_");
            if (prefixIndex > 0) {
                hashed = hashed.substring(prefixIndex + 1);
            }
            hashed = hashed.replace(".", "=");
            hashed = hashed.replace("-", "+");
            hashed = hashed.replace("_", "/");
            byte[] decoded = Base64.getDecoder().decode(hashed);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Error unhashing string: " + hashed, e);
            return "";
        }
    }

    public String getLocation(String hashed) {
        if (hashed != null && !hashed.isEmpty()) {
            String unhashedPath = unhash(hashed);
            if (unhashedPath.equals("/") || unhashedPath.equals("root")) return "minio://" + bucketName + "/";
            if (unhashedPath.startsWith("/")) unhashedPath = unhashedPath.substring(1);
            return "minio://" + bucketName + "/" + unhashedPath;
        }
        return "minio://" + bucketName + "/";
    }

    public String getPathRelativeToRoot(String location) {
        String path = location.trim();
        String prefix = "minio://" + bucketName + "/";
        if (!location.startsWith(prefix)) {
            logger.warn("Location [" + location + "] does not start with prefix [" + prefix + "]! Returning full location as relative path to root");
            return location;
        }
        path = path.substring(prefix.length());
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.startsWith("/")) path = path.substring(1);
        if (path.equals("")) return "root";
        return path;
    }

    public boolean isRoot(String location) { 
        return getPathRelativeToRoot(location).equals("root"); 
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getLocationInfo(String location) {
        try {
            String objectName = location.substring(("minio://" + bucketName + "/").length());
            String originalObjectName = objectName;
            if (objectName.endsWith("/")) objectName = objectName.substring(0, objectName.length() - 1);

            // 如果是根目录
            if (objectName.equals("") || objectName.equals("root")) {
                Map<String, Object> info = new HashMap<>();
                info.put("name", bucketName);
                info.put("hash", hash("root"));
                info.put("mime", "directory");
                info.put("dirs", 1);
                info.put("read", 1);
                info.put("write", 1);
                info.put("locked", 0);
                return info;
            }

            // 首先检查是否是目录（通过检查是否有子对象）
            boolean isDirectory = originalObjectName.endsWith("/") || hasChildDirectories(objectName + "/");

            if (isDirectory) {
                // 处理目录
                Map<String, Object> info = new HashMap<>();
                String name = objectName.contains("/") ? objectName.substring(objectName.lastIndexOf("/") + 1) : objectName;
                info.put("name", name);
                info.put("hash", hash(objectName + "/"));

                String parentPath = objectName.contains("/") ? objectName.substring(0, objectName.lastIndexOf("/")) : "root";
                info.put("phash", hash(parentPath));

                info.put("mime", "directory");
                info.put("ts", System.currentTimeMillis()); // 目录使用当前时间
                info.put("size", 0); // 目录大小为0
                info.put("dirs", hasChildDirectories(objectName + "/") ? 1 : 0);
                info.put("read", 1);
                info.put("write", 1);
                info.put("locked", 0);

                return info;
            } else {
                // 处理文件 - 尝试获取对象信息
                StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder().bucket(bucketName).object(objectName).build());

                Map<String, Object> info = new HashMap<>();
                String name = objectName.contains("/") ? objectName.substring(objectName.lastIndexOf("/") + 1) : objectName;
                info.put("name", name);
                info.put("hash", hash(objectName));

                String parentPath = objectName.contains("/") ? objectName.substring(0, objectName.lastIndexOf("/")) : "root";
                info.put("phash", hash(parentPath));

                info.put("mime", "application/octet-stream");
                info.put("ts", stat.lastModified().toInstant().toEpochMilli());
                info.put("size", stat.size());
                info.put("dirs", 0);
                info.put("read", 1);
                info.put("write", 1);
                info.put("locked", 0);

                return info;
            }
        } catch (Exception e) {
            logger.error("Error getting location info for " + location, e);
            return new HashMap<>();
        }
    }

    public boolean hasChildDirectories(String objectName) {
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucketName)
                .prefix(objectName.endsWith("/") ? objectName : objectName + "/")
                .recursive(false)
                .build());
            
            for (Result<Item> result : results) {
                Item item = result.get();
                if (item.objectName().endsWith("/")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            logger.error("Error checking child directories for " + objectName, e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getFiles(String target, boolean tree) {
        logger.info("getFiles called with target: " + target + ", tree: " + tree);
        List<Map<String, Object>> files = new ArrayList<>();
        String location = getLocation(target);
        String objectName = location.substring(("minio://" + bucketName + "/").length());
        logger.info("location: " + location + ", objectName: " + objectName);
        
        if (objectName.equals("") || objectName.equals("root")) {
            // 获取根目录下的所有对象
            files.add(getLocationInfo("minio://" + bucketName + "/"));
            
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucketName)
                .prefix("")
                .recursive(false)
                .build());
            
            for (Result<Item> result : results) {
                try {
                    Item item = result.get();
                    String itemName = item.objectName();
                    logger.info("Processing item: " + itemName + " (isDir: " + item.isDir() + ")");
                    if (!itemName.contains("/")) {
                        // 根目录下的文件
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", itemName);
                        info.put("hash", hash(itemName));
                        info.put("phash", hash("root"));
                        info.put("mime", "application/octet-stream");
                        try {
                            if (item.lastModified() != null) {
                                info.put("ts", item.lastModified().toInstant().toEpochMilli());
                            } else {
                                info.put("ts", System.currentTimeMillis());
                            }
                        } catch (Exception tsException) {
                            logger.warn("Could not get lastModified for file " + itemName + ": " + tsException.getMessage());
                            info.put("ts", System.currentTimeMillis());
                        }
                        info.put("size", item.size());
                        info.put("dirs", 0);
                        info.put("read", 1);
                        info.put("write", 1);
                        info.put("locked", 0);
                        files.add(info);
                    } else if (itemName.indexOf("/") == itemName.length() - 1) {
                        // 根目录下的目录
                        logger.info("Found directory: " + itemName);
                        String dirName = itemName.substring(0, itemName.length() - 1);
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", dirName);
                        info.put("hash", hash(dirName + "/"));
                        info.put("phash", hash("root"));
                        info.put("mime", "directory");
                        try {
                            if (item.lastModified() != null) {
                                info.put("ts", item.lastModified().toInstant().toEpochMilli());
                            } else {
                                info.put("ts", System.currentTimeMillis());
                            }
                        } catch (Exception tsException) {
                            logger.warn("Could not get lastModified for directory " + dirName + ": " + tsException.getMessage());
                            info.put("ts", System.currentTimeMillis());
                        }
                        info.put("size", item.size());
                        info.put("dirs", hasChildDirectories(dirName + "/") ? 1 : 0);
                        info.put("read", 1);
                        info.put("write", 1);
                        info.put("locked", 0);
                        files.add(info);
                    }
                } catch (Exception e) {
                    logger.error("Error processing item", e);
                }
            }
        } else {
            // 获取指定目录下的所有对象
            files.add(getLocationInfo(location));
            
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucketName)
                .prefix(objectName.endsWith("/") ? objectName : objectName + "/")
                .recursive(false)
                .build());
            
            for (Result<Item> result : results) {
                try {
                    Item item = result.get();
                    String itemName = item.objectName();
                    String relativeName = itemName.substring(objectName.length() + (objectName.endsWith("/") ? 0 : 1));
                    
                    if (!relativeName.contains("/")) {
                        // 当前目录下的文件
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", relativeName);
                        info.put("hash", hash(itemName));
                        info.put("phash", hash(objectName));
                        info.put("mime", "application/octet-stream");
                        try {
                            if (item.lastModified() != null) {
                                info.put("ts", item.lastModified().toInstant().toEpochMilli());
                            } else {
                                info.put("ts", System.currentTimeMillis());
                            }
                        } catch (Exception tsException) {
                            logger.warn("Could not get lastModified for item: " + tsException.getMessage());
                            info.put("ts", System.currentTimeMillis());
                        }
                        info.put("size", item.size());
                        info.put("dirs", 0);
                        info.put("read", 1);
                        info.put("write", 1);
                        info.put("locked", 0);
                        files.add(info);
                    } else if (relativeName.indexOf("/") == relativeName.length() - 1) {
                        // 当前目录下的目录
                        String dirName = relativeName.substring(0, relativeName.length() - 1);
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", dirName);
                        info.put("hash", hash(itemName));
                        info.put("phash", hash(objectName));
                        info.put("mime", "directory");
                        try {
                            if (item.lastModified() != null) {
                                info.put("ts", item.lastModified().toInstant().toEpochMilli());
                            } else {
                                info.put("ts", System.currentTimeMillis());
                            }
                        } catch (Exception tsException) {
                            logger.warn("Could not get lastModified for item: " + tsException.getMessage());
                            info.put("ts", System.currentTimeMillis());
                        }
                        info.put("size", item.size());
                        info.put("dirs", hasChildDirectories(itemName) ? 1 : 0);
                        info.put("read", 1);
                        info.put("write", 1);
                        info.put("locked", 0);
                        files.add(info);
                    }
                } catch (Exception e) {
                    logger.error("Error processing item", e);
                }
            }
        }
        
        return files;
    }

    public List<Map<String, Object>> getTree(String location, int deep) { 
        // 简化实现，实际应用中可能需要更复杂的树形结构
        return new ArrayList<>(); 
    }

    public List<Map<String, Object>> getParents(String location) { 
        // 简化实现
        List<Map<String, Object>> parents = new ArrayList<>();
        parents.add(getLocationInfo(location));
        return parents; 
    }

    public Map<String, Object> getOptions(String target) {
        Map<String, Object> options = new HashMap<>();
        options.put("seperator", "/");
        options.put("path", getLocation(target));
        
        List<String> disabled = Arrays.asList("tmb", "size", "dim", "duplicate", "paste", "archive", "extract", "search", "resize", "netmount");
        options.put("disabled", disabled);
        
        return options;
    }

    public List<String> delete(String location) {
        List<String> deleted = new ArrayList<>();
        String objectName = location.substring(("minio://" + bucketName + "/").length());
        
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(objectName).build());
            deleted.add(hash(objectName));
        } catch (Exception e) {
            logger.error("Error deleting object " + objectName, e);
        }
        
        return deleted;
    }

    @SuppressWarnings("unchecked")
    public void runCommand() {
        String cmd = (String) ec.getContext().get("cmd");
        String target = (String) ec.getContext().get("target");
        Map<String, Object> otherParameters = (Map<String, Object>) ec.getContext().get("otherParameters");

        Map<String, Object> responseMap = new HashMap<>();
        ec.getContext().put("responseMap", responseMap);

        if ("file".equals(cmd)) {
            ec.getContext().put("fileLocation", getLocation(target));
            ec.getContext().put("fileInline", !"1".equals(otherParameters.get("download")));
        } else if ("open".equals(cmd)) {
            boolean init = "1".equals(otherParameters.get("init"));
            boolean tree = "1".equals(otherParameters.get("tree"));
            if (init) {
                responseMap.put("api", "2.0");
                responseMap.put("netDrivers", new ArrayList<>());
                if (target == null || target.isEmpty()) target = hash("root");
            }

            if (target == null || target.isEmpty()) {
                responseMap.clear();
                responseMap.put("error", "File not found");
                return;
            }

            responseMap.put("uplMaxSize", "32M");

            responseMap.put("cwd", getLocationInfo(getLocation(target)));
            responseMap.put("files", getFiles(target, tree));
            responseMap.put("options", getOptions(target));
        } else if ("tree".equals(cmd)) {
            if (target == null || target.isEmpty()) { 
                responseMap.clear(); 
                responseMap.put("error", "errOpen"); 
                return; 
            }

            String location = getLocation(target);
            List<Map<String, Object>> tree = new ArrayList<>();
            tree.add(getLocationInfo(location));
            tree.addAll(getTree(location, 0));
            responseMap.put("tree", tree);
        } else if ("parents".equals(cmd)) {
            responseMap.put("tree", getParents(getLocation(target)));
        } else if ("ls".equals(cmd)) {
            if (target == null || target.isEmpty()) { 
                responseMap.clear(); 
                responseMap.put("error", "errOpen"); 
                return; 
            }
            List<String> fileList = new ArrayList<>();
            String location = getLocation(target);
            String objectName = location.substring(("minio://" + bucketName + "/").length());
            
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(bucketName)
                .prefix(objectName.endsWith("/") ? objectName : objectName + "/")
                .recursive(false)
                .build());
            
            for (Result<Item> result : results) {
                try {
                    Item item = result.get();
                    String itemName = item.objectName();
                    String relativeName = itemName.substring(objectName.length() + (objectName.endsWith("/") ? 0 : 1));
                    fileList.add(relativeName);
                } catch (Exception e) {
                    logger.error("Error processing item", e);
                }
            }
            responseMap.put("list", fileList);
        } else if ("mkdir".equals(cmd)) {
            String name = (String) otherParameters.get("name");
            if (target == null || target.isEmpty()) { 
                responseMap.clear(); 
                responseMap.put("error", "errOpen"); 
                return; 
            }
            if (name == null || name.isEmpty()) { 
                responseMap.clear(); 
                responseMap.put("error", "No name specified for new directory"); 
                return; 
            }
            
            String location = getLocation(target);
            String objectName = location.substring(("minio://" + bucketName + "/").length());
            String newObjectName = objectName + (objectName.endsWith("/") ? "" : "/") + name + "/";
            
            try {
                // 创建一个空对象来表示目录
                minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(newObjectName)
                    .stream(new ByteArrayInputStream(new byte[0]), 0, -1)
                    .build());
                
                Map<String, Object> newInfo = new HashMap<>();
                newInfo.put("name", name);
                newInfo.put("hash", hash(newObjectName));
                newInfo.put("phash", hash(objectName));
                newInfo.put("mime", "directory");
                newInfo.put("dirs", 0);
                newInfo.put("read", 1);
                newInfo.put("write", 1);
                newInfo.put("locked", 0);
                List<Map<String, Object>> added = new ArrayList<>();
                added.add(newInfo);
                responseMap.put("added", added);
            } catch (Exception e) {
                logger.error("Error creating directory " + newObjectName, e);
                responseMap.clear();
                responseMap.put("error", "Error creating directory");
            }
        } else if ("mkfile".equals(cmd)) {
            String name = (String) otherParameters.get("name");
            if (target == null || target.isEmpty()) { 
                responseMap.clear(); 
                responseMap.put("error", "errOpen"); 
                return; 
            }
            if (name == null || name.isEmpty()) { 
                responseMap.clear(); 
                responseMap.put("error", "No name specified for new file"); 
                return; 
            }
            
            String location = getLocation(target);
            String objectName = location.substring(("minio://" + bucketName + "/").length());
            String newObjectName = objectName + (objectName.endsWith("/") ? "" : "/") + name;
            
            try {
                // 创建一个空文件
                minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(newObjectName)
                    .stream(new ByteArrayInputStream(new byte[0]), 0, -1)
                    .build());
                
                Map<String, Object> newInfo = new HashMap<>();
                newInfo.put("name", name);
                newInfo.put("hash", hash(newObjectName));
                newInfo.put("phash", hash(objectName));
                newInfo.put("mime", "application/octet-stream");
                newInfo.put("dirs", 0);
                newInfo.put("read", 1);
                newInfo.put("write", 1);
                newInfo.put("locked", 0);
                List<Map<String, Object>> added = new ArrayList<>();
                added.add(newInfo);
                responseMap.put("added", added);
            } catch (Exception e) {
                logger.error("Error creating file " + newObjectName, e);
                responseMap.clear();
                responseMap.put("error", "Error creating file");
            }
        } else if ("rm".equals(cmd)) {
            Object targetsObj = otherParameters.get("targets");
            if (targetsObj == null) targetsObj = otherParameters.get("targets[]");
            List<String> targets = targetsObj instanceof List ? (List<String>) targetsObj : Arrays.asList((String) targetsObj);
            List<String> removed = new ArrayList<>();
            for (String curTarget : targets) {
                String rmLocation = getLocation(curTarget);
                logger.info("Minio elFinder rm " + rmLocation);
                removed.addAll(delete(rmLocation));
            }
            responseMap.put("removed", removed);
        } else if ("rename".equals(cmd)) {
            String name = (String) otherParameters.get("name");
            if (target == null || target.isEmpty()) { 
                responseMap.clear(); 
                responseMap.put("error", "errOpen"); 
                return; 
            }
            if (name == null || name.isEmpty()) { 
                responseMap.clear(); 
                responseMap.put("error", "No name specified for new directory"); 
                return; 
            }

            String location = getLocation(target);
            String objectName = location.substring(("minio://" + bucketName + "/").length());
            String newObjectName = objectName.substring(0, objectName.lastIndexOf("/") + 1) + name;

            try {
                // MinIO不支持直接重命名，需要复制对象然后删除原对象
                minioClient.copyObject(CopyObjectArgs.builder()
                    .bucket(bucketName)
                    .object(newObjectName)
                    .source(CopySource.builder().bucket(bucketName).object(objectName).build())
                    .build());
                
                minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(objectName).build());

                Map<String, Object> newInfo = getLocationInfo("minio://" + bucketName + "/" + newObjectName);
                List<Map<String, Object>> added = new ArrayList<>();
                added.add(newInfo);
                responseMap.put("added", added);
                List<String> removed = new ArrayList<>();
                removed.add(target);
                responseMap.put("removed", removed);
            } catch (Exception e) {
                logger.error("Error renaming object " + objectName + " to " + newObjectName, e);
                responseMap.clear();
                responseMap.put("error", "Error renaming object");
            }
        } else if ("upload".equals(cmd)) {
            if (target == null || target.isEmpty()) { 
                responseMap.clear(); 
                responseMap.put("error", "errOpen"); 
                return; 
            }
            String location = getLocation(target);
            String objectName = location.substring(("minio://" + bucketName + "/").length());
            
            List<Map<String, Object>> added = new ArrayList<>();
            List<FileItem> fileUploadList = (List<FileItem>) otherParameters.get("_fileUploadList");
            if (fileUploadList != null) {
                for (FileItem item : fileUploadList) {
                    logger.info("Minio elFinder upload " + item.getName() + " to " + location);
                    String newObjectName = objectName + (objectName.endsWith("/") ? "" : "/") + item.getName();
                    
                    try {
                        minioClient.putObject(PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(newObjectName)
                            .stream(item.getInputStream(), item.getSize(), -1)
                            .build());
                        
                        Map<String, Object> newInfo = new HashMap<>();
                        newInfo.put("name", item.getName());
                        newInfo.put("hash", hash(newObjectName));
                        newInfo.put("phash", hash(objectName));
                        newInfo.put("mime", "application/octet-stream");
                        newInfo.put("size", item.getSize());
                        newInfo.put("dirs", 0);
                        newInfo.put("read", 1);
                        newInfo.put("write", 1);
                        newInfo.put("locked", 0);
                        added.add(newInfo);
                    } catch (Exception e) {
                        logger.error("Error uploading file " + item.getName(), e);
                    }
                }
            }
            responseMap.put("added", added);
        } else if ("get".equals(cmd)) {
            String location = getLocation(target);
            String objectName = location.substring(("minio://" + bucketName + "/").length());
            
            try {
                // getObject方法返回的是GetObjectResponse，而不是ByteArrayInputStream
                io.minio.GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder().bucket(bucketName).object(objectName).build());
                // 读取流的内容到字符串
                Scanner scanner = new Scanner(response).useDelimiter("\\A");
                String content = scanner.hasNext() ? scanner.next() : "";
                scanner.close();
                responseMap.put("content", content);
            } catch (Exception e) {
                logger.error("Error getting object " + objectName, e);
                responseMap.clear();
                responseMap.put("error", "Error getting object");
            }
        } else if ("put".equals(cmd)) {
            String content = (String) otherParameters.get("content");
            String location = getLocation(target);
            String objectName = location.substring(("minio://" + bucketName + "/").length());
            
            try {
                minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length(), -1)
                    .build());
                
                Map<String, Object> newInfo = getLocationInfo(location);
                List<Map<String, Object>> changed = new ArrayList<>();
                changed.add(newInfo);
                responseMap.put("changed", changed);
            } catch (Exception e) {
                logger.error("Error putting object " + objectName, e);
                responseMap.clear();
                responseMap.put("error", "Error putting object");
            }
        }
    }
}