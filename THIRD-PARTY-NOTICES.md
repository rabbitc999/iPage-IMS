# 第三方组件与许可证声明

本文件记录 `ipage-ims` 仓库中**非原创**代码与依赖的许可证归属，以及分发时的合规注意事项。
最后更新：2026-11（随代码变更同步维护）。

---

## 一、源码级内置模块（libs/）

这两个模块是把上游项目**源码直接复制**进仓库的，不是 Maven 依赖，因此许可证随源码一起生效。

### 1. libs/ipage — Apache License 2.0

| 项 | 内容 |
| --- | --- |
| 上游 | `iPage`（注解驱动的 SQL 引擎） |
| 仓库 | GitHub：<https://github.com/rabbitc999/iPage> |
| 复制自提交 | `0a3da8e6d199936a26f9f6e8237b85f024ce3679`（2026-10-17） |  
| 许可证 | Apache-2.0，全文见 `libs/ipage/LICENSE` |  
| 是否修改 | **已修改 9 个源码文件**（2026-11-04，上游同日以 `fd9501e` 收编同一改动）：`FieldType` 新增 `IMAGE` 图像类型、四个方言的 `byte[]`→BLOB 映射、`ValueConverter` 的 BLOB 读取收口、`ModelMeta` 的 IMAGE 操作符限制、`ModelSession` 的 String 主键自动补 UUID；2026-11-05 又修改 `SqlStatement`，把 `LIKE` / `STARTS_WITH` / `ENDS_WITH` 渲染为 `LOWER(列) LIKE LOWER(模式)`，消除 PostgreSQL 大小写敏感导致的空查询；修改文件头部均按 Apache-2.0 义务标注了修改说明 |  
| 义务 | 保留 LICENSE 与版权声明；若修改文件需标注修改 |

Apache-2.0 与 MIT 兼容，可以放心一起分发。**使用、修改、闭源商用均允许**。

### 2. libs/embed-ai-chat — GNU AGPL-3.0 ⚠️

| 项 | 内容 |
| --- | --- |
| 上游 | `EmbedAiChat`（可嵌入宿主的 AI 控制台框架） |
| 仓库 | GitHub：<https://github.com/rabbitc999/EmbedAiChat> |
| 复制自提交 | `bd63c8de27257f5bc163ef8e11fc68402146a6f9`（2026-11-03） |
| 许可证 | AGPL-3.0，全文见 `libs/embed-ai-chat/LICENSE.txt` |
| 是否修改 | 已由 iPage-IMS 修改（2026-11-04）：增加同步消息事件回调与 assistant_reply / assistant_end 工具，保存并输出工具调用期间的助手回复；保留原 send 接口，并新增本轮多模态消息、协作取消入口、模型服务 HTTP 状态异常与宿主工具授权校验，以及 DSML、tool_call 和 assistant_reply 标签的回复正文兼容处理、按轮请求深度思考、分离公开思考事件，以及恢复宿主可信会话归档 |
| 义务 | 强著佐权：分发 + **通过网络提供服务** 都触发源码开放义务 |

#### 这是本仓库唯一的许可证冲突点，必须理解清楚

AGPL-3.0 是本仓库唯一的**强著佐权**（copyleft）许可证。它的关键条款：

1. **第 5 条（组合作品）**：把 AGPL 代码与其他代码链接/合并成一个程序并分发，
   整个组合作品必须整体按 AGPL-3.0 授权。
2. **第 13 条（远程网络交互）**：即使不分发二进制，只要让用户**通过网络与它交互**，
   也必须向这些用户提供完整对应源码。

`libs/embed-ai-chat` 被 `server` 模块编译进同一个 Spring Boot 进程（`AiEngineManager`
直接 `import io.embedai.EmbedAiChat`），属于典型的"组合作品"。因此：

> **现状**：`server/` 与 `web/` 的原创代码按 MIT 授权；
> 但只要发行包里含 `embed-ai-chat`，**整个发行包（jar / 镜像 / 源码包）对外分发时
> 需按 AGPL-3.0 提供完整源码**。根目录 `LICENSE` 中的 MIT 声明无法覆盖这部分。

这不是法律意见，只是对许可证文本的直白解读。真要商用请自行咨询律师。

---

## 二、三条合规路径（按需选择）

### 路径 A：保持现状（内部系统 / 开源项目）

适用：只在公司内网自用，或整个项目本来就打算开源。

做法：什么都不用改。README 与 LICENSE 已明确标注边界。

### 路径 B：纯 MIT、可闭源商用（推荐给产品化场景）

需要把 AGPL 模块从进程内剥离。三种做法，改造成本由低到高：

