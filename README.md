# Moqui MinIO Component

Enterprise-grade MinIO object storage integration for Moqui Framework.

[![License](https://img.shields.io/badge/license-CC0_1.0-blue.svg)](LICENSE.md)
[![Moqui](https://img.shields.io/badge/moqui-3.0+-green.svg)](https://github.com/moqui/moqui-framework)
[![MinIO](https://img.shields.io/badge/minio-8.5+-red.svg)](https://min.io/)

## Features

### Core Capabilities
- **Bucket Management**: Create, delete, list, and configure storage buckets
- **Object Operations**: Upload, download, delete objects with support for large files
- **ElFinder Integration**: Web-based file manager with MinIO backend
- **Permission Control**: User-level bucket access management
- **Audit Logging**: Complete operation tracking and history

### Enterprise Features
- **Connection Pooling**: High-performance connection reuse with lifecycle management
- **Unified Configuration**: Multi-source configuration support (system properties, environment variables, defaults)
- **Exception Handling**: Comprehensive error classification and handling
- **Security**: Sensitive data masking and permission validation
- **Monitoring**: Connection status monitoring and performance statistics

## Quick Start

### Prerequisites

- **Moqui Framework**: Version 3.0 or higher
- **JDK**: 11 or higher
- **MinIO Server**: Running instance

### 1. Install MinIO Server

Using Docker (recommended):

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

Access MinIO Console at: http://localhost:9001

### 2. Add Component to Moqui

Clone this repository into your Moqui runtime components directory:

```bash
cd runtime/component
git clone https://github.com/moqui/moqui-minio.git
```

### 3. Build the Component

```bash
cd runtime/component/moqui-minio
../../../gradlew jar
```

### 4. Configure MinIO Connection

Add the following to `runtime/conf/MoquiDevConf.xml`:

```xml
<moqui-conf>
    <default-property name="minio.endpoint" value="http://localhost:9000"/>
    <default-property name="minio.accessKey" value="admin"/>
    <default-property name="minio.secretKey" value="admin123"/>
    <default-property name="minio.region" value="us-east-1"/>
    <default-property name="minio.secure" value="false"/>
</moqui-conf>
```

Alternatively, use environment variables:

```bash
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESSKEY=admin
export MINIO_SECRETKEY=admin123
```

### 5. Start Moqui

```bash
java -jar moqui.war
```

### 6. Access the Application

- **Moqui**: http://localhost:8080 (login: john.doe / moqui)
- **MinIO Management**: http://localhost:8080/qapps/minio
- **Bucket List**: http://localhost:8080/qapps/minio/Bucket/FindBucket
- **File Explorer**: http://localhost:8080/qapps/minio/Bucket/FileExplorer?bucketName=your-bucket

## Architecture

```
MinIO Component Architecture
├── Configuration Layer (MinioConfig)
│   ├── Multi-source configuration support
│   ├── Configuration validation
│   └── Security logging
├── Connection Layer (MinioClientPool)
│   ├── Connection pooling
│   ├── Lifecycle management
│   └── Performance monitoring
├── Exception Layer (MinioException/Utils)
│   ├── Exception classification
│   ├── Error transformation
│   └── User-friendly messages
├── Service Layer (MinioServiceRunner)
│   ├── Bucket management
│   ├── Object operations
│   └── Permission control
└── Integration Layer (ElFinder/ToolFactory)
    ├── Protocol routing
    ├── Web file management
    └── System integration
```

## Usage Examples

### Create a Bucket

```groovy
ec.service.sync().name("minio.createBucket")
    .parameters([
        bucketId: "my-bucket",
        userId: "user123",
        bucketName: "My Bucket",
        description: "Test bucket",
        quotaLimit: 1073741824L // 1GB
    ]).call()
```

### Upload an Object

```groovy
ec.service.sync().name("minio.uploadObject")
    .parameters([
        bucketId: "my-bucket",
        userId: "user123",
        objectName: "documents/test.pdf",
        fileBytes: fileContent
    ]).call()
```

### Using Connection Pool

```java
// Get pooled client
MinioClient client = MinioClientPool.getClient(ec.getFactory());

// Perform operations
client.listBuckets();

// No need to close manually - pool manages lifecycle
```

### Exception Handling

```java
try {
    client.makeBucket(MakeBucketArgs.builder().bucket("test").build());
} catch (Exception e) {
    MinioException minioEx = MinioExceptionUtils.convertException("createBucket", e);
    logger.error("Operation failed: {}", minioEx.getDetailedMessage());
    throw minioEx;
}
```

## Monitoring and Diagnostics

### Connection Pool Statistics

```java
String stats = MinioClientPool.getCacheStats();
// Output: MinIO client cache stats: total=2, active=2, max=10
```

### Connection Validation

```java
boolean isHealthy = MinioClientFactory.validateConnection(client);
```

### Operation Logs

All operations are logged in the `moqui.minio.BucketUsageLog` table, including:
- Operation type (CREATE, DELETE, UPLOAD, DOWNLOAD, etc.)
- Operation time and user
- Result and error information
- IP address and user agent

## Security

### Permission Control
- **Bucket-level permissions**: READ, WRITE, ADMIN
- **User isolation**: Users can only access their own buckets
- **Admin access**: Administrators can manage all buckets

### Sensitive Data Protection
- **Configuration masking**: Keys are automatically hidden in logs
- **Operation audit**: Complete tracking of all operations
- **Error sanitization**: Error logs don't contain sensitive information

## Configuration Reference

### Available Properties

| Property | Default | Description |
|----------|---------|-------------|
| `minio.endpoint` | `http://localhost:9000` | MinIO server endpoint |
| `minio.accessKey` | `admin` | Access key |
| `minio.secretKey` | `admin123` | Secret key |
| `minio.region` | `us-east-1` | Region |
| `minio.secure` | `false` | Use HTTPS |
| `minio.connectionTimeout` | `10000` | Connection timeout (ms) |
| `minio.readTimeout` | `10000` | Read timeout (ms) |
| `minio.writeTimeout` | `10000` | Write timeout (ms) |

### Configuration Priority

1. System properties (highest)
2. Environment variables
3. Moqui configuration files
4. Default values (lowest)

## Troubleshooting

### Component Not Loading

**Error**: `Class org.moqui.impl.service.runner.MinioServiceRunner not found`

**Solution**:
```bash
cd runtime/component/moqui-minio
../../../gradlew jar
# Restart Moqui
```

### Connection Failed

**Error**: `MinIO client connection validation failed`

**Solution**:
1. Check MinIO service is running: `curl http://localhost:9000/minio/health/live`
2. Verify configuration: endpoint, accessKey, secretKey
3. Check network connectivity and firewall settings

### Missing Dependencies

**Error**: `Component moqui-minio depends on component mantle-usl which is not initialized`

**Solution**:
```bash
./gradlew getDepends
# Restart Moqui
```

### Enable Debug Logging

Add to `runtime/conf/log4j2.xml`:

```xml
<Logger name="org.moqui.impl.service.minio" level="DEBUG"/>
<Logger name="io.minio" level="DEBUG"/>
```

## Development

### Build from Source

```bash
# Clone the repository
git clone https://github.com/moqui/moqui-minio.git
cd moqui-minio

# Build JAR
../../../gradlew jar

# Run tests
../../../gradlew test
```

### Project Structure

```
moqui-minio/
├── src/main/java/           # Java source code
│   └── org/moqui/impl/service/
│       ├── minio/           # MinIO implementation
│       └── runner/          # Service runner
├── service/                 # Service definitions
│   └── minio/
│       ├── MinioServices.xml
│       └── MinioElFinderServices.xml
├── entity/                  # Entity definitions
│   └── Entities.xml
├── data/                    # Seed data
│   ├── MinioSecurityData.xml
│   └── MinioSetupData.xml
├── screen/                  # UI screens
│   └── MinioApp/
├── docs/                    # Documentation
├── build.gradle             # Build configuration
├── component.xml            # Component definition
└── README.md                # This file
```

## API Documentation

### REST API

The component exposes REST APIs for bucket and object operations:

- `GET /rest/s1/minio/buckets` - List buckets
- `POST /rest/s1/minio/buckets` - Create bucket
- `GET /rest/s1/minio/buckets/{bucketId}` - Get bucket details
- `DELETE /rest/s1/minio/buckets/{bucketId}` - Delete bucket
- `GET /rest/s1/minio/buckets/{bucketId}/objects` - List objects

See `service/minio.rest.xml` for complete API specifications.

## Contributing

Contributions are welcome! Please read our [Contributing Strategy](docs/CONTRIBUTING_STRATEGY.md) for details.

### Development Guidelines

1. Follow existing code style
2. Add appropriate unit tests
3. Update documentation
4. Ensure all tests pass
5. Submit focused pull requests

## Version History

### v1.0.0 (2025-10-09) - Initial Release

**Core Features**:
- ✅ Enterprise-grade architecture
- ✅ Connection pool optimization
- ✅ Unified exception handling
- ✅ ElFinder integration
- ✅ Enterprise security features
- ✅ Complete monitoring and diagnostics

**Technical Highlights**:
- Connection pooling with caching
- Multi-source configuration management
- Comprehensive error handling
- Permission-based access control
- Complete audit logging
- REST API support

## License

This software is in the public domain under CC0 1.0 Universal plus a Grant of Patent License.

See [LICENSE.md](LICENSE.md) for details.

## Support

- **Issues**: https://github.com/moqui/moqui-minio/issues
- **Moqui Forum**: https://forum.moqui.org/
- **Documentation**: [docs/](docs/)

## Related Projects

- [Moqui Framework](https://github.com/moqui/moqui-framework)
- [MinIO](https://github.com/minio/minio)
- [ElFinder](https://github.com/Studio-42/elFinder)

## Acknowledgments

- Moqui Framework team for the excellent framework
- MinIO team for the robust object storage solution
- Community contributors

---

**Maintained by**: Moqui Community
**License**: CC0 1.0 Universal
