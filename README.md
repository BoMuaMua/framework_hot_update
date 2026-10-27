# 外置配置热更新框架（Spring Boot 3.3.x）

把 `application/` 配置目录放在 **jar 同级目录**（不参与编译），修改其中的 `application.yml`
后自动生效，无需重启。

框架本身被抽成了一个可复用的 starter 模块，示例应用演示怎么用。

---

## 一、项目结构

```
springboot-base-framework示例/
├── pom.xml                                  # 父 POM（packaging=pom）
│
├── external-config-spring-boot-starter/     # ★ 热更新能力模块（可独立复用）
│   ├── pom.xml
│   └── src/main/java/com/example/config/
│       ├── ExternalConfigLocationResolver.java   # 把外置目录挂进 Spring 配置体系
│       ├── HotReloadProperties.java              # 6 个配置项
│       ├── ConfigWatcher.java                    # WatchService + 去抖 + 校验 + 刷新
│       ├── ValueFieldRefresher.java              # @Value 字段回写
│       └── ExternalConfigAutoConfiguration.java  # 自动装配
│
└── app/                                     # 示例应用
    ├── pom.xml
    ├── application/application.yml          # 外置配置（随包发布，可改）
    ├── scripts/run.bat / run.sh             # 启动脚本
    └── src/main/java/com/example/app/       # 启动类 + Demo 控制器
```

---

## 二、快速开始

```bash
mvn clean package
cd app/target/dist
./run.bat            # Windows；Linux 用 ./run.sh
```

打包产物 `app/target/dist/`：

```
dist/
├── app.jar
├── application/application.yml   ← 改这里，改完自动生效
├── run.bat
└── run.sh
```

验证：

```bash
curl http://localhost:8080/demo/config
# {"configurationProperties":{...},"valueAnnotation":{...}}
```

打开 `application/application.yml` 把 `demo.app-name` 改掉并保存，再请求一次，值就变了。

---

## 三、核心原理

### 3.1 外置目录怎么挂进去

`ExternalConfigLocationResolver` 实现 `EnvironmentPostProcessor`，在 Spring Boot
读取配置文件之前执行（`getOrder() = HIGHEST_PRECEDENCE`，比
`ConfigDataEnvironmentPostProcessor` 还早），把 `spring.config.additional-location`
设为 `optional:file:<jar目录>/application/`。

- 目录名默认 `application`，可用 `-Dapp.external-config-dir=...` 覆盖
- `optional:` 前缀保证目录不存在时应用照常启动
- 用户自己显式配置过该属性时，框架不覆盖

### 3.2 热更新链路

```
文件变化
   ↓
WatchService 收到 ENTRY_CREATE / ENTRY_DELETE / ENTRY_MODIFY   ← 只当作「有动静」
   ↓
不立即刷新，安排 debounceMs 后的去抖任务（新事件到来则旧任务作废，代次 generation 递增）
   ↓
去抖到期 → 稳定性采样：每 sampleIntervalMs 采一次全目录 (lastModified, size)
           连续两次完全一致 = 写入结束（最多 maxRetry 次）
   ↓
与「上次已提交的快照」比对：
   ① 元数据完全一致            → 跳过（重复保存）
   ② 元数据变了但内容一字未改  → 跳过（编辑器 touch / 空保存）
   ③ 内容真的变了              → 继续
   ↓
ContextRefresher.refresh() + 回写 @Value 字段
```

**时间戳不是被监视的对象，而是事件之后的校验依据**——这解决了三个实际问题：

| 问题 | 怎么解决 |
|---|---|
| 编辑器双写（先清空再写入） | 两次采样比对，中间态不刷新 |
| 半截文件 | 采样不稳定就不刷新，等稳定 |
| 重复触发（一次保存多个事件） | generation 代次 + 去抖，只执行最后一次 |
| 只 touch 不改内容 | 内容比对，直接跳过 |

### 3.3 控制台会告诉你「哪里被改了」

刷新成功后，控制台会打印两段内容：**生效值差异** 和 **来源文件行号**。

```
检测到 2 项配置变化：
    demo.app-name: 外置配置-初始值  ->  行号验证
    demo.timeout-ms: 3000  ->  6666
  来源文件 application.yml：
    第 6 行 | app-name: 行号验证
    第 8 行 | timeout-ms: 6666
```

实现要点（都在 `ConfigWatcher` 里）：

| 方法 | 作用 |
|---|---|
| `snapshotConfigProperties()` | 刷新前后各取一次「生效配置快照」 |
| `diffProperties()` | 比对两份快照，列出变化的键与新旧值（新增标 `[新增]`、删除标 `[删除]`，最多 30 行） |
| `changedKeys()` | 算出真正变化的键集合 |
| `printSourceLines()` | 拿变化的键反查来源文件与行号 |
| `flattenYamlHits()` | 用缩进栈把 yml 的 `a:\n  b:` 压成 `a.b`，再与变化的键精确比对 |

