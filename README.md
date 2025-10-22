# Moqui-MinIO 组件

企业级MinIO对象存储集成组件，支持ElFinder中文文件管理器和完整的存储桶管理功能。

[![版本](https://img.shields.io/badge/版本-v1.0.0-blue.svg)](https://github.com/your-repo)
[![状态](https://img.shields.io/badge/状态-生产就绪-green.svg)](#)
[![协议](https://img.shields.io/badge/协议-CC0_1.0-lightgrey.svg)](#)

## 🚀 功能特性

### 核心功能
- **存储桶管理**：创建、删除、更新、列表查询
- **对象操作**：上传、下载、删除、列表，支持大文件处理
- **ElFinder集成**：Web文件管理器支持，中文界面优化
- **权限控制**：用户级别的存储桶权限管理
- **操作日志**：完整的操作审计跟踪

### 企业级特性
- **连接池管理**：高性能连接复用，避免连接泄露
- **统一配置**：多源配置支持（系统属性、环境变量、默认值）
- **异常处理**：完整的错误分类和处理机制
- **安全性**：敏感信息脱敏和权限验证
- **监控诊断**：连接状态监控和性能统计
- **中文支持**：完整的中文界面和错误信息

## 📦 快速开始

### 1. 前置条件

确保您已安装并运行MinIO服务：

```bash
# 使用Docker快速启动MinIO服务
docker run -d \
  --name minio \
  -p 9000:9000 \
  -p 9001:9001 \
  -e "MINIO_ROOT_USER=admin" \
  -e "MINIO_ROOT_PASSWORD=admin123" \
  -v ~/minio-data:/data \
  quay.io/minio/minio server /data --console-address ":9001"
```

### 2. 编译组件

在首次使用前，需要编译moqui-minio组件：

```bash
cd runtime/component/moqui-minio
../../../gradlew jar
```

### 3. 配置连接

#### 方式一：配置文件 (推荐)
在 `runtime/conf/MoquiDevConf.xml` 中配置：
```xml
<default-property name="minio.endpoint" value="http://localhost:9000"/>
<default-property name="minio.accessKey" value="admin"/>
<default-property name="minio.secretKey" value="admin123"/>
<default-property name="minio.region" value="us-east-1"/>
<default-property name="minio.secure" value="false"/>
```

#### 方式二：环境变量
```bash
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESSKEY=admin
export MINIO_SECRETKEY=admin123
```

#### 方式三：系统属性
```bash
java -jar moqui.war \
  -Dminio.endpoint=http://localhost:9000 \
  -Dminio.accessKey=admin \
  -Dminio.secretKey=admin123
```

### 4. 启动应用

```bash
java -jar moqui.war
```

### 5. 访问系统

- **系统登录**: `http://localhost:8080` (john.doe/moqui)
- **MinIO管理界面**: `http://localhost:8080/qapps/minio`
- **存储桶列表**: `http://localhost:8080/qapps/minio/Bucket/FindBucket`
- **文件管理器**: `http://localhost:8080/qapps/minio/Bucket/FileExplorer?bucketName=your-bucket`

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

### 常见问题及解决方案

#### 1. MinioServiceRunner类找不到错误
```
错误: Could not find class for java service [minio.MinioServices.list#Bucket]
     Class org.moqui.impl.service.runner.MinioServiceRunner not found.
原因: moqui-minio组件未编译，JAR文件不存在
解决方案:
  1. cd runtime/component/moqui-minio
  2. ../../../gradlew jar
  3. 重启服务器: java -jar moqui.war
```

#### 2. 登录后白页问题
```
错误: 登录成功后显示空白页面
原因: 前端库依赖缺失 (Vue.js, Quasar, moment.js)
解决方案:
  1. 下载缺失的前端库到 runtime/base-component/webroot/screen/webroot/libs/
  2. Vue.js 2.7.14 (vue.js, vue.min.js)
  3. Quasar 1.22.10 (quasar.min.css, quasar.umd.min.js)
  4. moment.js 2.24.0 (moment-with-locales.min.js)
  5. 重启服务器
```

#### 3. 用户账户找不到
```
错误: No account found for username john.doe
解决方案: java -jar moqui.war -load  # 强制加载种子数据
```

#### 4. MinIO连接失败
```
错误: MinIO client connection validation failed
解决方案:
  1. 检查MinIO服务是否运行: curl http://localhost:9000/minio/health/live
  2. 验证配置: endpoint, accessKey, secretKey
  3. 检查网络连通性和防火墙设置
```

#### 5. ElFinder虚拟目录错误
```
错误: ErrorResponseException: Object does not exist
原因: MinIO中目录是虚拟的，不存在实际对象
解决方案: 组件已优化处理虚拟目录，使用hasChildDirectories()检测
```

#### 6. 权限访问错误
```
错误: AccessDenied: 访问被拒绝
解决方案:
  1. 验证accessKey和secretKey配置正确
  2. 检查MinIO服务器的用户权限设置
  3. 确认存储桶策略配置
```

### 调试工具

#### 启用详细日志
```xml
<!-- 在 runtime/conf/log4j2.xml 中添加 -->
<logger name="org.moqui.impl.service.minio" level="DEBUG"/>
<logger name="io.minio" level="DEBUG"/>
```

#### 检查组件加载状态
```bash
# 查看服务器启动日志
grep -i "moqui-minio" runtime/log/moqui.log

# 确认JAR文件存在
ls runtime/component/moqui-minio/lib/moqui-minio-*.jar
```

#### 验证配置
```bash
# 检查配置文件
grep -i minio runtime/conf/MoquiDevConf.xml
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
- ✅ **核心架构重构**：完整的企业级架构设计
- ✅ **连接池优化**：高性能连接复用和生命周期管理
- ✅ **统一异常处理**：完整的错误分类和处理机制
- ✅ **ElFinder完美集成**：支持中文界面的Web文件管理器
- ✅ **企业级安全特性**：权限控制、操作审计、敏感信息保护
- ✅ **完整监控诊断**：连接状态监控和性能统计
- ✅ **关键问题修复**：
  - 修复MinioServiceRunner类加载问题 (需要先编译组件)
  - 修复登录后白页问题 (前端库依赖缺失)
  - 修复用户账户加载问题 (需要加载种子数据)
  - 修复ElFinder虚拟目录处理问题
  - 优化中文界面支持和错误提示
- ✅ **文档完善**：详细的故障排查指南和使用文档

### 关键修复说明

#### MinioServiceRunner类找不到问题
- **问题**: `Class org.moqui.impl.service.runner.MinioServiceRunner not found`
- **根因**: moqui-minio组件源码未编译为JAR
- **解决**: 使用Gradle编译组件并生成JAR文件

#### 登录后白页问题
- **问题**: 登录成功后显示空白页面
- **根因**: 前端库依赖缺失 (Vue.js 2.7.14, Quasar 1.22.10, moment.js 2.24.0)
- **解决**: 手动下载并部署前端库到正确目录

#### 用户账户加载问题
- **问题**: `No account found for username john.doe`
- **根因**: 种子数据未正确加载
- **解决**: 使用 `java -jar moqui.war -load` 强制加载数据

### v0.2.0 - ElFinder集成版
- ✅ ElFinder文件管理器集成
- ✅ 虚拟目录处理
- ✅ 基础的存储桶管理

### v0.1.0 - 基础功能版
- ✅ 基本的MinIO连接
- ✅ 简单的存储桶操作
- ✅ 初步的服务封装

## ✅ 快速验证清单

在安装和配置完成后，请按以下清单验证组件是否正常工作：

### 1. 组件编译验证
```bash
# 检查JAR文件是否存在
ls runtime/component/moqui-minio/lib/moqui-minio-*.jar
# 应该显示: moqui-minio-1.0.0.jar
```

### 2. 前端库验证
```bash
# 检查关键前端库是否存在
ls runtime/base-component/webroot/screen/webroot/libs/vue3/vue.min.js
ls runtime/base-component/webroot/screen/webroot/libs/quasar2/quasar.umd.min.js
ls runtime/base-component/webroot/screen/webroot/libs/moment.js/moment-with-locales.min.js
```

### 3. 服务器启动验证
```bash
# 查看启动日志，确认组件加载成功
grep -i "Added component moqui-minio" runtime/log/moqui.log
grep -i "Added JARs from component moqui-minio" runtime/log/moqui.log
```

### 4. 登录功能验证
- 访问: `http://localhost:8080`
- 用户名: `john.doe`
- 密码: `moqui`
- 应该能正常显示主界面（非空白页）

### 5. MinIO组件验证
```bash
# 访问以下URL，应该不报错
curl -s "http://localhost:8080/qapps/minio/Bucket/FindBucket" | grep -v "MinioServiceRunner not found"
```

### 6. 功能完整性验证
- **存储桶列表**: `http://localhost:8080/qapps/minio/Bucket/FindBucket`
- **文件管理器**: `http://localhost:8080/qapps/minio/Bucket/FileExplorer?bucketName=test`
- **中文界面**: ElFinder应显示中文菜单和操作提示

### 状态指示

| ✅ 正常状态 | ❌ 异常状态 | 解决方案 |
|------------|------------|----------|
| 组件JAR存在 | JAR文件缺失 | 执行 `gradlew jar` |
| 前端库完整 | 白页显示 | 下载缺失的前端库 |
| 用户可登录 | 账户不存在 | 执行 `java -jar moqui.war -load` |
| MinIO服务正常 | 服务类错误 | 检查编译和配置 |
| 中文界面正常 | 英文或乱码 | 检查语言包配置 |

---

**维护团队**: Moqui开发团队
**技术支持**: 参考.ai目录下的完整文档
**许可证**: CC0 1.0 Universal

### 🆘 需要帮助？

如果您在使用过程中遇到问题：

1. **首先检查**: 按照上述快速验证清单排查
2. **查看日志**: `runtime/log/moqui.log` 中的错误信息
3. **参考文档**: `.ai/Moqui-MinIO组件开发精品化实战指南.md`
4. **常见问题**: 本文档的"故障排查"部分包含了所有已知问题的解决方案

> **提示**: 本组件经过充分测试，按照文档操作应该能够正常工作。如果遇到问题，99%的情况是配置或环境问题。
