# Moqui-MinIO 组件

企业级MinIO对象存储集成组件，支持ElFinder文件管理器和完整的存储桶管理功能。

## 🚀 功能特性

### 核心功能
- **存储桶管理**：创建、删除、更新、列表查询
- **对象操作**：上传、下载、删除、列表
- **ElFinder集成**：Web文件管理器支持
- **权限控制**：用户级别的存储桶权限管理
- **操作日志**：完整的操作审计跟踪

### 企业级特性
- **连接池管理**：高性能连接复用
- **统一配置**：多源配置支持（系统属性、环境变量、默认值）
- **异常处理**：完整的错误分类和处理机制
- **安全性**：敏感信息脱敏和权限验证
- **监控诊断**：连接状态监控和性能统计

## 📦 快速开始

### 1. 配置MinIO连接

#### 方式一：系统属性
```bash
-Dminio.endpoint=http://localhost:9000
-Dminio.accessKey=admin
-Dminio.secretKey=admin123
```

#### 方式二：环境变量
```bash
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESSKEY=admin
export MINIO_SECRETKEY=admin123
```

#### 方式三：配置文件 (MoquiConf.xml)
```xml
<default-property name="minio.endpoint" value="http://localhost:9000"/>
<default-property name="minio.accessKey" value="admin"/>
<default-property name="minio.secretKey" value="admin123"/>
```

### 2. 启动应用
```bash
java -jar moqui.war
```

### 3. 访问MinIO管理界面
访问：`http://localhost:8080/apps/minio`

## 🏗️ 架构设计

```
MinIO组件架构
├── 配置层 (MinioConfig)
│   ├── 多源配置支持
│   ├── 配置验证
│   └── 安全日志
├── 连接层 (MinioClientPool)
│   ├── 连接复用
│   ├── 生命周期管理
│   └── 性能监控
├── 异常层 (MinioException/Utils)
│   ├── 异常分类
│   ├── 错误转换
│   └── 用户友好消息
├── 服务层 (MinioServiceRunner)
│   ├── 存储桶管理
│   ├── 对象操作
│   └── 权限控制
└── 集成层 (ElFinder/ToolFactory)
    ├── 协议路由
    ├── Web文件管理
    └── 系统集成
```

## 🔧 开发指南

### API使用示例

#### 创建存储桶
```groovy
ec.service.sync().name("minio.createBucket")
    .parameters([
        bucketId: "my-bucket",
        userId: "user123",
        bucketName: "我的存储桶",
        description: "测试存储桶",
        quotaLimit: 1073741824L // 1GB
    ]).call()
```

#### 上传对象
```groovy
ec.service.sync().name("minio.uploadObject")
    .parameters([
        bucketId: "my-bucket",
        userId: "user123",
        objectName: "documents/test.pdf",
        fileBytes: fileContent
    ]).call()
```

#### 连接池使用
```java
// 获取连接池客户端
MinioClient client = MinioClientPool.getClient(ec.getFactory());

// 执行操作
client.listBuckets();

// 无需手动关闭，连接池自动管理
```

### 异常处理
```java
try {
    // MinIO操作
    client.makeBucket(MakeBucketArgs.builder().bucket("test").build());
} catch (Exception e) {
    // 统一异常转换
    MinioException minioEx = MinioExceptionUtils.convertException("createBucket", e);
    logger.error("操作失败: {}", minioEx.getDetailedMessage());
    throw minioEx;
}
```

## 📊 监控和诊断

### 连接池状态查询
```java
String stats = MinioClientPool.getCacheStats();
// 输出: MinIO客户端缓存统计: 总计=2, 活跃=2, 最大=10
```

### 连接验证
```java
boolean isHealthy = MinioClientFactory.validateConnection(client);
```

### 操作日志查询
所有操作都会记录在 `moqui.minio.BucketUsageLog` 表中，包含：
- 操作类型（CREATE, DELETE, UPLOAD, DOWNLOAD等）
- 操作时间和用户
- 操作结果和错误信息
- IP地址和用户代理

## 🔒 安全性

### 权限控制
- **存储桶级权限**：READ, WRITE, ADMIN
- **用户级隔离**：用户只能操作自己的存储桶
- **管理员权限**：可以管理所有用户的存储桶

### 敏感信息保护
- **配置脱敏**：日志中自动隐藏密钥信息
- **操作审计**：完整记录所有操作轨迹
- **错误信息脱敏**：错误日志不包含敏感信息

## 🛠️ 故障排查

### 常见问题

#### 1. 连接失败
```
错误: MinIO client connection validation failed
解决: 检查endpoint配置和MinIO服务状态
```

#### 2. 权限错误
```
错误: AccessDenied: 访问被拒绝
解决: 检查accessKey和secretKey配置
```

#### 3. ElFinder目录错误
```
错误: ErrorResponseException: Object does not exist
解决: 组件已处理虚拟目录问题，确保使用最新版本
```

#### 4. 性能问题
```
问题: 客户端创建频繁
解决: 确保使用MinioClientPool而不是直接创建客户端
```

### 日志级别调整
```xml
<!-- 开启Debug日志 -->
<logger name="org.moqui.impl.service.minio" level="DEBUG"/>
```

## MinIO 服务端安装

### 安装 Docker 版本的 MinIO

使用以下命令安装和运行 Docker 版本的 MinIO：

```bash
docker run -d \
--name minio \
-p 9000:9000 \
-p 9001:9001 \
-e "MINIO_ROOT_USER=admin" \
-e "MINIO_ROOT_PASSWORD=admin123" \
-v ~/minio-data:/data \
quay.io/minio/minio server /data --console-address ":9001"
```

### 访问 MinIO 控制台

安装完成后，可以通过以下地址访问 MinIO 控制台：
- API地址: http://localhost:9000
- 控制台地址: http://localhost:9001
- 用户名: admin
- 密码: admin123

## 📚 相关文档

- [Moqui-MinIO组件开发精品化实战指南](../.ai/Moqui-MinIO组件开发精品化实战指南.md) - 完整的开发实战经验
- [Moqui组件开发实战规范](../.ai/Moqui组件开发实战规范.md) - 通用开发规范
- [MinIO官方文档](https://docs.min.io/) - MinIO服务端配置和管理

## 🤝 贡献指南

### 开发环境设置
1. 克隆项目：`git clone <repo-url>`
2. 配置IDE：导入Moqui项目，配置XML schema验证
3. 启动MinIO：使用上述Docker命令
4. 运行测试：`./gradlew test`

### 提交规范
- 遵循现有代码风格
- 添加适当的单元测试
- 更新相关文档
- 确保所有测试通过

## 📋 版本历史

### v1.0.0 (2025-09-28) - 企业级精品版
- ✅ 完整的架构重构
- ✅ 连接池和性能优化
- ✅ 统一异常处理机制
- ✅ ElFinder完美集成
- ✅ 企业级安全特性
- ✅ 完整的监控和诊断功能

### v0.2.0 - ElFinder集成版
- ✅ ElFinder文件管理器集成
- ✅ 虚拟目录处理
- ✅ 基础的存储桶管理

### v0.1.0 - 基础功能版
- ✅ 基本的MinIO连接
- ✅ 简单的存储桶操作
- ✅ 初步的服务封装

---

**维护团队**: Moqui开发团队
**技术支持**: 参考.ai目录下的完整文档
**许可证**: CC0 1.0 Universal