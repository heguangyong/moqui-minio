## A minio component

minio Component with: 

- data
- entities
- screens
- services
- REST API
- src

Create a new moqui component

To use run the following with moqui-framework [226f4826f97a0300d704b55a3aa63298aedd9acc](https://github.com/moqui/moqui-framework/pull/636/commits/226f4826f97a0300d704b55a3aa63298aedd9acc) or later:

```bash
./gradlew createComponent -Pcomponent=your-component
```

See [this](https://forum.moqui.org/t/moqui-minio-component/725/7) for context

## 安装和配置

### 前提条件
1. 已经在 GitHub 上创建了一个空的仓库。
2. 本地计算机已经安装并配置好 Git（如果没有，请先安装）。
3. IntelliJ IDEA 已经安装并配置好 Git 版本控制。

### 安装步骤

#### 1. 在 GitHub 上创建仓库
- 在 GitHub 页面右上角点击 `+` 按钮，选择 `New repository`。
- 为你的仓库起一个名字，注意不要勾选创建 README 文件、.gitignore 文件等选项，因为我们还没在本地创建这些文件。

#### 2. 在 IntelliJ IDEA 中
- 打开已经创建好的项目。
- 确保项目已经初始化 Git（如果没有，在项目目录右键选择 `Git` -> `Enable Version Control Integration` -> `Git`）。

#### 3. 初始化 Git 仓库（如果尚未初始化）
如果在项目中还没有初始化 Git 仓库，先初始化它

- 在 IntelliJ IDEA 中，打开 `Terminal`，然后运行以下命令：
  ```bash
  git init
  ```

#### 4. 添加远程仓库
- 在 GitHub 上找到刚创建的仓库 URL（SSH 或 HTTPS）。
- 使用以下命令添加远程仓库：
  ```bash
  git remote add origin <your-repository-url>
  ```

#### 5. 将文件添加到 Git 并提交
- 在 IntelliJ IDEA 中，右键点击项目根目录，选择 `Git` -> `Add`，将文件添加到 Git 中。
- 或者在终端中运行命令：
  ```bash
  git add .
  ```

- 提交文件：
  ```bash
  git commit -m "Initial commit"
  ```

#### 6. 推送到 GitHub
- 使用以下命令将提交推送到 GitHub 仓库：
  ```bash
  git push -u origin master
  ```

#### 7. 验证推送结果
- 在 GitHub 上刷新你的仓库页面，确保文件已经成功推送上去。

### 注意事项：
- 如果在 GitHub 仓库使用的是 `main` 分支而不是 `master`，推送时要替换分支名称：
  ```bash
  git push -u origin main
  ```

## MinIO 配置

### 安装 Docker 版本的 MinIO

使用以下命令安装和运行 Docker 版本的 MinIO（使用默认的访问密钥）：

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
- 控制台地址: http://localhost:9001
- 用户名: minioadmin
- 密码: minioadmin

### 配置 MinIO

1. 登录 MinIO 控制台
2. 创建一个新的存储桶（bucket）
3. 配置访问策略（如果需要公开访问）