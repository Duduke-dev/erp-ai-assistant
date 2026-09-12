# ERP AI Assistant

基于 Spring AI 2 构建的制造业 ERP 智能助手。集成 **Tool Calling**（实时查询业务数据）与 **RAG**（检索用户导入的知识文档），支持多模型切换、流式对话、会话记忆、多租户隔离、动态 Tool 管理与业务数据图表可视化。

技术栈：Spring Boot 4.1.1 / Java 25 / Spring AI 2.0.1 / PostgreSQL + pgvector / MyBatis-Plus 3.5.17 / Sa-Token 1.46.0。

## 快速开始

### 1. 启动中间件

PostgreSQL + pgvector 是必需的（其余中间件按里程碑逐步引入）：

```bash
docker run -d --name erp-pg -p 5432:5432 \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=erp_ai \
  pgvector/pgvector:pg16

docker exec -it erp-pg psql -U postgres -d erp_ai \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

### 2. 配置模型 Key

至少需要 DashScope Key（通义千问 chat + text-embedding-v4 向量化）：

```powershell
$env:DASHSCOPE_API_KEY="你的Key"
$env:DEEPSEEK_API_KEY="你的Key"   # 可选
```

### 3. 启动应用

本机没有全局 `mvn`，使用 JDK 25 + IDEA 内置 Maven 3.9.16：

```bash
"C:/Users/18171/.jdks/temurin-25.0.4.1/bin/java" \
  -classpath "D:/IntelliJ IDEA 2026.1.1/plugins/maven-plugin/lib/maven3/boot/plexus-classworlds-2.11.0.jar" \
  "-Dclassworlds.conf=D:/IntelliJ IDEA 2026.1.1/plugins/maven-plugin/lib/maven3/bin/m2.conf" \
  "-Dmaven.home=D:/IntelliJ IDEA 2026.1.1/plugins/maven-plugin/lib/maven3" \
  "-Dmaven.multiModuleProjectDirectory=D:/local-dev/erp-ai-assistant" \
  "--enable-native-access=ALL-UNNAMED" \
  org.codehaus.plexus.classworlds.launcher.Launcher spring-boot:run
```

访问 http://localhost:8080

## 目录结构

```
com.duduke.erp
│   ── 三层架构主线（依赖单向：controller → service → mapper → DB）
├── controller/     # 表现层：HTTP 入口，不写业务
├── service/        # 业务层：编排、事务边界、领域规则
├── mapper/         # 持久层：MyBatis-Plus Mapper 接口
├── entity/         # 实体域：po/（实体，类名不带后缀）+ dto/（入参）+ vo/（出参）
│
│   ── 组件集成（第三方组件，按组件名分包）
├── component/      # satoken/ mybatisplus/ security/ context/
│
│   ── 基础设施（自研）
├── common/         # 通用组件：response/、exception/
├── config/         # 自研配置属性（TenantProperties）
└── tenant/         # 多租户运行时：上下文 / 传播 / 拦截器
```

## 文档

- [复现实施规划](docs/implementation-plan.md) — 能力取舍、版本矩阵、十个核心设计、里程碑划分
- [数据库迁移](src/main/resources/db/migration/) — Flyway 版本化脚本：`V1` 平台表 / `V2` 业务表 / `V3` 演示数据
