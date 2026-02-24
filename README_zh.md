
# RikkaWeb (独立服务器)

[🇬🇧 English](./README.md)

欢迎！这是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的 **独立服务器版本** —— 无需 Android 设备，直接在任何平台运行 Web UI 和 API。

最简单的上手方式？下载预编译 jar，一行命令启动：

```bash
java -jar rikkaweb.jar --host 0.0.0.0 --port 11001 --data-dir ./data \
  --jwt-enabled true --access-password "你的密码"
```

---

## 环境要求

- **Java 17+** 运行时
- 一个可写的数据目录（用于存放配置、数据库、上传文件）

---

## 快速开始

### 第一步：创建数据目录

```bash
mkdir -p ./data
```

初始化数据有两种方式：

| 方式 | 适用场景 |
|------|----------|
| 📦 **导入 Android 备份**（推荐） | 已经在 Android 上用 RikkaHub？直接迁移过来！ |
| ✏️ **手动配置** | 全新开始？参考 `settings.example.json` 自己写配置 |

### 第二步：导入 Android 备份（推荐）

如果你有从 Android 应用导出的备份 ZIP，一条命令搞定：

```bash
java -jar rikkaweb.jar --data-dir ./data \
  --import-zip "/path/to/rikkahub-backup.zip" \
  --import-overwrite true
```

这会导入你的 `settings.json`、数据库和上传文件 —— 无缝迁移！✨

### 第三步：启动服务器

```bash
java -jar rikkaweb.jar --host 0.0.0.0 --port 11001 --data-dir ./data \
  --jwt-enabled true --access-password "你的密码"
```

打开浏览器访问：

- 🌐 **Web 界面**: `http://<主机>:11001/`
- 🔌 **API 接口**: `http://<主机>:11001/api/*`

---

## 命令行参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `--host <地址>` | 绑定地址 | `0.0.0.0` |
| `--port <端口>` | 绑定端口 | `8080` |
| `--data-dir <路径>` | 数据目录 | `./data` |
| `--jwt-enabled <布尔>` | 启用身份验证 | `false` |
| `--access-password <字符串>` | JWT 签名密码 | — |
| `--import-zip <路径>` | 导入备份 ZIP 后退出 | — |
| `--import-overwrite <布尔>` | 导入时覆盖现有数据 | `true` |
| `--help`, `-h` | 显示帮助 | — |

### 环境变量

也可以用环境变量配置（优先级高于命令行默认值）：

```
RIKKAHUB_HOST
RIKKAHUB_PORT
RIKKAHUB_DATA_DIR
RIKKAHUB_JWT_ENABLED
RIKKAHUB_ACCESS_PASSWORD
```

---

## 数据目录结构

```
data/
├── settings.json    # 配置文件
├── db/              # SQLite 数据库（从应用备份导入）
├── upload/          # 上传文件和工具生成的资源
└── tmp/             # 临时文件（停止服务后可安全删除）
```

---

## 安全提示 🔒

如果要把服务暴露到公网，请务必：

- ✅ **启用认证**: `--jwt-enabled true`
- ✅ **使用强密码**: `--access-password "一个难以猜测的密码"`
- ✅ **配置 HTTPS**: 用 nginx/caddy 反向代理，加上 TLS 证书

> Web UI 会把 access token 存在浏览器的 `localStorage` 中。

---

## 常见问题

### 😕 模型列表是空的？

模型列表来自 `settings.json`，你需要：
- 导入一个 Android 备份 ZIP，或者
- 手动在配置里添加 providers/models（参考 `settings.example.json`）

### 😕 MCP 服务器没显示？

检查 `settings.json` 中对应 MCP 服务器的 `commonOptions.enable` 是否为 `true`。

---

## 从源码构建

想自己编译？没问题：

```bash
./gradlew :standalone-server:rikkawebJar
```

输出文件: `standalone-server/build/libs/rikkaweb.jar`

---

用得开心！🚀