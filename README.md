# ERP AI Assistant

制造业 ERP 智能助手 —— 把 LLM 接入 ERP 业务系统，用 **Tool Calling** 实时查询业务数据、用 **RAG** 检索企业内部知识文档，让业务人员用自然语言完成「查数据」与「查制度」两件事。

单体架构、纯按层分包，覆盖多租户隔离、RBAC 鉴权、计费用量、文档异步解析与检索质量评测。

> **技术栈**：Java 25 · Spring Boot 4.1.1 · Spring AI 2.0.1 · MyBatis-Plus 3.5.17 · PostgreSQL 18 + pgvector · Sa-Token 1.46.0 · RabbitMQ · RustFS(S3) · Flyway · Vue 3 + TypeScript
>
> **本仓库为后端**。前端是独立仓库：[**Duduke-dev/erp-ai-assistant-web**](https://github.com/Duduke-dev/erp-ai-assistant-web)（Vue 3 + TypeScript + Vite），启动方式见 [§4.4](#44-启动前端)。

---

## 一、功能特性

| 模块 | 能力 |
|---|---|
| **智能对话** | 三种问答模式（auto / data / knowledge）统一编排；SSE 流式输出、停止生成、多轮对话记忆 |
| **业务 Tool** | 覆盖 8 大业务域共 39 个查询工具（销售 / 采购 / 仓储 / 生产 / 质检 / 售后 / 财务 / 委外） |
| **知识库检索** | 1 个知识库检索工具，由模型按需调用；文档解析、向量化、引用回答、文档版本管理 |
| **动态 SQL Tool** | 管理端配置查询模板，模型生成 SQL 经三层校验后受控执行 |
| **数据安全** | 三层 SQL 校验 + 只读事务兜底；多租户条件自动注入；RBAC 权限码鉴权 |
| **异步链路** | 文档上传 → RabbitMQ 入队 → 解析 + 向量化；进度按阶段轮询；死信队列与管理端重投 |
| **可观测** | Tool 调用追踪（traceId 聚合）、token 用量按日/月汇总、检索评测报告 |
| **前端** | 登录 / 对话 / 知识库 / 工具 / 计费 / 平台 6 个页面 |

**规模**：42 张数据表 · 18 个 Flyway 迁移 · 40 个 `@Tool` · 228 个单元/集成测试用例。

---

## 二、系统架构

### 2.1 分层与依赖方向

依赖严格单向，不允许反向调用：

```
controller  →  service  →  mapper  →  DB
```

- `controller`：只做参数绑定与响应包装，不写业务逻辑，**不出现 `mapper` 引用**
- `service`：编排、事务边界、领域规则
- `mapper`：MyBatis-Plus Mapper 接口与自定义 SQL

### 2.2 包结构

**分包基准是「层」而不是「模块」**——新增能力按层放入，不新开顶层模块包。

```
com.duduke.erp
├── controller/                 表现层
├── service/                    业务层
│   ├── tool/                   业务 Tool：8 大业务域 + 知识库检索
│   │   ├── dynamic/            动态 SQL Tool：校验 / 绑定 / 注入 / 执行
│   │   └── trace/              Tool 调用追踪
│   └── ...                     对话、RAG、知识库、计费、租户等
├── mapper/                     持久层
├── entity/
│   ├── po/                     实体（类名不带后缀）
│   ├── dto/                    入参
│   └── vo/                     出参
├── component/                  第三方组件集成，按组件名分包
│   ├── satoken/                Sa-Token 配置与权限实现
│   ├── security/               BCrypt 密码编码
│   ├── mybatisplus/            租户插件、jsonb 类型处理器
│   ├── context/                跨线程上下文传播
│   ├── springai/               ChatClient / 记忆 / Advisor
│   ├── rustfs/                 S3 客户端
│   └── rabbitmq/               MQ 拓扑（队列 / 交换机 / 死信）
├── tenant/                     多租户运行时：上下文 / 访问器 / 拦截器
├── common/                     response（统一信封）/ exception（全局异常）
└── config/                     自研 `@ConfigurationProperties`
```

> 约定：`@Configuration` 与 `@Bean` 只出现在 `component/` 下，`@ConfigurationProperties` 只出现在 `config/` 下。

### 2.3 一次对话请求的处理链路

```
ChatController
  └─ StreamPreparation        解析模式 / 会话 / 知识库，做准入校验
      └─ AssistantService     按模式装配：对话模型 + 记忆 Advisor + (RAG Advisor 或 Tool)
          ├─ assistant  模式 → 只挂 Tool（业务工具 + 知识库检索工具，模型按需调用）
          ├─ data       模式 → 只挂 Tool，提示词约束「只用业务数据」
          └─ knowledge  模式 → 只挂 RAG Advisor（检索前先做输入润色）
              └─ DeltaGate  首轮增量缓冲 → 确认拿到业务数据才放行
                  └─ AssistantLifecycleService  收口（CAS 保证只落库一次）+ 引用校验
```

---

## 三、核心设计

### 3.1 三种问答模式

| 模式 | 装配 | 适用场景 |
|---|---|---|
| `auto` | 只挂 **Tool**（业务工具 + 知识库检索工具） | 默认。业务数据与知识资料都能答，**由模型自己决定要不要查** |
| `data` | 只挂 **Tool**，提示词承诺「只用业务数据」 | 只要业务数据，不希望资料干扰 |
| `knowledge` | 只挂 **RAG Advisor**，检索前做输入润色 | 纯知识问答（查制度、查手册） |

为什么 `auto` 用「工具式」而不是「Advisor 式」：Advisor 会**每轮强制检索**——既浪费 embedding 调用，又会把弱相关段落注入上下文干扰模型使用工具结果（实测出现过「检索到的无关文档让模型忽略了工具返回的数据」）。改成工具式后，模型可以按需检索，也天然支持多轮检索。

### 3.2 流式数据门控（`ChatController.DeltaGate`）

**要解决的问题**：多轮对话里，模型常拿上一轮已算好的数字作答（「上轮显示销售额 55,000」），而用户问的其实是另一个筛选条件。这类回答数字真实、格式漂亮，但**不是本轮查出来的**。

**做法**：对「本轮本该查库」的问题，首轮增量先缓冲不下发；一旦本轮出现「成功且结果非空」的工具结果就立即放行并补发暂存；流结束时仍未放行，则丢弃暂存、改发「未查询到数据」说明。

两条被测试钉死的不变量：**未启用时完全直通**、**一旦放行就不再缓冲**（后者防重复补发）。落库时写入的是**实际下发给用户的那段说明**，避免「界面说没查到、库里存着编造内容」。

> 注意：门控只拦「完全没查数据」；查了但结果不相关属于「数据不匹配」，不归它管。

### 3.3 动态 SQL Tool 安全链路

模型生成的 SQL 直连数据库前的完整链路：

| 层 | 组件 | 作用 |
|---|---|---|
| ① | `SqlToolValidator` | 只放行单层 `SELECT`；禁子查询 / CTE / UNION（租户注入器只处理第一个 WHERE）；写操作与 DDL 关键词黑名单 |
| ② | `SqlTemplateBinder` | 命名参数绑定，避免字符串拼接 |
| ③ | `TenantSqlInjector` | 注入租户条件，**拿不到租户直接失败**，绝不放行无边界的查询 |
| ④ | `DatabaseToolExecutor` | 执行期**再校验一次**（防配置被直接改库绕过）+ `SET TRANSACTION READ ONLY` 只读事务兜底 |

两个关键取舍：

- **黑名单不区分注释与字符串**（fail-closed）：误伤一条合法配置的代价，远低于放过一条写操作。
- **必须有关键词黑名单之外的兜底**：关键词是枚举式的，永远可能漏。例如 `SELECT code INTO stolen FROM product` 是 SELECT 开头、不含任何被禁词、也不是子查询——它会**建表**。本项目把 `into` 补进了禁用词；而调用内部写库的存储函数这类情况只能靠**只读事务**由数据库拦住。

> PG 的一个反直觉点：只读事务**并不阻止 `nextval`**，别指望它挡住这类副作用。

### 3.4 多租户隔离

隔离**交给 MyBatis-Plus 租户插件**，业务代码不手写 `ent_code`：

- 插件按 `app.tenant.column`（默认 `ent_code`）自动为 SQL 追加租户条件
- 无 `ent_code` 语义的全局表必须登记进 `app.tenant.ignore-tables`（当前：`billing_plan`、`billing_price_rule`、`llm_tool`、`tenant`）
- 仅当 JOIN + GROUP BY 场景插件改写有问题时，才**方法级**关闭插件并显式写 `ent_code`；**每处手写都必须配跨租户泄漏测试**
- 租户上下文由 `TenantInterceptor` 建立：优先取 Sa-Token 会话中的租户，取不到再回落到 `X-Ent-Code` 请求头，两者都没有则直接拒绝（400），不会放行一条无租户边界的查询；跨线程传播由 `component/context` 负责——异步 / MQ 线程拿不到 ThreadLocal，必须显式携带

### 3.5 知识库异步解析（RustFS + RabbitMQ）

```
上传 → beginImport（登记版本，产生 documentId + version）   ← 上传线程
     → 投递 MQ（消息体为 JSON 字符串，且必须携带 entCode）
        → 消费侧 parseAndPromote（解析 + 分块 + 向量化）      ← MQ 监听线程
           → 写回 objectKey / bucket，stage: queued → parsing → embedding → ready
           → 失败 → 死信队列 → 管理端重投 / 丢弃
```

关键点：

- **投递与登记分离**：`beginImport` 在**上传线程**完成，消费侧只做 `parseAndPromote`。消费侧若再调一次 `importFile` 会二次登记，同一文件会多出一个版本（能编译、能运行、结果错）。
- **`entCode` 必须随消息携带**：MQ 监听线程没有 ThreadLocal 租户上下文。
- **必须配死信 + `default-requeue-rejected: false`**：否则失败消息会被立刻重新入队，形成「失败 → 重投 → 再失败」的热循环，把日志刷爆且挡住后续消息。
- **进度不加百分比**：只按 `stage` 分阶段轮询——假进度比没有进度更糟。
- **重投前必须先把状态重置回 `processing`**（状态机只认 processing），丢弃只改状态、不做物理删除。
- 对象键为 `{entCode}/{uuid}/{fileName}`：键不带租户前缀会造成跨租户影响，且没有事务可回滚。

### 3.6 RAG 检索与引用

- **向量身份契约**：`embedding-model` 写入每个向量的元数据并在检索时强制比对，换 embedding 模型后旧向量自动失效；`embedding-dimensions` 必须与 pgvector 表结构一致。
- **阈值按实测标定，不可跨模型复用**：DashScope `text-embedding-v4`（1024 维）实测相关提问 top1 分数 `0.4278 ~ 0.5558`、不相关 `0.0970 ~ 0.1828`，中间存在 `0.18 ~ 0.43` 的空档。据此 `knowledge` 模式取 `0.25`（偏召回），`auto` 模式取 `0.5`（偏精度，因为有业务工具兜底）。
- **引用编号全局递增**：模型可能分两次检索，若各自从 `[1]` 起编会撞号，而引用校验只按「在列表中的位置」匹配，结果是**引用指向错误内容且不报错**。故由 `RagRecallRecorder` 按 traceId 统一编号（工具执行线程与流式收口线程不是同一个，**不能用 ThreadLocal**）。
- **引用校验容错**：正则同时接受 `[n]` 与 `【n】` 并跳过代码块；越界编号按证据条数回绕，且回绕有边界。
- **RAG 是否生效只看 `ragDocCount` 与日志**，绝不能看回答内容——模型会编出「像成功的回答」。
- **Query Rewrite** 为规则式（`app.rag.rewrite-mode: rule`）。模型润色经 43 条用例对照实验否决：Recall@5 持平、MRR@5 下降 0.0085、评测耗时增至 4 倍。代码与开关保留，若将来补了口语化评测集可重做对照。

### 3.7 RAG 质量评测与门禁

`src/test/java/com/duduke/erp/rageval/`，按版本分目录，`v1` 保留作对照。

- **数据集 v2**：15 份目标文档 + 2 份干扰草稿（同主题未发布内容，含专门用来诱导的错误数字）+ **43 条用例**（39 可答 + 4 不可答）
- **当前基线**：`Recall@5 = 1.0`、`MRR@5 = 0.9359`
- **为什么要扩到 v2**：v1 只有 6 份文档而 `TOP_K = 5`，等于每次都必中，`Recall@5` 恒为 1.0——指标好看但**毫无区分度**。扩容后 MRR 从 1.0 降到 0.9359，指标才开始真正测量排序质量。
- **两层分离**：检索层（Recall@5 / MRR@5 / 空召回 / 越界）不依赖对话模型；答案层（关键事实 / 禁止短语 / 拒答）依赖模型 → 只做零容忍硬门禁，不进基线回归。
- **门禁只硬卡**：越界召回、关键事实违规、用例没跑完、`Recall@5` 相对基线下降超过 5pt。**不卡 Recall 绝对值**——常年红的门禁等于没有门禁。
- 报告输出到 `target/rag-eval-report.md`。

---

## 四、快速开始

### 4.1 启动中间件

PostgreSQL + pgvector 为必需；RustFS 与 RabbitMQ 在启用文档异步解析时需要。

> 仓库根目录的 `docker-compose.yml` 与 `docker/` **未纳入版本控制**（含口令），新克隆的仓库需要按下面命令自行准备，或从本地备份恢复该文件。

```bash
# 必需：PostgreSQL 18 + pgvector
docker run -d --name postgres -p 5432:5432 \
  -e POSTGRES_DB=erp_ai -e POSTGRES_USER=duduke -e POSTGRES_PASSWORD=123456 \
  -v postgres_data:/var/lib/postgresql \
  pgvector/pgvector:0.8.6-pg18

# 建扩展（若未用 initdb 脚本自动创建）
docker exec -it postgres psql -U duduke -d erp_ai \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 文档异步解析所需（可选）
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=erp -e RABBITMQ_DEFAULT_PASS=123456 \
  rabbitmq:4-management

docker run -d --name rustfs -p 9000:9000 -p 9001:9001 \
  -e RUSTFS_ACCESS_KEY=rustfsadmin -e RUSTFS_SECRET_KEY=rustfsadmin \
  -e RUSTFS_ADDRESS=":9000" -e RUSTFS_CONSOLE_ADDRESS=":9001" \
  rustfs/rustfs:1.0.0-alpha.89 /data
```

端口速查：`5432` PostgreSQL · `5672` AMQP · `15672` RabbitMQ 控制台 · `9000` S3 API · `9001` RustFS 控制台。

> ⚠️ RustFS 的 **9000 是 S3 接口**（应用侧 endpoint 用这个），9001 才是在浏览器打开的控制台。

### 4.2 配置模型凭据

在 `src/main/resources/application-local.yml` 中写入：

```yaml
spring:
  ai:
    openai:
      api-key: sk-你的DashScopeKey
```

该文件已被 `.gitignore` 忽略，并由 `application.yml` 的 `spring.profiles.default: local` 自动加载。

> 用 `default` 而不是 `active`：`default` 只在未显式指定 profile 时生效，因此 CI / 生产用 `--spring.profiles.active=prod` 启动时会自动让位，不会把本机配置带进部署环境。
>
> 若未配置 Key，应用会回落到占位符并使模型调用返回 401，表现为对话直接进 `error` 事件。**改完 Key 必须重启后端**——占位符只在启动时解析。

### 4.3 启动后端

```bash
mvn spring-boot:run
```

访问 <http://localhost:8080>。

<details>
<summary>本机没有全局 <code>mvn</code> 时的启动方式（IDEA 内置 Maven）</summary>

```bash
"C:/Users/18171/.jdks/temurin-25.0.4.1/bin/java" \
  -classpath "D:/IntelliJ IDEA 2026.1.1/plugins/maven-plugin/lib/maven3/boot/plexus-classworlds-2.11.0.jar" \
  "-Dclassworlds.conf=D:/IntelliJ IDEA 2026.1.1/plugins/maven-plugin/lib/maven3/bin/m2.conf" \
  "-Dmaven.home=D:/IntelliJ IDEA 2026.1.1/plugins/maven-plugin/lib/maven3" \
  "-Dmaven.multiModuleProjectDirectory=D:/local-dev/erp-ai-assistant" \
  "--enable-native-access=ALL-UNNAMED" \
  org.codehaus.plexus.classworlds.launcher.Launcher spring-boot:run
```

启动前记得清除工具侧注入的 `SERVER__PORT` 环境变量，它会覆盖 `application.yml` 里的 8080。
</details>

### 4.4 启动前端

前端是**独立仓库**：[Duduke-dev/erp-ai-assistant-web](https://github.com/Duduke-dev/erp-ai-assistant-web)（Vue 3 + TS + Vite，包管理器 **bun**）。

```bash
git clone https://github.com/Duduke-dev/erp-ai-assistant-web.git
cd erp-ai-assistant-web
bun install
bun run dev          # http://localhost:5173
```

若本地已与后端放在同一父目录（默认目录名 `erp-ai-assistant-web`，即 `../erp-ai-assistant-web`），直接在已有目录执行 `bun install && bun run dev` 即可，不必重新克隆。

`/api` 由 dev server 代理到后端（默认 `http://localhost:8080`，可用 `VITE_BACKEND_ORIGIN` 覆盖）。走后端代理而不是浏览器直连，是为了同源免 CORS，且 SSE 不受跨域流式响应的额外限制。

### 4.5 演示账号

| 租户 | 账号 | 密码 | 角色 |
|---|---|---|---|
| `DEMO` | `admin` | `123456` | 管理员（含动态 Tool 管理、死信管理权限） |
| `DEMO` | `viewer` | `123456` | 只读访客 |

演示数据由 Flyway `V3` / `V17` 种子脚本写入。

---

## 五、配置说明

关键配置集中在 `src/main/resources/application.yml`，全部带注释说明取舍。常用项：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `spring.ai.openai.chat.options.model` | `deepseek-v4-pro-0813` | 对话模型（切换模型只改这一行） |
| `spring.ai.openai.embedding.options.model` | `text-embedding-v4` | 向量模型 |
| `spring.ai.openai.embedding.options.dimensions` | `1024` | **必须与 `vectorstore.pgvector.dimensions` 一致** |
| `app.rag.auto-similarity-threshold` | `0.5` | auto 模式阈值（有工具兜底，偏精度） |
| `app.rag.knowledge-similarity-threshold` | `0.25` | knowledge 模式阈值（偏召回） |
| `app.rag.chunk-size` | `300` | 分块大小；过大（如 600）会让一个向量混入多个主题，召回失去区分度 |
| `app.rag.vector-write-batch-size` | `10` | **由 embedding provider 的批量上限决定**（DashScope 单次最多 10 条），换 provider 必须同步改 |
| `app.tenant.column` | `ent_code` | 租户列名 |
| `app.tenant.ignore-tables` | 见 yml | 无租户语义的全局表 |
| `app.mq.async-enabled` | `true` | 关闭后文档走同步解析，用于 MQ 不可用时降级 |
| `app.chat.memory-window-size` | `20` | 记忆窗口（约 10 轮问答） |
| `app.chat.expose-error-detail` | `true` | 流式错误是否回传原始异常摘要。**生产必须改为 `false`**（可能含表名、SQL 片段、内网地址） |

> 若需引入网络代理，注意 `spring.ai.openai.base-url` **必须带 `/v1`**：Spring AI 2.0 使用 OpenAI 官方 SDK，其 base-url 语义是「含 `/v1` 的根路径」，不会自动补全。

---

## 六、接口一览

统一响应信封 `Result{code, message, data}`；鉴权头为 `satoken`。

| 前缀 | Controller | 用途 |
|---|---|---|
| `/api/auth` | `AuthController` | 登录 / 登出 / 当前用户 / 权限探测 |
| `/api/chat` | `ChatController` | 对话（非流式 + SSE 流式）、会话与消息 |
| `/api/biz/products` | `ProductController` | 产品 |
| `/api/biz/customers` | `CustomerController` | 客户 |
| `/api/biz/inventories` | `InventoryController` | 库存 |
| `/api/biz/sales_orders` | `SalesOrderController` | 销售订单 |
| `/api/biz/sales_stats` | `SalesStatsController` | 销售统计（按客户 / 按产品 / 按月） |
| `/api/biz/knowledge_bases` | `KnowledgeBaseController` | 知识库、文档、检索自测 |
| `/api/biz/document_parse_dead_letters` | `DocumentParseDeadLetterController` | 死信列表 / 重投 / 丢弃 |
| `/api/tool` | `ToolManagementController` | 动态 Tool 配置 |
| `/api/billing` | `BillingController` / `BillingManagementController` | 计费账户与用量、套餐与流水 |
| `/api/platform/tenants` | `TenantManagementController` | 租户管理 |
| `/api/users` | `UserManagementController` | 用户与角色 |

**对话**

```
POST   /api/chat/ask                                 非流式问答
POST   /api/chat/ask/stream                          SSE 流式问答
GET    /api/chat/conversations                       会话列表
GET    /api/chat/conversations/{conversationId}/messages
DELETE /api/chat/conversations/{conversationId}
```

SSE 事件类型：`meta` / `delta` / `citations` / `warning` / `done` / `error`。

**知识库**

```
GET    /api/biz/knowledge_bases/{id}
PUT    /api/biz/knowledge_bases/{id}
DELETE /api/biz/knowledge_bases/{id}
GET    /api/biz/knowledge_bases/{id}/documents                       文档列表（含 stage）
GET    /api/biz/knowledge_bases/{id}/documents/{documentId}          文档详情 / 进度轮询
POST   /api/biz/knowledge_bases/{id}/documents                       上传文档
DELETE /api/biz/knowledge_bases/{id}/documents/{documentId}
GET    /api/biz/knowledge_bases/{id}/search                          检索自测
```

**死信管理**

```
GET  /api/biz/document_parse_dead_letters               死信列表
POST /api/biz/document_parse_dead_letters/{id}/retry     重投（内部先重置状态）
POST /api/biz/document_parse_dead_letters/{id}/discard   丢弃（只改状态，不物理删除）
```

> 接口规约（强制）：**POST 新建 / PUT 更新 / DELETE 删除 / GET 查询**；URL 不大写、下划线分隔；路径为资源名词，不带 `/page`。

---

## 七、数据库迁移

Schema 的唯一来源是 Flyway，脚本位于 `src/main/resources/db/migration/`，当前 **V1 ~ V18**：

| 脚本 | 内容 |
|---|---|
| `V1` | 系统表 17 张（租户 / 用户角色 / 对话 / 知识库 / Tool / 计费） |
| `V2` | 业务表 24 张（产品 / 客户 / 供应商 / 订单 / 库存 / 工单 / 质检 / 售后 / 财务 / 委外） |
| `V3`、`V17` | 演示数据 |
| `V4`、`V7`~`V14`、`V18` | 权限码授予 |
| `V5` | 文档 checksum |
| `V6` | 对话消息操作列 |
| `V8` | Tool Calling 相关列 |
| `V15` | 解析阶段字段 + 死信表（合计 **42 张表**，不含 pgvector 自建的 `vector_store`） |
| `V16` | 计费金额精度放宽 |

两条纪律：

- **已发布的脚本不可修改**（Flyway 会校验 checksum）。新增权限码、新增字段一律走新脚本。
- **必须使用 `spring-boot-starter-flyway`**：Boot 4 起 Flyway 自动配置已拆为独立模块，只引 `flyway-core` 会导致迁移**完全不执行且启动日志没有任何 Flyway 输出**。

---

## 八、测试

```bash
mvn test                 # 单元 + 集成测试（Surefire），当前 228 个用例
mvn verify               # 额外执行 *IT 集成测试（Failsafe），含 RAG 端到端评测
mvn verify -DskipITs     # 跳过 IT
```

- **228 个 `@Test`**，无参数化测试；数据用 UUID 后缀并在 `finally` 清理。
- 一律使用 `@SpringBootTest` 全上下文——租户插件、Flyway、Sa-Token 拦截器任一环失效，切片测试都测不出来。
- `*IT` 不进 `mvn test`（Surefire 默认只匹配 `*Test`），由 **failsafe** 在 `verify` 阶段执行。
- `RagEvalIT` 需要有效的模型 Key（导入 fixture 要调用云端 embedding）；Key 不可用时以 `Assumptions.assumeTrue` **跳过而非失败**。评测约 73 秒、数十次 API 调用。
- 集成测试使用 **`TEST_IT` 专属租户**，不要用演示租户 `DEMO`。

> Boot 4 起 MockMvc 支持已从 `spring-boot-starter-test` 拆出，必须单独引 `spring-boot-starter-webmvc-test`；注解包名也有迁移（`@MockBean` → `@MockitoBean`，Jackson 包名改为 `tools.jackson.*`）。

---

## 九、已知限制与待办

| 项 | 状态 |
|---|---|
| **单模型** | 原规划的多模型路由（`ModelRegistry`）已舍弃——只有一套 OpenAI 兼容配置时，多 provider 缓存是无用复杂度。切换模型改 `spring.ai.openai.chat.options.model` 即可，编排代码不受影响 |
| **Redis** | 本地 compose 已启动（`redis:8`），但**代码尚未使用**，预留给限流 / 缓存 |
| **图表可视化** | 已移除（服务端与前端均不再提供） |
| **Reranker** | 未实现，仅预留接口 |
| **Query Rewrite 模型润色** | 已实现但经对照实验否决，默认固定为 `rule` |
| **`docs/`、`docker-compose.yml`、`application-local.yml`** | 均未纳入版本控制（含口令或为本地规划文档），新环境需自行准备 |

---

## 十、文档

| 文档 | 位置 | 说明 |
|---|---|---|
| 复现实施规划 | `docs/implementation-plan.md` | 能力取舍、版本矩阵、十个核心设计、里程碑划分（**本地保留，未入库**） |
| 踩坑记录 | `docs/pitfalls.md` | 静默失效清单与规避措施（**本地保留，未入库**） |
| 数据库迁移 | `src/main/resources/db/migration/` | Schema 唯一来源，必须入库 |
| 评测报告 | `target/rag-eval-report.md` | 每次跑 `RagEvalIT` 后生成 |
