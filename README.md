# external-config-spring-boot-starter

> 把 `application.yml` 放到 **jar 同级目录**，修改保存后自动生效，无需重启。

Spring Boot 3.3.x 的外置配置热更新 starter。配置不再打进 jar 包，而是与 jar 并排放置；
文件内容变化后框架自动重新装载配置并回写运行中的 Bean，进程不重启、Bean 不重建、内部状态不丢失。

---

## 特性

- **零配置接入**：引入依赖即生效，不需要任何注解，也不需要写配置项
- **外置文件**：`application.yml` 与 jar 同级，不参与编译，随时可改
- **保存即生效**：文件落盘后毫秒级刷新，实测 24–44 ms
- **两种读取方式都支持热更新**：`@ConfigurationProperties` 与 `@Value` 字段均可自动更新，Bean 不被销毁
- **抗误触发**：事件去抖 + 写入稳定性采样 + 内容比对，半截文件、重复保存、仅 touch 都不会误刷新
- **变更可追溯**：刷新后打印每个变化键的新旧值，以及它在源文件中的行号
- **运行态可观测**：内置状态接口，输出触发/成功/跳过/失败计数与最近错误

---

## 模块结构

```
framework_hot_update/
├── pom.xml                                   # 聚合 POM（packaging=pom）
├── jitpack.yml                               # JitPack 构建配置
├── mvnw / mvnw.cmd / .mvn/                   # Maven Wrapper（3.9.7）
├── LICENSE                                   # MIT
│
├── external-config-spring-boot-starter/      # ★ 框架本体，可独立复用
│   ├── pom.xml                               # parent 为 spring-boot-starter-parent，可单独发布
│   └── src/main/java/com/example/config/
│       ├── ExternalConfigLocationResolver.java   # EnvironmentPostProcessor：把外置文件挂进配置体系
│       ├── ConfigWatcher.java                    # 文件监听、去抖、稳定性校验、刷新
│       ├── ValueFieldRefresher.java              # @Value 字段回写
│       ├── HotReloadProperties.java              # app.hot-reload.* 配置项
│       └── ExternalConfigAutoConfiguration.java  # 自动装配入口
│
└── app/                                      # 示例应用（不对外发布）
    ├── application.yml                       # 外置配置样例
    ├── scripts/run.bat · run.sh              # 启动脚本
    └── src/main/java/com/example/app/        # 启动类、DemoProperties、DemoController
```

---

## 快速开始

### 1. 引入依赖

仓库已发布到 JitPack，Maven 与 Gradle 均可直接引用：

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

```xml
<dependency>
    <groupId>com.github.BoMuaMua</groupId>
    <artifactId>framework_hot_update</artifactId>
    <version>1.0.2</version>
</dependency>
```

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.BoMuaMua:framework_hot_update:1.0.2' }
```

### 2. 把配置文件放到 jar 旁边

```
your-app/
├── your-app.jar
└── application.yml     ← 外置配置，改这里
```

jar 内部仍可保留一份 `classpath:/application.yml` 作为兜底，外置文件存在时优先生效。

### 3. 启动

```bash
java -jar your-app.jar
```

启动日志：

```
外置配置文件已装配：/opt/app/application.yml -> optional:file:/opt/app/application.yml
外置配置热更新已启动，监听文件 /opt/app/application.yml（397 字节）
外置配置热更新组件已注册并启动，监听文件 /opt/app/application.yml
```

### 4. 验证

修改外置文件中的任意配置项并保存，控制台立即输出：

```
外置配置刷新成功，耗时 44 ms，变更文件 [application.yml]，@Value 字段更新 2 个
检测到 2 项配置变化：
    demo.app-name: 外置配置-初始值  ->  热更新验证成功
    demo.timeout-ms: 3000  ->  9999
  来源文件 application.yml：
    第 6 行 | app-name: 热更新验证成功
    第 8 行 | timeout-ms: 9999
