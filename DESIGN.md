# 知乎圆桌辩论 · 看山 — 设计文档

> 项目代号：kanshan-debate  
> 技术栈：Spring Boot 3 · j-langchain 1.0.15 · Qwen / Zhida · 阿里云嵌入 · SQLite · SSE  
> 活动：知乎 Hackathon 2026

---

## 一、背景与动机

知乎是中国最大的问答社区，每个热门问题下都聚集着来自不同背景、持不同立场的答主。然而用户浏览时往往只看到"最高赞"一条答案，形成信息茧房——大量真实、深度、有分歧的声音被淹没。

**看山辩论**的核心想法：把知乎上同一话题的多元真实观点，提炼成一场"AI 驱动的圆桌辩论"，让用户在几分钟内看到同一问题的 N 种答案的思辨碰撞，而不是被算法过滤后的单一声音。

> 每个观点，都有出处。每场辩论，都是知乎真实声音。

---

## 二、需求

### 2.1 功能需求

| # | 需求 | 优先级 |
|---|------|--------|
| F1 | 用户输入任意争议话题，系统自动发起一场圆桌辩论 | P0 |
| F2 | 辩论内容来源于知乎真实答主回答，每条发言附带原文引用链接 | P0 |
| F3 | 辩论分三阶段：开场陈述 → 交叉质询 → 综合综述 | P0 |
| F4 | 所有阶段实时流式推送，用户看到打字机效果 | P0 |
| F5 | 综述由深度思考模型（Zhida）生成，流式呈现，有降级兜底 | P1 |
| F6 | 热门话题快捷入口（来自知乎热榜缓存） | P1 |
| F7 | 相同话题二次提问命中缓存，秒级返回 | P1 |
| F8 | 用户可随时中止进行中的辩论 | P1 |
| F9 | 知乎 API 限流等错误，明确提示用户而不是崩溃 | P1 |
| F10 | Token 令牌访问控制（内测期限流） | P2 |

### 2.2 非功能需求

- **延迟**：首屏 turn 在 6s 内出现（用户体感流畅）
- **稳定性**：内容审核 / API 限流不影响整体流程（多级降级）
- **可维护**：本地 dev 与线上 prod 配置严格分离
- **安全**：令牌加密存储，不明文持久化使用量

---

## 三、设计思路

### 3.1 核心抽象：观点聚类 → 立场人格化 → 结构化辩论

```
知乎答案（原始文本）
      ↓ LLM 聚类
立场分组（3–4 个，有标签 + 核心论点 + emoji）
      ↓ 角色扮演
辩手发言（每轮从向量库召回证据 + Prompt）
      ↓ 深度思考综述
辩论综述（Zhida → Qwen → 模板三级降级）
```

**关键设计决策**：
- 辩手不是凭空创造的 AI，而是"代表知乎上持该立场的答主群体"，每句话都有原文出处
- 聚类粒度控制在 3–4 个立场，太多分散注意力，太少失去辩论张力
- 主持人（HOST turn）承担结构叙事，不参与观点博弈，页面上用蓝色左边框区分

### 3.2 流式架构：SSE + 事件分层

前端通过 Server-Sent Events 订阅同一个 sessionId，后端按事件类型推送：

```
stances        → 渲染立场标签栏
turn           → 渲染单条辩论卡片（HOST/OPENING/CROSS/SUMMARY）
summaryToken   → 综述流式打字（独立事件，不进 DB）
status         → 更新进度横幅（CLUSTERING/DEBATING/COMPLETED/ERROR）
error          → 渲染红色错误卡片
```

`summaryToken` 与 `turn` 刻意分离：token 不入库，只用于前端动效；等 LLM 完成后再把完整文本作为 SUMMARY turn 持久化。

### 3.3 RAG 证据链路

