# AI Passage Creator

> 一套以 **AI 智能体协作 + 多模态内容生成** 为核心的 AI 图文创作平台。
> 基于 Spring Boot 3 + Spring AI Alibaba + Vue 3 开发的个人学习项目。

⚠️ **项目状态：开发中（脚手架阶段）**。当前已完成工程骨架、统一响应与异常体系、用户模块与注解式权限校验；
AI 图文生成相关的核心业务尚未开始编码，具体进度见下方[开发进度](#开发进度)。

---

## 目录

- [技术栈](#技术栈)
- [功能特性](#功能特性)
- [开发进度](#开发进度)
- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [接口一览](#接口一览)
- [设计约定](#设计约定)
- [开发路线](#开发路线)

---

## 技术栈

### 后端

| 分类 | 技术 | 版本 | 说明 |
| --- | --- | --- | --- |
| 基础框架 | Spring Boot | 3.5.14 | Java 21 |
| Web | spring-boot-starter-web | — | RESTful API |
| ORM | MyBatis-Flex | 1.11.1 | `mybatis-flex-spring-boot3-starter` |
| 连接池 | HikariCP | 由 Boot BOM 管理 | — |
| 数据库 | MySQL | 8.x | `mysql-connector-j` |
| 缓存 / 会话 | Redis | — | 会话存储，30 天过期 |
| 会话管理 | Spring Session Data Redis | — | 分布式会话 |
| 切面 | spring-boot-starter-aop | — | 权限校验 AOP |
| 接口文档 | Knife4j + springdoc-openapi | 4.4.0 / 2.8.17 | springdoc 已统一抬版本 |
| 工具库 | Hutool | 5.8.43 | — |
| 热重载 | Spring Boot DevTools | — | 开发期使用 |

> **关于 springdoc 版本**：knife4j 4.4.0 传递进来的 springdoc 是 2.3.0，它在 Spring Framework 6.2（Boot 3.4+）
> 上生成文档时会抛 `NoSuchMethodError`（`ControllerAdviceBean(Object)` 构造器已被移除）。
> 因此 `pom.xml` 的 `dependencyManagement` 中把三个 springdoc 构件统一抬到 **2.8.17**。

### 前端

| 分类 | 技术 | 版本 |
| --- | --- | --- |
| 框架 | Vue | 3.5 |
| 语言 | TypeScript | 5.9 |
| 构建 | Vite | 7.3 |
| UI 组件库 | Ant Design Vue | 4.2 |
| 状态管理 | Pinia | 4.0 |
| 路由 | Vue Router | 4.6 |
| HTTP | Axios | 1.20 |
| 接口代码生成 | @umijs/openapi | 1.14 |

---

## 功能特性

### 已实现

- **统一响应体系**：`BaseResponse<T>`（`code` / `data` / `message`）+ `ErrorCode` 枚举 + 全局异常处理
- **用户模块**：注册、登录、注销、获取当前登录用户
- **数据脱敏**：出入参使用 DTO / VO 隔离实体，`LoginUserVO`、`UserVO` 均不含密码字段
- **注解式权限校验**：`@AuthCheck(mustRole = "admin")` + AOP 环绕拦截
- **管理员接口**：用户增删改查、分页查询
- **逻辑删除**：MyBatis-Flex `@Column(isLogicDelete = true)`
- **前端权限路由**：路由守卫拦截 `/admin/**`，未登录 / 非管理员自动跳转登录页
- **接口代码自动生成**：后端 OpenAPI 文档 → 前端 TypeScript 调用代码

### 规划中

- AI 图文生成（对接 Spring AI Alibaba）
- 文章 / 图片素材管理
- 生成历史与结果收藏
- 用户额度与积分体系
- 流式输出（SSE）

---

## 开发进度

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| 工程骨架 | ✅ 已完成 | Maven 多环境配置、跨域、热重载 |
| 统一响应与异常 | ✅ 已完成 | BaseResponse / ErrorCode / GlobalExceptionHandler |
| 用户模块 | ✅ 已完成 | 注册、登录、注销、查询、脱敏 |
| 权限校验 | ✅ 已完成 | @AuthCheck + AuthInterceptor |
| 前端脚手架 | ✅ 已完成 | 布局、路由、状态管理、请求封装 |
| 数据库脚本 | ✅ 已完成 | `sql/create_table.sql` |
| **AI 图文生成** | ⬜ 未开始 | 核心业务 |
| 文章管理 | ⬜ 未开始 | — |
| 单元测试 | ⚠️ 待补充 | 目前仅上下文启动测试 |

---

## 快速开始

### 环境要求

| 依赖 | 版本要求 |
| --- | --- |
| JDK | 21+ |
| Maven | 3.8+（或使用项目内置 `mvnw`） |
| MySQL | 8.x |
| Redis | 6.x+ |
| Node.js | ^20.19.0 或 >=22.12.0 |
| npm | 10+ |

### 1. 克隆项目

```bash
git clone https://github.com/xuanj4323-collab/ai-passage-creator.git
cd ai-passage-creator
```

### 2. 初始化数据库

```bash
mysql -u root -p < sql/create_table.sql
```

脚本会创建 `ai_passage_creator` 库、`user` 表，并插入 3 个测试账号。

### 3. 配置后端

数据库密码**没有硬编码在配置文件里**，通过环境变量注入。查看 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ai_passage_creator
    username: root
    password: ${Mysql_Password}
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
```

**启动前必须设置环境变量 `Mysql_Password`**，否则占位符解析失败会启动报错。

PowerShell：

```powershell
$env:Mysql_Password="你的数据库密码"
```

Bash：

```bash
export Mysql_Password="你的数据库密码"
```

> IDEA 用户也可以在「Run/Debug Configurations → Environment variables」中配置。

同时请确保 **MySQL 与 Redis 服务已启动**。

### 4. 启动后端

```bash
# 方式一：Maven Wrapper（推荐，无需本地安装 Maven）
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run

# 方式二：打包后运行
./mvnw clean package -DskipTests
java -jar target/ai-passage-creator-0.0.1-SNAPSHOT.jar
```

- 服务端口：**8567**
- 接口前缀：**/api**
- 健康检查：<http://localhost:8567/api/health/>
- 接口文档（Knife4j）：<http://localhost:8567/api/doc.html>

### 5. 启动前端

```bash
cd frontend/ai-passage-creator
npm install
npm run dev
```

访问 <http://localhost:5173>（Vite 默认端口）。

> 前端 Axios 的 `baseURL` 硬编码为 `http://localhost:8567/api`，而后端已通过 `CorsConfig` 放行跨域，
> 因此前端无需配置代理即可直接请求后端。

### 6. 测试账号

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| admin | 12345678 | 管理员 |
| user | 12345678 | 普通用户 |
| test | 12345678 | 普通用户 |

---

## 项目结构

```
ai-passage-creator/
├── pom.xml                          # Maven 配置与依赖版本仲裁
├── mvnw / mvnw.cmd                  # Maven Wrapper
├── sql/
│   └── create_table.sql             # 建库、建表、初始化数据
├── src/
│   ├── main/
│   │   ├── java/com/mypj/aipassagecreator/
│   │   │   ├── AiPassageCreatorApplication.java   # 启动类
│   │   │   ├── annotation/          # 自定义注解（AuthCheck）
│   │   │   ├── aop/                 # 切面（AuthInterceptor 权限校验）
│   │   │   ├── common/              # 通用返回、分页、请求基类
│   │   │   ├── config/              # 配置类（CorsConfig）
│   │   │   ├── constant/            # 常量（UserConstant）
│   │   │   ├── controller/          # 控制层
│   │   │   ├── exception/           # 错误码、业务异常、全局异常处理
│   │   │   ├── mapper/              # MyBatis-Flex Mapper
│   │   │   ├── model/
│   │   │   │   ├── dto/             # 入参对象
│   │   │   │   ├── entity/          # 数据库实体
│   │   │   │   ├── enums/           # 枚举
│   │   │   │   └── vo/              # 出参对象（脱敏视图）
│   │   │   └── service/             # 业务接口与实现
│   │   └── resources/
│   │       └── application.yml      # 应用配置
│   └── test/java/                   # 测试代码
└── frontend/
    └── ai-passage-creator/
        ├── openapi2ts.config.ts     # 接口代码生成配置
        ├── vite.config.ts
        └── src/
            ├── api/                 # 自动生成的接口调用代码
            ├── components/          # 全局组件（Header / Footer）
            ├── constants/           # 前端常量
            ├── layouts/             # 布局组件
            ├── pages/               # 页面
            ├── router/              # 路由配置
            ├── stores/              # Pinia 状态管理
            ├── access.ts            # 全局路由守卫与权限校验
            ├── request.ts           # Axios 实例与响应拦截器
            └── main.ts              # 入口
```

---

## 接口一览

统一前缀：`/api`　｜　统一响应体：`{ "code": 0, "data": ..., "message": "ok" }`

### 健康检查

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/health/` | 服务健康检查 | 公开 |

### 用户模块

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/user/register` | 用户注册 | 公开 |
| POST | `/user/login` | 用户登录 | 公开 |
| GET | `/user/get/login` | 获取当前登录用户 | 登录 |
| POST | `/user/logout` | 用户注销 | 登录 |
| POST | `/user/add` | 创建用户 | 管理员 |
| GET | `/user/get` | 根据 id 获取用户（实体） | 管理员 |
| GET | `/user/get/vo` | 根据 id 获取用户（脱敏） | 管理员 |
| POST | `/user/delete` | 删除用户 | 管理员 |
| POST | `/user/update` | 更新用户 | 管理员 |
| POST | `/user/list/page/vo` | 分页查询用户（脱敏） | 管理员 |

### 前端接口代码生成

后端启动后执行，即可从 OpenAPI 文档重新生成前端调用代码：

```bash
cd frontend/ai-passage-creator
npm run openapi2ts
```

---

## 设计约定

### 统一响应与错误码

所有接口返回 `BaseResponse`，`code = 0` 表示成功。错误码定义见 `ErrorCode`：

| 错误码 | 含义 |
| --- | --- |
| 0 | 成功 |
| 40000 | 请求参数错误 |
| 40100 | 未登录 |
| 40101 | 无权限 |
| 40300 | 禁止访问 |
| 40400 | 请求数据不存在 |
| 42900 | 请求过于频繁 |
| 50000 | 系统内部异常 |
| 50001 | 操作失败 |

### 权限校验

在 Controller 方法上标注注解即可完成角色校验，无需手写 `if` 判断：

```java
@PostMapping("/delete")
@AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
public BaseResponse<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest) {
    // ...
}
```

`AuthInterceptor` 会从 Session 中取出登录用户并比对角色，不符则抛出 `NO_AUTH_ERROR`。

### 会话与登录态

- 登录成功后把用户对象写入 Session（键名 `user_login`，见 `UserConstant`）
- Session 由 Redis 存储，**30 天**过期，应用重启不丢失登录态
- 前端 Axios 配置 `withCredentials: true`，依赖 Cookie 维持会话
- 响应码为 `40100` 时，前端响应拦截器自动跳转登录页并带上 `redirect` 参数

### 密码存储

- 加盐 MD5：`MD5(密码 + 固定盐值)`
- ⚠️ **仅用于学习演示**。MD5 加固定盐无法抵御彩虹表与暴力破解，生产环境请改用 **BCrypt / Argon2** 等自适应哈希算法

---

## 开发路线

- [x] 工程骨架与统一响应体系
- [x] 用户模块（注册 / 登录 / 注销 / 管理）
- [x] 注解式权限校验
- [x] 前端脚手架与权限路由
- [ ] 引入 Spring AI Alibaba，接通大模型对话能力
- [ ] 图文生成核心业务与 `article` 相关数据表
- [ ] 生成结果流式输出（SSE）
- [ ] 生成历史、素材库、收藏
- [ ] 用户额度与积分体系
- [ ] 补充单元测试与接口测试

---

## 已知事项

- 用户分页查询的排序字段目前直接拼接到 SQL 中，尚未做字段白名单校验，后续需加固
- `application.yml` 中的 JDBC URL 未显式指定字符集与时区参数，如遇中文乱码或时区报错可追加
  `?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai`
- 单元测试目前仅有 Spring 上下文启动测试，待补充业务测试

---

## License

本项目仅用于个人学习与技术实践，未指定开源许可证。