```

---

## 配置加载规则

`ExternalConfigLocationResolver` 在 Spring Boot 读取配置之前执行
（`getOrder() = HIGHEST_PRECEDENCE`，早于 `ConfigDataEnvironmentPostProcessor`），
把 `spring.config.additional-location` 指向外置文件。

**查找顺序**（自上而下，第一个命中的胜出）：

| 顺序 | 来源 | 说明 |
|---|---|---|
| 1 | `-Dapp.external-config-file=<绝对路径>` | 完整指定文件，文件名可自定义 |
| 2 | `-Dapp.external-config-dir=<目录>` | 指定目录，自动拼接 `application.yml` |
| 3 | jar 所在目录 | 默认行为，由 `ApplicationHome` 定位 |
| 4 | 当前工作目录 `user.dir` | IDE 直接运行 main 方法时 |
| 5 | `user.dir` 的一级子目录 | IDE 多模块运行，例如项目根下的 `app/application.yml` |

行为约定：

- location 指向**文件**而非目录，因此 URI 结尾不带 `/`
- 加 `optional:` 前缀，文件不存在时应用照常启动
- 用户已显式配置 `spring.config.additional-location` 时，框架不覆盖
- 启动时文件不存在也没关系，监听已就位，文件一旦出现即自动加载
- Windows 下传路径请用正斜杠或双反斜杠：`-Dapp.external-config-dir=D:/cfg`

---

## 热更新机制

```
文件变化
   ↓
WatchService 收到 ENTRY_CREATE / ENTRY_MODIFY / ENTRY_DELETE
   （事件文件名必须精确等于目标文件，.swp / .tmp / 其他 yml 一律丢弃）
   ↓
去抖：延迟 debounce-ms 再检查；期间再来新事件则旧任务作废（generation 递增）
   ↓
稳定性采样：每 sample-interval-ms 采一次 (lastModified, size)
             连续两次完全一致才算写完（最多 max-retry 次）
   ↓
三级判定
   ① 元数据完全一致            → 跳过（重复保存）
   ② 元数据变了但内容未变      → 跳过（touch / 空保存）
   ③ 内容确实变了              → 继续
   ↓
ContextRefresher.refresh() + 回写 @Value 字段 + 打印变更明细
```

时间戳不作为触发依据，而是事件之后的校验依据，因此下列情况都不会造成错误刷新：

| 场景 | 处理方式 |
|---|---|
| 编辑器先清空再写入，产生多个事件 | generation 代次 + 去抖，只执行最后一次 |
| 读到写了一半的文件 | 采样不稳定就不刷新，等写入结束 |
| 一次保存连发多个事件 | 去抖窗口内合并 |
| 只 touch 不改内容 | 内容比对后跳过 |
| 配置写错导致刷新异常 | 失败不更新已提交快照，保留旧配置继续运行，改回来自动恢复 |

内容比对直接比较原始文本，不计算哈希：配置文件通常只有几 KB，字符串比较的开销
远小于一次上下文刷新。超过 4 MB 的文件放弃比对，保守按「有变化」处理。

---

## 读取配置

| 方式 | 热更新 | Bean 是否重建 | 说明 |
|---|---|---|---|
| `@ConfigurationProperties` | ✅ | 否 | 推荐，类型安全 |
| `@Value` + 内置 `ValueFieldRefresher` | ✅ | 否 | 兼容存量代码，无需加注解 |
| `@Value` + `@RefreshScope` | ✅ | 是 | 会丢失 Bean 内部状态，有状态 Bean 慎用 |

### @Value 字段自动回写

框架默认开启回写，`@Value` 字段无需任何额外注解：

```java
@Component
public class MyService {