```
每个立场 → InMemoryVectorStore（阿里云嵌入）
                  ↓ similaritySearch(topic, k=2)
             证据片段（含 @答主名）
                  ↓ 拼入 Prompt
             LLM 发言（要求标注引用）
                  ↓ 正则提取 @mention
             Citation 列表 → 前端展示为引用芯片
```

无嵌入 API Key 时自动退化为空检索，发言质量略降但流程不中断。

### 3.4 多级降级策略

每个有 LLM 调用的环节都有至少两级降级：

```
generateSpeech:
  正常调用 Qwen（带证据）
    └─ 内容审核 400 → 去掉证据重试
         └─ 再次失败 → 模板兜底发言

generateSummary:
  Zhida 深度思考流式
    └─ 失败/空 → Qwen 流式
         └─ 失败 → 纯模板拼接

ZhihuApiService:
  真实接口（ZHIHU_SECRET 配置时）
    └─ 未配置 → MockService（离线演示）
```

---

## 四、实现方式

### 4.1 技术选型

| 组件 | 选型 | 原因 |
|------|------|------|
| LLM 框架 | j-langchain 1.0.15 | 阿里巴巴出品，原生支持 Qwen / 阿里云嵌入，ChainActor API 简洁 |
| 辩手模型 | qwen3.5-flash-2026-02-23 | 速度快、成本低、中文推理强 |
| 综述模型 | zhida-thinking-1p5 | 深度思考，综述质量更高；支持流式 |
| 嵌入模型 | 阿里云 text-embedding | RAG 证据召回 |
| Web 框架 | Spring Boot 3.2 | 生产成熟，SSE 原生支持 |
| 持久化 | SQLite + JPA | 单机部署零运维，WAL 模式支持并发读写 |
| 前端 | 原生 HTML/JS + Thymeleaf | 无构建工具，部署简单，EventSource 原生支持 SSE |

### 4.2 j-langchain 关键用法

**同步调用（辩手发言）：**
```java
FlowInstance chain = chainActor.builder()
    .next(PromptTemplate.fromTemplate("${prompt}"))
    .next(ChatAliyun.builder().model("qwen3.5-flash-2026-02-23")
        .temperature(0.7f)
        .modelKwargs(Map.of("enable_thinking", false))
        .build())
    .next(new StrOutputParser())
    .build();
ChatGeneration result = chainActor.invoke(chain, Map.of("prompt", prompt));
```

**流式调用（综述）：**
```java
ChatGenerationChunk chunk = chainActor.stream(zhidaChain, Map.of("prompt", prompt));
while (chunk.getIterator().hasNext()) {
    String token = chunk.getIterator().next().getText();
    if (token != null && !token.isEmpty()) onToken.accept(token);
}
```

**429 快速失败修复：**  
`DoListener.onError()` 发送 ERROR code 但不追加 STOP finish reason，导致迭代器阻塞 60s。
通过覆写 `ChatZhida.getConsumer()` 在 ERROR 时立即追加 STOP chunk：
```java
@Override
protected Consumer<AiChatOutput> getConsumer(AIMessageChunk aiMessageChunk) {
    Consumer<AiChatOutput> parent = super.getConsumer(aiMessageChunk);
    return output -> {
        if (AiChatCode.ERROR.getCode().equals(output.getCode())) {
            AIMessageChunk stopChunk = AIMessageChunk.builder()
                .finishReason(FinishReasonType.STOP.getCode()).build();
            aiMessageChunk.add(stopChunk);
            try { aiMessageChunk.getIterator().append(stopChunk); }
            catch (TimeoutException e) { throw new RuntimeException(e); }
            return;
        }
        parent.accept(output);
    };
}
```

### 4.3 SSE 竞态问题解决

`runAsync` 在 Spring `@Async` 线程池执行，可能在前端 SSE 建连前就完成（尤其错误路径，<1s）。
`subscribe()` 在返回 SseEmitter 后立即检查 session 状态并补放历史事件：