1. **拆成独立进程**：把 AI 控制台做成单独部署的服务（自己的进程、自己的仓库，
   按 AGPL 单独开源），主应用通过 HTTP 调用它。AGPL 的"组合作品"边界通常在
   进程边界处断开（仍有争议，但比静态链接清晰得多）。
2. **换掉实现**：`AiEngineManager` 对外只暴露 `ready/status/chat/history/reset`
   五个方法，自己按这套接口实现一遍（对接 OpenAI 兼容协议 + 自己写 MCP stdio 客户端），
   代码量约 800～1200 行。
3. **改为运行时加载**：把 `embed-ai-chat` 打成独立 jar，由用户自行下载放入
   `plugins/` 目录并在运行时反射加载；发行包本身不含 AGPL 代码。

### 路径 C：整仓库 AGPL-3.0

适用：本来就计划完全开源、且不介意下游必须开源。

做法：把根 `LICENSE` 换成 AGPL-3.0 全文，删除其中的 MIT 范围说明。

---

## 三、Maven 依赖（运行时）

| 依赖 | 版本 | 许可证 | 备注 |
| --- | --- | --- | --- |
| spring-boot-starter-web / jdbc / test | 3.3.5 | Apache-2.0 | 传递依赖含 Tomcat（Apache-2.0）、Jackson（Apache-2.0） |
| com.h2database:h2 | 随 Boot 管理 | MPL-2.0 或 EPL-1.0（双许可） | 仅开发/演示用，可换库 |
| com.mysql:mysql-connector-j | 随 Boot 管理 | GPL-2.0 + Universal FOSS Exception | 见下方说明 |
| org.postgresql:postgresql | 随 Boot 管理 | BSD-2-Clause | 宽松，无义务 |
| Spring AMQP（spring-boot-starter-amqp） | Spring Boot BOM | Apache-2.0 | 可选 RabbitMQ 事件投递 |
| Spring Data Redis（spring-boot-starter-data-redis） / Lettuce | Spring Boot BOM | Apache-2.0 | 可选 Redis 品牌缓存 |
| RabbitMQ Java Client | Spring Boot BOM | Apache-2.0（上游三许可之一） | Spring AMQP 传递依赖 |
| com.google.code.gson:gson | 2.11.0 | Apache-2.0 | embed-ai-chat 的依赖 |
| org.slf4j:slf4j-api | 2.0.13 | MIT | ipage 的唯一依赖 |

**关于 mysql-connector-j**：它是 GPL-2.0，但附带 Oracle 的 *Universal FOSS Exception*，
允许在开源项目中自由使用；闭源商用则通常需要购买 Oracle 商业许可，或改用
MariaDB Connector/J（LGPL-2.1）。用 PostgreSQL 则完全无此顾虑。

---

## 四、前端依赖（构建期 / 运行时）

| 依赖 | 版本 | 许可证 |
| --- | --- | --- |
| vue | ^3.5.13 | MIT |
| vue-router | ^4.5.0 | MIT |
| markdown-it | ^15.0.3 | MIT |
| @mdit/plugin-katex | ^1.1.5 | MIT |
| @mdit/plugin-tex（数学渲染插件的传递依赖） | — | MIT |
| katex | ^0.19.1 | MIT |
| vite | ^6.0.7 | MIT |
| @vitejs/plugin-vue | ^5.2.1 | MIT |
| esbuild（vite 传递依赖） | — | MIT |
| rollup（vite 传递依赖） | — | MIT |

前端全部为宽松许可证，构建产物可自由分发。

---

## 五、维护规则（给后续 AI / 开发者）

1. **新增内置源码模块**：必须在本文件第一节补一条，写清上游地址、许可证、是否修改。
2. **引入强著佐权依赖（GPL / AGPL / SSPL）**：必须先在 PR 描述里说明理由，
   并更新第二节的合规路径，否则不要合并。
3. **升级依赖大版本**：核对许可证是否变化（近两年 Redis、Elasticsearch、
   Terraform 都改过），变化则同步更新本文件。
4. 本文件是 `README.md`「许可证」章节的唯一事实来源，改这里就够了，不要在别处重复罗列。

## Tabular import/export

Apache Commons CSV and Apache POI are distributed under the Apache License 2.0. See https://www.apache.org/licenses/LICENSE-2.0.

The bundled iPage source includes iPage-IMS changes for typed model queries, validated string lengths in schema generation, and display-template diagnostics.
The bundled iPage also includes bounded multi-hop association queries, JDBC batches, callback-based row iteration, count-free slices, versioned updates, and optional model permission keys.
