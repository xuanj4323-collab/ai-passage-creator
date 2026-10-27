# Docker 部署说明

一套 `docker compose up -d --build` 拉起完整环境：MySQL + Redis + 后端 + Nginx(前端)。

## 目录结构

```
docker-compose.yml              编排文件（4 个服务）
.dockerignore                   构建上下文裁剪
.env                            密钥（由脚本生成，git 忽略）
docker/
  Dockerfile.backend            后端多阶段构建
  Dockerfile.frontend           前端构建 + Nginx
  nginx.conf                    容器内站点配置（含 SSE 关键项）
  maven-settings.xml            Maven 阿里云镜像（仅构建期用）
  gen-env.ps1                   从 Windows 环境变量生成 .env
  mysql-init/
    01-schema.sql               表结构（从开发库导出，与实际环境一致）
    02-seed.sql                 内置测试账号
```

## 首次启动

```powershell
# 1. 生成 .env（从 Windows 用户级环境变量读取密钥）
pwsh -File docker\gen-env.ps1

# 2. 构建并启动
docker compose up -d --build

# 3. 看状态
docker compose ps
```

访问 `http://localhost:8080`。

内置账号（来自 `02-seed.sql`）：

| 账号 | 密码 | 角色 |
|---|---|---|
| admin | 12345678 | 管理员 |
| user | 12345678 | 普通用户 |
| test | 12345678 | 普通用户 |

## 端口

刻意错开宿主机可能已占用的端口，避免和本地 MySQL/Redis、手工部署的后端冲突：

| 服务 | 宿主机 | 容器内 | 用途 |
|---|---|---|---|
| mysql | 3307 | 3306 | 调试用，可删掉端口映射 |
| redis | 6380 | 6379 | 同上 |
| backend | 8568 | 8567 | 直连调试 /doc.html |
| nginx | 8080 | 80 | 正常访问入口 |

## 常用命令

```powershell
docker compose ps                       # 状态
docker compose logs -f backend          # 后端日志
docker compose logs -f nginx            # Nginx 访问日志
docker compose restart backend          # 只重启后端
docker compose down                     # 停止并删除容器（数据卷保留）
docker compose down -v                  # 连数据卷一起删（下次启动会重新初始化数据库）
docker compose up -d --build backend    # 只重建后端
```

## 几个关键设计

**1. 容器内为什么不是 localhost**

`application-prod.yml` 里写的是 `localhost:3306`，在容器内 `localhost` 指向容器自己，连不上 MySQL。
compose 用 Spring Boot 的环境变量覆盖机制改指向服务名：

```yaml
SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/ai_passage_creator?...
SPRING_DATA_REDIS_HOST: redis
```

Spring Boot 的宽松绑定会把 `SPRING_DATASOURCE_URL` 映射成 `spring.datasource.url`。
这样 `application-prod.yml` 可以一份两用：宿主机直接跑用 localhost，容器里用服务名。

**2. 数据库初始化只在首次**

`docker/mysql-init/*.sql` 挂载到 `/docker-entrypoint-initdb.d`，MySQL 官方镜像**只在数据目录为空时**执行。
所以改了 schema 之后：

```powershell
docker compose down -v      # 必须删卷，否则脚本不会重跑
docker compose up -d --build
```

生产环境不要这么做，应该单独管理迁移。

**3. 为什么用导出的 schema 而不是仓库里的 sql/**

仓库里的 `sql/create_table.sql` 是最初版本，`article` 表缺 `phase`/`style`/`titleOptions` 等 5 个字段，
而 `user` 表却已经是最新的（含 `quota`、`vipTime`）。后续的增量脚本又会重复加列：

- `update_quota.sql` 加 `quota` → 但 `create_table.sql` 里已经有了
- `add_vip_payment.sql` 加 `vipTime` → 同样重复

硬凑执行顺序会在初始化阶段报错并中断。因此 `01-schema.sql` 直接从开发库 `mysqldump --no-data` 导出，
保证结构和实际运行环境完全一致。增量脚本仅作历史记录保留。

**4. 构建上下文**

`.dockerignore` 排除了 `target/`、`node_modules/`、`dist/`、`.env`、`logs/`。
前后端都采用多阶段构建，在容器内重新编译，宿主机的产物不参与。

**5. SSE 长连接**

`docker/nginx.conf` 里必须保留这三项，缺一个都会出现「后端在推、前端转圈」：

```nginx
proxy_set_header Connection '';
proxy_buffering off;
proxy_read_timeout 1800s;
```

## 已知限制

- **后端没有 healthcheck**：`eclipse-temurin:21-jre` 基础镜像里没有 curl/wget，做不了 HTTP 探测。
  因此 Nginx 启动后可能有 7~10 秒窗口返回 502，属正常现象。
- **单实例**：`ArticleStreamBuffer`（阶段3正文快照）是进程内内存实现。
  若以后 `docker compose up --scale backend=2`，需要换成 Redis 共享。
- **Webhook**：Stripe 回调需要公网可达地址。
  本地调试用 `stripe listen --forward-to http://localhost:8080/api/webhook/stripe`。