```java
} else if (session.getStatus() == DebateSession.Status.ERROR) {
    String msg = session.getSummary();   // 错误消息持久化在 summary 字段
    if (msg != null) sendEvent(emitter, "error", Map.of("message", msg));
    sendEvent(emitter, "status", Map.of("status", "ERROR"));
    emitter.complete();
}
```

### 4.4 Token 访问控制

```
Token 生成（本地运行 TokenCipher.main）：
  label + limit → AES-256-GCM 加密 → Base64url token

Token 校验（startDebate API）：
  token → 解密 → 取 payload.limit()
  SHA-256(token)[:12] → 在加密存储中查用量
  used >= limit → 429 拒绝
  否则 increment → runAsync

存储：
  ConcurrentHashMap<anonymizedKey, count>
  每次 increment 后序列化为 JSON → AES-GCM 加密 → token_usage.enc
```

令牌只在新建会话（PENDING）时消耗，命中缓存（COMPLETED）时免费复用。

---

## 五、项目结构

```
kanshan-debate/
├── src/main/java/com/zhihu/kanshan/debate/
│   ├── KanshanDebateApplication.java        # 入口
│   ├── agent/
│   │   └── DebateOrchestrator.java          # 核心辩论引擎（聚类→RAG→LLM→综述）
│   ├── auth/
│   │   ├── TokenCipher.java                 # AES-256-GCM 加解密 + 令牌生成 CLI
│   │   └── TokenUsageStore.java             # 加密持久化用量计数
│   ├── controller/
│   │   └── DebateController.java            # REST API（start/events/stop/health）
│   ├── llm/
│   │   ├── ChatZhida.java                   # Zhida 模型节点（覆写错误处理）
│   │   └── ZhidaActuator.java               # Zhida HTTP 执行器（自定义 Header）
│   ├── model/
│   │   ├── Answer.java                      # 知乎答案（含 keySnippets）
│   │   ├── Citation.java                    # 引用（answerUrl/authorName/upvotes）
│   │   ├── DebateSession.java               # JPA 实体（话题→状态→turnsJson）
│   │   ├── DebateTurn.java                  # 单条发言（类型/辩手/引用/时间戳）
│   │   └── StanceGroup.java                 # 立场分组（标签/emoji/keyArguments/答案列表）
│   ├── repository/
│   │   └── DebateSessionRepository.java     # JPA 查询（findByTopicHashAndStatus）
│   └── service/
│       ├── ClusteringService.java           # LLM 聚类答案为立场（含 JSON 解析 + 降级）
│       ├── DebateSessionService.java        # 会话管理 + SSE 推送 + @Async 编排
│       ├── ZhihuApiService.java             # 接口定义
│       ├── ZhihuApiRealService.java         # 真实知乎 API（ZHIHU_SECRET 激活）
│       └── ZhihuApiMockService.java         # Mock（本地开发/演示）
├── src/main/resources/
│   ├── application.yml                      # 公共配置（端口/DB占位符/默认值）
│   ├── application-dev.yml                  # Dev 本地路径
│   ├── application-prod.yml                 # Prod 绝对路径（/data/kanshan/）
│   ├── cache/                               # 热榜话题缓存（JSON）
│   ├── db/
│   │   └── debate.db                        # SQLite（Dev）
│   ├── logo.png                             # 页面 favicon
│   └── templates/
│       └── index.html                       # 单页 SPA（Thymeleaf + 原生 SSE）
├── pom.xml
└── DESIGN.md                                # 本文档
```

### 数据模型

```
DebateSession
  id           VARCHAR PK
  topic        TEXT
  topicHash    VARCHAR(16)    -- SHA-256[:16] 用于缓存命中
  status       ENUM(PENDING/CLUSTERING/DEBATING/COMPLETED/ERROR)
  stancesJson  TEXT           -- List<StanceGroup>
  turnsJson    TEXT           -- List<DebateTurn>
  summary      TEXT           -- 最终综述（或错误消息）
  createdAt    DATETIME
```

