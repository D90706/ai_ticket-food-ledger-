================================================================
AI 客服工单系统 · 技术栈锁定清单
最后更新：2026-10-20
状态：已锁定。改动本文件任一项，视为契约变更。
================================================================


----------------------------------------------------------------
【一、锁定清单】
----------------------------------------------------------------

▍运行环境

    操作系统    Windows 11
    JDK         23.0.1（已安装，C:\soft\java）
    Maven       3.9.10（已安装，C:\soft\maven\apache-maven-3.9.10）
    JAVA_HOME   C:\soft\java
    数据库      MySQL 8.x（utf8mb4）
    Node.js     用于前端构建

▍后端

    Spring Boot         3.4.8（已锁定；3.4 起官方支持 Java 23）
    Web                  spring-boot-starter-web（Spring MVC，非 WebFlux）
    持久层              MyBatis-Plus 3.5.7（mybatis-plus-spring-boot3-starter）
    数据库驱动          com.mysql:mysql-connector-j
    JSON                Jackson（随 Boot 自带）
    参数校验            spring-boot-starter-validation（可选）
    接口文档            springdoc-openapi-starter-webmvc-ui
    简化样板            Lombok 1.18.36（JDK 23 要求 >= 1.18.34）
    测试                spring-boot-starter-test（JUnit 5 + MockMvc）

▍前端

    Vue 3（Composition API）
    Vite
    Element Plus（按需引入）
    Pinia
    Axios
    Vue Router 4

    不用 TypeScript（两周项目，类型成本高于收益）
    不用 SSR / Nuxt

▍外部服务

    DeepSeek API         https://api.deepseek.com
                          OpenAI 兼容协议，直接 HttpClient 调用
                          ⚠ 实测请求 deepseek-chat 返回 model=deepseek-flash，
                            该 key 后端可能被网关映射，需确认实际模型

▍存储

    图片               本地 ./uploads/，通过静态资源映射对外
    会话上下文          MySQL（chat_session 表）

▍通信

    普通 HTTP + JSON。**不做 SSE 流式。**
    理由见第六节，这是一次有意的推翻。


----------------------------------------------------------------
【二、后端依赖（pom.xml 片段）】
----------------------------------------------------------------

<properties>
    <java.version>23</java.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        <version>3.5.7</version>
        <!-- 注意 artifactId 必须是 -spring-boot3-，
             用 mybatis-plus-boot-starter 会在 Boot 3 上启动失败 -->
    </dependency>

    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <scope>runtime</scope>
    </dependency>

    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>1.18.36</version>
        <optional>true</optional>
        <!-- JDK 23 必须 >= 1.18.34，低于此版本编译期直接报错 -->
    </dependency>

    <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        <version>2.7.0</version>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>

版本策略：上表版本已实测编译通过（JDK 23 + Boot 3.4.8 + MP 3.5.7
        + Lombok 1.18.36 + springdoc 2.7.0）。
        这几个版本是按本机 Maven 仓库里已有的缓存对齐的，
        目的是减少下载、避免版本冲突；升级前请重新跑一次 mvn test。


----------------------------------------------------------------
【三、前端依赖（package.json 片段）】
----------------------------------------------------------------

{
  "dependencies": {
    "vue": "^3.5.0",
    "vue-router": "^4.4.0",
    "pinia": "^2.2.0",
    "element-plus": "^2.8.0",
    "axios": "^1.7.0"
  },
  "devDependencies": {
    "vite": "^5.4.0",
    "@vitejs/plugin-vue": "^5.1.0",
    "unplugin-auto-import": "^0.18.0",
    "unplugin-vue-components": "^0.27.0"
  }
}

两个配置要点：

    1. Element Plus 按需引入
       用 unplugin-vue-components 的 ElementPlusResolver，
       否则全量引入会让首屏体积膨胀（对课程设计其实无所谓，
       但配置一次很便宜）。

    2. Vite proxy 解决跨域
       server.proxy['/api'] → http://localhost:8080
       这样后端完全不需要配 CORS。
       两周项目里，能不在后端加配置的地方就不要加。


----------------------------------------------------------------
【四、JDK 23 的三个坑（必读）】
----------------------------------------------------------------

▍坑一：Lombok 版本下限

    JDK 23 下 Lombok 必须 >= 1.18.34。
    低版本会在编译期抛：
        java.lang.NoSuchFieldError: Class com.sun.tools.javac.tree.JCTree$JCImport
    这看起来像 Spring 的问题，实际是 Lombok 的字节码适配问题，
    排查方向容易跑偏。

▍坑二：MyBatis-Plus 的 starter 名

    Boot 3 必须用：
        mybatis-plus-spring-boot3-starter
    用：
        mybatis-plus-boot-starter        ← 这是 Boot 2 的
    会在启动阶段失败，且报错信息不直接指向 artifactId。