    @Value("${demo.timeout-ms}")
    private long timeoutMs;   // 外置配置改动后，该字段自动更新
}
```

实现方式：`ValueFieldRefresher` 作为 `BeanPostProcessor`，在 Bean 初始化完成后扫描
`@Value` 字段，登记「字段 + 原始表达式 + Bean 弱引用」；刷新时用最新的 `Environment`
重新解析占位符并反射回写。Bean 实例不销毁，内部缓存与计数器保留。

约束：

- `static` 字段跳过
- `final` 字段跳过并打印 WARN
- 弱引用登记，Bean 被回收后自动清理登记项

如需改用 Spring Cloud 原生方式，关闭回写并自行添加 `@RefreshScope`：

```yaml
app:
  hot-reload:
    refresh-value-fields: false
```

---

## 配置项

全部位于 `app.hot-reload` 前缀下，均有默认值，不写也能正常工作：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关，`false` 时完全不装配 |
| `debounce-ms` | `300` | 事件去抖窗口，最后一次事件后等待多久再检查 |
| `sample-interval-ms` | `150` | 稳定性采样间隔 |
| `max-retry` | `10` | 最大采样次数，超过则放弃本次刷新 |
| `refresh-value-fields` | `true` | 是否自动回写 `@Value` 字段 |
| `verbose-log` | `false` | 打印每次监听到的事件与跳过原因 |

```yaml
app:
  hot-reload:
    enabled: true
    debounce-ms: 300
    sample-interval-ms: 150
    max-retry: 10
    refresh-value-fields: true
    verbose-log: false
```

---

## 诊断接口

`ConfigWatcher#status()` 返回运行状态，示例应用通过 `GET /demo/hot-reload/status` 暴露：

```json
{
  "running": true,
  "configFile": "D:\\app\\target\\dist\\application.yml",
  "fileExists": true,
  "triggerCount": 4,
  "successCount": 2,
  "failCount": 1,
  "skipCount": 1,
  "lastChangedFiles": ["application.yml"],
  "lastRefreshTime": 1793083138716,
  "lastError": null
}
```

| 字段 | 含义 |
|---|---|
| `running` | 监听线程是否在运行 |
| `configFile` / `fileExists` | 目标文件路径与当前是否存在 |
| `triggerCount` | 去抖后真正进入检查的次数 |
| `successCount` / `failCount` | 刷新成功 / 抛异常的次数 |
| `skipCount` | 因元数据或内容未变被跳过的次数 |
| `lastChangedFiles` | 最近一次刷新涉及的文件 |
| `lastRefreshTime` | 最近一次刷新成功的时间戳（毫秒） |
| `lastError` | 最近一次失败的错误摘要 |

---

## 示例应用

`app` 模块演示完整用法，打包后得到可直接运行的发行目录：

```bash
./mvnw clean package
cd app/target/dist
./run.bat          # Windows
./run.sh           # Linux / macOS
```

```
dist/
├── app.jar
├── application.yml    ← 改这里，保存即生效
├── run.bat
└── run.sh
```

示例端点：

| 端点 | 说明 |
|---|---|
| `GET /demo/config` | 输出 `@ConfigurationProperties` 与 `@Value` 两套当前值，用于对比热更新效果 |
| `GET /demo/hot-reload/status` | 输出热更新运行状态 |

```bash
curl http://localhost:8080/demo/config
# {"configurationProperties":{...},"valueAnnotation":{...}}
```

---

## 已知限制

1. **只处理配置，不处理代码**：修改 Java 代码仍需重启。
2. **只监听一个文件**：默认即 jar 同级的 `application.yml`。profile 文件（`application-dev.yml`）走 Spring Boot 原生机制，本框架不监听。
3. **`server.port` 无法热更新**：Web 容器已启动，改端口必须重启。
4. **WatchService 不跨文件系统**：监听的是本地目录，网络盘可能收不到事件。
5. **不生效的键不会报错**：修改不参与刷新的配置项（如 `server.port`）时，日志会提示「生效配置项无差异」。

---

## 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17+ |
| Spring Boot | 3.3.x |
| Maven | 3.6.3+（仓库自带 Wrapper 3.9.7） |

---

## License

[MIT](LICENSE)