---

## 六、完成总结

### 已实现功能

- [x] 知乎答案搜索 + 自动立场聚类（3–4 个，带 emoji 和核心论点）
- [x] 三阶段结构化辩论（开场 → 质询 → 综述）
- [x] RAG 证据召回（阿里云嵌入 + InMemoryVectorStore）
- [x] 发言内 @mention 解析 → Citation 芯片（含答主链接 + 点赞数）
- [x] SSE 实时推流，前端打字机动效
- [x] Zhida 深度思考综述（流式），Qwen 降级，模板三级降级
- [x] 429 错误快速失败（覆写 getConsumer 避免阻塞 60s）
- [x] 内容审核 400 降级（去除证据重试 → 模板兜底）
- [x] 话题缓存（topicHash 命中秒级返回）
- [x] 热榜话题快捷入口（知乎 API 或本地 JSON 缓存）
- [x] 用户随时中止辩论（AtomicBoolean stop signal）
- [x] API 限流错误红色卡片展示 + SSE 竞态修复（ERROR 状态回放）
- [x] Spring Profile 分离（dev/prod DB 路径、缓存目录）
- [x] AES-256-GCM Token 访问控制（可选，未配置时自动禁用）
- [x] 零依赖单页前端（无构建工具）

### 技术挑战与解决

| 挑战 | 解决方案 |
|------|----------|
| j-langchain 流式 ERROR 不触发 STOP，阻塞 60s | 覆写 `getConsumer()` 在 ERROR 时立即注入 STOP chunk |
| LLM 回答内容被平台审核 400 | 去除 RAG 证据重试（原始答主文本可能含敏感词） |
| SSE 订阅晚于 async 任务完成（竞态） | `subscribe()` 检查终态并立即回放历史事件 |
| AES Key 长度错误（任意字符串无法直接作 Key） | SHA-256 派生固定 32 字节 Key |
| 答主 @mention 跨立场匹配 | 全局 `authorName → Answer` map + 最长前缀模糊匹配 |
| SQLite 并发写（WAL + Hikari pool=5） | 正确配置 WAL journal mode，减少锁竞争 |

---

## 七、使用方法

### 7.1 本地开发启动

```bash
# 1. 配置环境变量（最少需要 ALIYUN_KEY）
export ALIYUN_KEY=sk-xxxxx
export ZHIHU_SECRET=xxx      # 不配则 Mock 模式
# export TOKEN_SECRET=       # 不配则无访问控制

# 2. 启动（默认 dev profile）
mvn spring-boot:run

# 3. 访问
open http://localhost:8080/kanshan-debate
```

### 7.2 生产部署

```bash
# 构建
mvn clean package -DskipTests

# 启动（激活 prod profile）
java -jar target/kanshan-debate-*.jar \
  --spring.profiles.active=prod \
  --ALIYUN_KEY=$ALIYUN_KEY \
  --ZHIHU_SECRET=$ZHIHU_SECRET \
  --TOKEN_SECRET=$TOKEN_SECRET
```

Prod 数据目录：
- 数据库：`/data/kanshan/debate-prod.db`
- 缓存：`/data/kanshan/cache/`
- 令牌用量：`/data/kanshan/cache/token_usage.enc`

### 7.3 生成访问令牌

```bash
# 生成一个允许使用 50 次的令牌，标签为"内测用户A"
TOKEN_SECRET=your-secret java -cp target/kanshan-debate-*.jar \
  com.zhihu.kanshan.debate.auth.TokenCipher "内测用户A" 50

# 输出示例：
# Token: eyJhbGci...
# URL:   http://your-host/kanshan-debate?token=eyJhbGci...
```

用户通过 `?token=xxx` 访问，前端自动携带令牌，无感知。

### 7.4 Mock 模式演示