▍坑三：可能的 jar 版本冲突

    MyBatis-Plus 的 starter 会传入 mybatis / mybatis-spring，
    与 Spring Boot 3.4+ 自带的版本区间不一定吻合。

    出问题时的第一反应命令：

        mvn dependency:tree -Dincludes=org.mybatis:*

    确认 mybatis-spring 是 3.x 而不是 2.x。
    如果是 2.x，显式排除并锁定 3.0.x。

▍兜底方案（重要）

    如果 JDK 23 上确实卡住（启动失败、反射告警、插件不兼容），
    最省事的解法不是逐个排查，而是把项目降到 JDK 21：

        mvn -v 里换不了就装一个 21，
        或者用 Maven toolchain 固定 <release>21</release>

    这个兜底不代表方案有问题，只是把「环境适配」与「业务开发」
    两件事分开——两周项目里，环境适配的时间必须设上限。


----------------------------------------------------------------
【五、明确不引入的东西】
----------------------------------------------------------------

以下不是遗漏，是决定。每一条都有人会问「为什么不加」。

    不引入 Spring AI / LangChain4j
        框架封装掉的部分（重试策略、缺失判定、三维核验、
        动态 prompt 拼装）恰好是本项目要定制的部分。
        见 idea.txt §10.2

    不引入 JSON Schema 校验库（如 networknt）
        我们要校验的不是通用 JSON Schema，
        而是「values 是否符合动态字段定义」。
        自写约 100 行，且能直接产出中文原因句子，
        喂给 quality_detail 展示给人工。
        引库反而要先做「字段定义 → 标准 JSON Schema」的转换，
        再加一层错误信息翻译。

    不引入 Redis
        会话上下文落 MySQL。单机单用户，不需要。

    不引入向量库 / embedding
        知识用全量注入。语料几千字，召回率 100%。

    不引入 MinIO / OSS
        图片存本地 ./uploads/，一个静态资源映射即可。
        注意：image 字段 AI 不处理，图片由人工补。

    不引入消息队列
        出单是同步的，没有异步削峰需求。

    不引入 Docker
        汇报不加分，配置环境要花时间。

    不引入 Spring Security / 登录
        单用户单机演示。

    不引入 WebFlux
        全链路阻塞式（HttpClient 调 LLM 也是阻塞），
        混入响应式会带来两套编程模型。

    不引入 DTO/VO 分层的过度设计
        4 张表、7 个接口，实体直接作为出入参。
        唯一例外：QualityReport 是独立的值对象。


----------------------------------------------------------------
【六、被推翻的决定：不做 SSE 流式】
----------------------------------------------------------------

之前把 SSE 列为必做，是基于「AI 回答商品问题」的形态。
定位收敛为「抽取 + 追问」之后，前提变了：

    实测延迟    16 次真实调用，0.63s ~ 1.30s，平均 0.95s
                （数据来源：spike/raw-01.json）
                等 1 秒不需要流式。

    输出形态    LLM 输出的是结构化 JSON（values/basis/suggestion/summary）。
                把 JSON 的 token 逐个吐给顾客看，既难看又无意义。

    顾客看到的  追问 → FollowUpBuilder 用字段 label 拼的，瞬时产出
                出单 → 「已为您转交人工」，瞬时产出
                这两句都不是模型生成的，没有流式可言。

省下来的东西：

    上游 SSE 解析（读 DeepSeek 的流）
    SseEmitter 的异步超时与线程池配置
    前端 fetch + ReadableStream 手写解析
    流的异常中断处理

替代方案：普通 POST + 前端 loading 态（1 秒以内，一个转圈就够）。

如果将来要恢复流式（例如加入「AI 回答商品问题」的能力），
只需要把 ChatController 的返回改成 SseEmitter，
并在 LlmClient 里加一个流式方法——接口层已经隔离好了。
这也是 LlmClient 值得单独抽象的另一个理由。


----------------------------------------------------------------
【七、启动前的自检清单】
----------------------------------------------------------------

    java -version            → 23.0.1
    mvn -v                   → 3.9.10，且显示 Java version: 23
    mvn dependency:tree      → mybatis-spring 是 3.x
    mvn spring-boot:run      → 能起来
    MySQL 建库               → utf8mb4，四张表建好
    前端 npm run dev         → 能起来且 proxy 到 8080 通
    一次 preview 调用        → 能拿到 values + basis + quality

最后一条是关键：preview 接口通了，
说明 prompt 拼装、LLM 调用、JSON 校验、三维核验
四条链路全都活着。它是最小可用的验收点。


================================================================
End of document
================================================================