**取「生效值」必须用 `environment.getProperty(name)`**。若按 PropertySource 顺序逐个覆盖写入，
jar 内低优先级的兜底配置（`classpath:/application.yml`）会盖掉外置配置的高优先级值，
表现为「明明改了却没检测到差异」。这是本项目实际踩过的坑。

### 3.4 为什么要内容比对

单纯靠 `(lastModified, size)` 挡不住「编辑器保存了但内容没变」——
很多编辑器保存时会重写整个文件，mtime 变了、size 不变、内容一字未改。

这里**没有引入 SHA-256**：配置文件通常几 KB，直接比对原始字符串的开销
远小于一次 Spring 上下文刷新（实测刷新 25–45ms）。超过 4MB 的文件放弃比对，
保守按「有变化」处理。

---

## 四、三种读取配置的方式

| 方式 | 热更新 | Bean 是否重建 | 推荐度 |
|---|---|---|---|
| `@ConfigurationProperties` | ✅ 自动生效 | 否 | ★★★ 首选 |
| `@Value` + 本框架的 ValueFieldRefresher | ✅ 自动回写 | 否 | ★★ 兼容老代码 |
| `@Value` + `@RefreshScope` | ✅ 但 Bean 被销毁重建 | 是 | ★ 有状态 Bean 慎用 |

### @Value 的两种方案

**方案 A（框架默认，推荐）** —— 什么都不用加，框架自动回写：

```java
@Component
public class MyService {
    @Value("${demo.timeout-ms}")
    private long timeoutMs;   // 配置改了，这个字段自动变
}
```

原理：`ValueFieldRefresher` 是 `BeanPostProcessor`，Bean 初始化时扫描所有
`@Value` 字段并记下原始表达式；刷新时用新 Environment 重新
`resolvePlaceholders` 再反射写回。Bean 不销毁，内部状态保留。

限制：`static` 字段跳过，`final` 字段打 WARN 后跳过。

**方案 B** —— 想用 Spring Cloud 原生方式，关掉框架的回写：

```yaml
app:
  hot-reload:
    refresh-value-fields: false
```

然后给需要的 Bean 加 `@RefreshScope`。注意：刷新时该 Bean 会被销毁重建，
内部缓存、计数器等状态会丢。

---

## 五、配置项

全部在 `app.hot-reload` 前缀下，6 个：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关，关掉则完全不装配 |
| `debounce-ms` | `300` | 事件去抖窗口，最后一次事件后等这么久再检查 |
| `sample-interval-ms` | `150` | 稳定性采样间隔 |
| `max-retry` | `10` | 最多采样次数，超过则放弃本次刷新 |
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

## 六、诊断端点

示例应用暴露了 `GET /demo/hot-reload/status`：

```json
{
  "running": true,
  "configDir": "...\\app\\target\\dist\\application",
  "fileCount": 1,
  "triggerCount": 3,
  "successCount": 2,
  "failCount": 1,
  "skipCount": 1,
  "lastChangedFiles": ["application.yml"],
  "lastRefreshTime": 1761545829449,
  "lastError": null
}
```

- `triggerCount`：去抖后真正进入检查的次数
- `skipCount`：因内容/元数据未变被跳过的次数
- `failCount`：刷新抛异常的次数（配置非法）

---

## 七、已实测场景

| 场景 | 结果 |
|---|---|
| 修改配置内容 | 25–45ms 生效，`@ConfigurationProperties` 与 `@Value` 同步更新 |
| 只 touch 不改内容 | `skipCount+1`，不刷新 |
| 写 `.tmp` / `.swp` | 完全忽略 |
| 非法 YAML | `failCount+1`，应用存活，旧配置继续用；改回来自动恢复 |
| 新增子目录里的 yml | 监听触发刷新，但**配置不生效**（见限制） |
| 改配置后看控制台 | 打印「检测到 N 项配置变化」+ 每项新旧值 + 来源文件名与行号 |

单元测试 10 个，全绿。

---

## 八、已知限制

1. **不能热更新代码** —— 只处理配置。
2. **新增配置文件不生效** —— Spring Boot 的配置数据在首次加载时确定了文件清单，
   运行期新增的 `extra.yml` 不会被纳入（虽然会被监听到并触发刷新）。
   要加文件，请重启；或者改已有文件。
3. **`server.port` 无效** —— Tomcat 已经起来了，改端口必须重启。
4. **WatchService 不跨文件系统** —— 监听的是本地目录，网络盘可能收不到事件。
5. **中文路径 + GBK 控制台** —— Windows 下 Maven 输出可能乱码，建议 `chcp 65001`。

---

## 九、在别的项目里复用

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>external-config-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

引入即可，无需任何注解——自动装配会完成全部工作。
若不想用，`app.hot-reload.enabled=false` 或排除自动配置。