不配置 `ZHIHU_SECRET` 时自动切换 `ZhihuApiMockService`，使用预置的样本答案和热榜话题，无需任何外部 API 依赖（`ALIYUN_KEY` 仍需配置以调用 LLM）。

---

## 八、未来功能扩展

### 8.1 辩论质量提升

**多轮辩手记忆**  
当前每轮发言无状态，未来可将前 N 轮对话历史注入 Prompt，实现真正的"有来有往"：辩手会记住自己说过什么，不重复论点。

**立场动态演化**  
引入"说服度"指标，若某辩手在质询中被对方论据击中要害，可让其在下一轮适度修正立场，展示观点碰撞下的思想演化。

**辩手个性化人格**  
除立场外，给每个辩手赋予知乎答主的真实画像（职业、城市、背景），发言风格随之调整——比如同一立场下，"应届生程序员"和"40岁 CTO"的说话方式会很不同。

### 8.2 信息深度

**知识图谱引用增强**  
当前 RAG 仅检索文本片段；未来可构建话题知识图谱，让辩手引用数据、研究报告、政策文件等结构化知识，大幅提升说服力。

**实时争议热度追踪**  
辩论结束后，持续监听该话题下新增的高赞答案，发现立场漂移时自动触发"续集辩论"，形成动态演化的辩论时间线。

**多语言辩论**  
同一话题下，有时中英文社区的观点截然不同（如 AI 替代工作）。可接入 Reddit / Quora 等平台，生成跨文化视角的辩论。

### 8.3 用户参与

**观众投票与立场倾斜**  
每轮结束后展示实时投票，用户为自己支持的立场加分；高支持率的立场在下一轮获得更多发言权，模拟真实辩论的节奏张力。

**用户插话**  
允许用户在辩论过程中输入一个观点，系统将其作为"特别嘉宾"注入下一轮，AI 辩手需要对用户观点作出回应。

**辩论卡片分享**  
每条发言生成一张精美分享卡（含立场色、发言摘要、来源答主），一键分享到知乎/微博/微信，引流回辩论全文。

### 8.4 产品化

**辩论订阅 / 定时触发**  
用户订阅某话题，每日热搜新变化时自动生成新一轮辩论，推送摘要 Newsletter。

**辩论历史 & 观点进化**  
同一话题跨时间维度的多次辩论对比：2年前知乎怎么看"35岁危机"，今天的答主观点有何变化，用时间轴可视化展示。

**API 开放平台**  
将辩论生成能力封装为 API，供其他应用（教育平台、咨询工具、新闻媒体）调用，生成"争议话题速览"模块。

**辩论质量评分**  
引入第三方（另一个 LLM）对辩论质量打分：逻辑严密性、证据充分性、立场覆盖度，帮助用户快速判断本次辩论的参考价值。

---

## 附录

### 关键配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `ALIYUN_KEY` | 无 | 阿里云 API Key（必填） |
| `ZHIHU_SECRET` | 空（Mock） | 知乎 API 凭证 |
| `TOKEN_SECRET` | 空（无控制） | 令牌加密密钥 |
| `debate.max-rounds` | 3 | 交叉质询轮数 |
| `debate.cache-enabled` | true | 话题缓存开关 |
| `server.port` | 8080 | HTTP 端口 |

### 事件类型速查

| SSE 事件 | payload | 含义 |
|----------|---------|------|
| `stances` | `List<StanceGroup>` | 立场聚类完成 |
| `turn` | `DebateTurn` | 新增一条发言 |
| `summaryToken` | `{token: string}` | 综述流式字符 |
| `status` | `{status, message}` | 状态变更 |
| `error` | `{message}` | 错误（红色卡片） |

### 依赖版本

| 依赖 | 版本 |
|------|------|
| Spring Boot | 3.2.12 |
| j-langchain | 1.0.15 |
| SQLite JDBC | 3.45.1.0 |
| Hibernate Community SQLite Dialect | 6.4.4.Final |
| Gson | 2.10.1 |
| Java | 17 |
