# On Rhythm Run 解耦合分析与改进建议

## 概述

本文档基于对 On Rhythm Run 项目完整代码的审查，识别出当前架构中的耦合问题，并提出分阶段的实际改进建议。项目的**领域逻辑**（音频分析、队列算法、Manifest 解析）质量良好且隔离度高，主要耦合问题集中在**UI 与业务逻辑的分层**和**手表端的依赖管理**上。

---

## 一、PC 端问题分析

### 1.1 God Component：App.tsx（1107 行）

**位置**：`pc-app/src/renderer/App.tsx`

App.tsx 是整个 PC 端唯一的 React 组件，承担了所有职责：

| 职责域 | 涉及的 state 数量 | 行数范围 |
|--------|-------------------|----------|
| 曲库扫描 | 6 个 useState | 14-21 行 |
| ADB 设备同步 | 8 个 useState | 23-31 行 |
| 音频试听 | 7 个 useState + 1 ref | 33-40 行 |
| 派生统计 | 3 个 useMemo | 42-62 行 |
| 事件监听 | 6 个 useEffect | 64-152 行 |
| 业务函数 | ~15 个 async function | 154-527 行 |
| 键盘快捷键 | 1 个 useEffect + useCallback | 528-601 行 |
| JSX 渲染 | ~500 行 | 602-1107 行 |

**问题**：

1. **20+ 个 useState 散落在同一个组件**——曲库状态、设备状态、试听状态相互无关却共享同一个渲染周期，任何一个 `set*` 都会触发整个组件重新渲染。
2. **业务逻辑直接嵌在组件中**——`scanLibrary()`、`pushTracksToDevice()`、`exportLibrary()` 等函数直接操作多个 state 变量，无法脱离 React 单独测试。
3. **键盘快捷键处理跨越多个领域**——第 528-601 行的 `useEffect` 同时处理音频播放（空格/箭头）、标签修改（1/2）、自动前进切换（0），依赖数组包含几乎所有状态。
4. **`applyLabelShortcut()`（480-508 行）混合了三个关注点**——修改曲库标签 + 判断自动前进偏好 + 控制试听导航。

### 1.2 IPC 层缺少业务逻辑中间层

**位置**：`pc-app/src/main/main.cts`

每个 `ipcMain.handle` 直接实例化工具类并链式调用：

```typescript
// main.cts:91-93 — 每次调用都 new 一个 AdbBridge
const { AdbBridge } = await import("./device/adbBridge.js");
return new AdbBridge().listDevices();
```

当前每个 IPC handler 都是独立的、一次性的。这本身不算严重问题，但导致 App.tsx 必须自己编排所有工作流（扫描 → 分析 → 更新状态 → 显示结果），组件承担了"控制器"角色。

---

## 二、手表端问题分析

### 2.1 God Service：AdaptivePlaybackService（720 行）

**位置**：`watch-app/src/main/java/.../player/AdaptivePlaybackService.kt`

这个 Service 同时负责：

| 职责 | 关键方法 | 行数 |
|------|----------|------|
| ExoPlayer 生命周期管理 | `onCreate`, `prepareTrack` | 69-100, 422-450 |
| Intent 路由（12 种 action） | `onStartCommand` | 107-165 |
| 曲库加载 | `loadLibrary` | 180-219 |
| 播放控制 | `togglePlayback`, `playNext`, `playPrevious` | 221-265 |
| 自适应模式切换 + 淡入淡出 | `applyPlaybackMode`, `switchTrackWithFade` | 355-420 |
| Debug 模式管理 | `setDebugModeEnabled`, `toggleDebugPlaybackMode` | 299-353 |
| 通知栏构建与更新 | `buildNotification`, `updateNotification` | 563-625 |
| UI 状态快照发布 | `updatePlaybackSnapshot` | 511-541 |
| 播放进度定时器 | `startPlaybackTicker` | 550-561 |
| 心率传感器回调 | `onStableModeChanged` | 291-297 |

**问题**：

1. **无法单元测试**——Service 在 `onCreate` 中直接 `new` 所有依赖（ExoPlayer、HeartRateSensorController、AdaptiveQueueEngine），需要完整的 Android 生命周期才能运行。
2. **`onStartCommand` 是一个巨型 when/switch**（107-165 行）——12 个分支，每个分支都直接操作 Service 内部状态和 `AppGraph.uiStateStore`。
3. **通知逻辑散布在各处**——几乎每个状态变更方法末尾都调用 `updateNotification()`，通知构建依赖 `currentTrack`、`player.isPlaying`、`currentMode`、`queueMode` 四个不同维度的状态。
4. **心率回调直接写入全局状态**（第 93-96 行）：
   ```kotlin
   onHeartRateSnapshot = { snapshot ->
       if (!debugModeEnabled) {
           AppGraph.uiStateStore.updateHeartRate { snapshot }
       }
   }
   ```
   Service 的 `debugModeEnabled` 标志"穿透"到传感器回调中，创建了隐式依赖。

### 2.2 Singleton DI 容器：AppGraph

**位置**：`watch-app/src/main/java/.../state/AppGraph.kt`（22 行）

```kotlin
object AppGraph {
    lateinit var manifestRepository: ManifestRepository
    val uiStateStore: UiStateStore by lazy { UiStateStore() }
}
```

**问题**：

1. **全局可变单例**——任何类都可以读写 `AppGraph.uiStateStore`，没有接口抽象，无法替换为测试实现。
2. **多个写入者没有明确的所有权**——`AdaptivePlaybackService` 写 playback 状态，`HeartRateSensorController`（通过回调）写心率状态，Fragment 通过 Intent 间接触发写入。没有单一的状态归属。
3. **不可测试**——每个 Fragment 和 Service 都硬依赖 `AppGraph`，单元测试必须初始化整个依赖图。

### 2.3 UiStateStore 中的隐式逻辑

**位置**：`watch-app/src/main/java/.../state/UiStateStore.kt`，第 25-33 行

```kotlin
fun updateHeartRate(transform: (HeartRateSnapshot) -> HeartRateSnapshot) {
    mutableState.update { current ->
        val updatedHeartRate = transform(current.heartRate)
        current.copy(
            heartRate = updatedHeartRate,
            playback = current.playback.copy(playbackMode = updatedHeartRate.mode), // ← 隐式联动
        )
    }
}
```

心率更新**自动覆盖** `playbackMode`，但 Debug 模式下 Service 又会通过 `setPlaybackMode()` 手动覆盖它。两条路径都能改 `playbackMode`，谁最终生效取决于调用时序，容易出现竞态。

### 2.4 Fragment 直接访问全局状态

**位置**：`PlayerFragment.kt`（第 97 行）、`RunModeFragment.kt`

```kotlin
AppGraph.uiStateStore.state.collect { state -> ... }  // PlayerFragment:97
```

Fragment 没有 ViewModel 层，直接从全局 `AppGraph` 读取状态并通过 `ServiceIntents.send()` 发命令。这意味着：
- Fragment 无法在没有 `AppGraph` 的情况下进行 UI 测试
- Fragment 同时依赖 `AppGraph`（读状态）和 `ServiceIntents`（发命令）两个外部协议

### 2.5 硬编码业务常量

| 常量 | 位置 | 当前值 |
|------|------|--------|
| 心率阈值 | `HeartRateModeClassifier.kt` | `180 BPM` |
| 稳定样本数 | `HeartRateModeClassifier.kt` | `10` |
| 远程音乐路径 | `App.tsx:11` | `/sdcard/Music` |
| 通知 Channel ID | `AdaptivePlaybackService.kt:716` | `runner_player_playback` |

这些值无法通过配置调整，修改需要重新编译。

---

## 三、改进建议

### 阶段一：PC 端 App.tsx 拆分（低风险，高回报）

**目标**：将 1107 行的 God Component 拆分为独立的自定义 Hook 和子组件。

#### 3.1 提取自定义 Hook

将 App.tsx 中的状态和逻辑按领域拆分为三个自定义 Hook：

**`useLibraryScan`**——曲库扫描与标签管理

```typescript
// pc-app/src/renderer/hooks/useLibraryScan.ts
export function useLibraryScan() {
  const [sourceFolder, setSourceFolder] = useState("");
  const [tracks, setTracks] = useState<AnalyzedTrack[]>([]);
  const [isScanning, setIsScanning] = useState(false);
  const [scanProgress, setScanProgress] = useState<ScanProgressUpdate | null>(null);
  const [status, setStatus] = useState("请选择一个目录来扫描 MP3 音乐。");
  const [libraryName, setLibraryName] = useState("晨跑歌单");

  // 移入 chooseSourceFolder, scanLibrary, exportLibrary
  // 移入 scan-progress useEffect
  // 移入 applyLabelShortcut, toggleExcluded 等标签操作

  return { sourceFolder, tracks, isScanning, scanProgress, status, libraryName, ... };
}
```

**`useAdbSync`**——设备同步与远程浏览

```typescript
// pc-app/src/renderer/hooks/useAdbSync.ts
export function useAdbSync() {
  const [devices, setDevices] = useState<AdbDevice[]>([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState("");
  const [deviceStatus, setDeviceStatus] = useState("正在等待设备连接。");
  const [isPushingToDevice, setIsPushingToDevice] = useState(false);
  const [remoteMusicListing, setRemoteMusicListing] = useState<RemoteMusicListing | null>(null);
  // ...

  // 移入 refreshAdbDevices, pushTracksToDevice, pushRunnerExportToDevice
  // 移入 push-progress useEffect, loadRemoteMusic

  return { devices, selectedDeviceId, deviceStatus, ... };
}
```

**`useAudioPreview`**——试听播放

```typescript
// pc-app/src/renderer/hooks/useAudioPreview.ts
export function useAudioPreview(audioRef: RefObject<HTMLAudioElement>) {
  const [previewTitle, setPreviewTitle] = useState("尚未选择试听歌曲");
  const [previewDuration, setPreviewDuration] = useState(0);
  const [previewPosition, setPreviewPosition] = useState(0);
  const [isPreviewPlaying, setIsPreviewPlaying] = useState(false);
  // ...

  // 移入 audio element 的 useEffect
  // 移入 startPreview, togglePreview, seekPreview

  return { previewTitle, isPreviewPlaying, previewPosition, ... };
}
```

**重构后的 App.tsx**：

```typescript
export default function App() {
  const audioRef = useRef<HTMLAudioElement>(null);
  const library = useLibraryScan();
  const adb = useAdbSync();
  const preview = useAudioPreview(audioRef);

  // 键盘快捷键——仅做路由分发
  useKeyboardShortcuts({ library, preview });

  return (
    <>
      <LibraryScanPanel {...library} onPreview={preview.startPreview} />
      <AdbSyncPanel {...adb} tracks={library.exportableTracks} />
      <AudioPreviewBar {...preview} audioRef={audioRef} />
    </>
  );
}
```

**收益**：
- App.tsx 从 ~1100 行降至 ~100 行
- 每个 Hook 可独立测试（通过 `renderHook`）
- 各领域状态变更不会触发无关区域重渲染
- 键盘快捷键逻辑集中在一个薄分发层

#### 3.2 拆分 JSX 为子组件

当前 App.tsx 的 JSX（约 500 行）应拆分为独立组件：

| 组件 | 职责 | 预估行数 |
|------|------|----------|
| `LibraryScanPanel` | 目录选择、扫描按钮、进度条、曲目表格 | ~200 行 |
| `TrackRow`（已有 memo） | 单行曲目展示 | ~60 行 |
| `AdbSyncPanel` | 设备选择、推送按钮、远程浏览器 | ~150 行 |
| `RemoteBrowser` | `/sdcard/Music` 文件浏览 | ~80 行 |
| `AudioPreviewBar` | 浮动试听条 | ~60 行 |
| `StatsBar` | 曲库统计信息 | ~30 行 |

---

### 阶段二：手表端 Service 分解（中等风险，高回报）

**目标**：将 720 行的 AdaptivePlaybackService 拆分为多个职责明确的类。

#### 3.3 提取 PlaybackController

将播放控制逻辑从 Service 中独立出来：

```kotlin
// player/PlaybackController.kt
class PlaybackController(
    private val player: ExoPlayer,
    private val queueEngine: AdaptiveQueueEngine,
    private val onStateChanged: (PlaybackEvent) -> Unit,
) {
    fun togglePlayback() { ... }
    fun playNext(autoAdvance: Boolean = false) { ... }
    fun playPrevious() { ... }
    fun playTrackById(trackId: String?) { ... }
    fun prepareTrack(track: LocalTrack?, playImmediately: Boolean) { ... }
}
```

**收益**：播放逻辑可脱离 Service 生命周期进行单元测试。

#### 3.4 提取 NotificationController

通知构建和更新逻辑独立：

```kotlin
// player/NotificationController.kt
class NotificationController(
    private val context: Context,
    private val mediaSession: MediaSessionCompat,
) {
    fun update(state: NotificationState) { ... }
    fun createChannel() { ... }

    data class NotificationState(
        val trackTitle: String?,
        val isPlaying: Boolean,
        val modeLabel: String,
        val queueModeLabel: String,
    )
}
```

**收益**：通知逻辑不再散布在 Service 各方法末尾，只需在状态变更后统一调用一次 `notificationController.update(currentState)`。

#### 3.5 提取 AdaptiveModeManager

自适应模式切换、Debug 模式、淡入淡出逻辑集中管理：

```kotlin
// player/AdaptiveModeManager.kt
class AdaptiveModeManager(
    private val queueEngine: AdaptiveQueueEngine,
    private val sensorController: HeartRateSensorController,
    private val onModeChanged: (PlaybackMode, String) -> Unit,
    private val onFadeSwitch: suspend (LocalTrack?, String) -> Unit,
) {
    var adaptiveEnabled: Boolean
    var debugModeEnabled: Boolean
    var currentMode: PlaybackMode

    fun onStableModeChanged(mode: PlaybackMode) { ... }
    fun toggleDebugMode() { ... }
    fun toggleDebugPlaybackMode() { ... }
}
```

**收益**：
- 心率→模式→播放 的决策链清晰可追踪
- Debug 模式的特殊逻辑不再与播放控制交织
- 可独立测试模式切换的各种边界情况

#### 3.6 重构后的 AdaptivePlaybackService

```kotlin
class AdaptivePlaybackService : Service() {
    private lateinit var playbackController: PlaybackController
    private lateinit var notificationController: NotificationController
    private lateinit var modeManager: AdaptiveModeManager

    override fun onCreate() {
        // 初始化各 Controller，注入依赖
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 仅做 intent 路由，委托给对应的 Controller
        when (intent?.action) {
            ACTION_TOGGLE_PLAY -> playbackController.togglePlayback()
            ACTION_NEXT -> playbackController.playNext()
            ACTION_TOGGLE_ADAPTIVE -> modeManager.toggleAdaptive(...)
            // ...
        }
        return START_STICKY
    }
}
```

Service 从 720 行降至约 100-150 行，只负责生命周期管理和 Intent 路由。

---

### 阶段三：依赖注入改造（中等风险，中等回报）

#### 3.7 为 AppGraph 引入接口抽象

当前 `AppGraph` 是全局可变单例。第一步不需要引入 Hilt/Koin 等框架，只需**引入接口**：

```kotlin
// state/StateStore.kt
interface StateStore {
    val state: StateFlow<AppUiState>
    fun updatePlayback(transform: (PlaybackSnapshot) -> PlaybackSnapshot)
    fun updateHeartRate(transform: (HeartRateSnapshot) -> HeartRateSnapshot)
    fun updateLibrary(summary: LibrarySummary)
    fun setPlaybackMode(mode: PlaybackMode)
    fun setAdaptiveEnabled(enabled: Boolean)
    fun setDebugModeEnabled(enabled: Boolean)
    fun setMessage(message: String)
}

// UiStateStore 实现该接口
class UiStateStore : StateStore { ... }
```

```kotlin
// state/AppGraph.kt — 改为面向接口
object AppGraph {
    lateinit var manifestRepository: ManifestRepository
    val uiStateStore: StateStore by lazy { UiStateStore() }
    // ...
}
```

**收益**：
- 测试时可注入 `FakeStateStore`
- 为将来引入 Hilt/Koin 铺路
- Fragment 和 Service 依赖接口而非实现

#### 3.8 Service 依赖通过构造函数注入

当前 Service 在 `onCreate` 中直接创建所有依赖。改为从 `AppGraph` 获取可替换的实例：

```kotlin
override fun onCreate() {
    super.onCreate()
    val stateStore = AppGraph.uiStateStore
    val repository = AppGraph.manifestRepository
    playbackController = PlaybackController(player, queueEngine, stateStore)
    modeManager = AdaptiveModeManager(queueEngine, heartRateSensorController, stateStore)
    notificationController = NotificationController(this, mediaSession)
}
```

---

### 阶段四：UiStateStore 状态归属澄清（低风险，中等回报）

#### 3.9 消除 updateHeartRate 中的隐式联动

**当前问题**：`updateHeartRate()` 自动将 `heartRate.mode` 同步到 `playback.playbackMode`，但 Debug 模式下 `setPlaybackMode()` 又能覆盖它。

**建议**：让 `updateHeartRate()` 只更新心率数据，模式切换由调用方显式决定：

```kotlin
// 改造前
fun updateHeartRate(transform: (HeartRateSnapshot) -> HeartRateSnapshot) {
    mutableState.update { current ->
        val updatedHeartRate = transform(current.heartRate)
        current.copy(
            heartRate = updatedHeartRate,
            playback = current.playback.copy(playbackMode = updatedHeartRate.mode), // 隐式
        )
    }
}

// 改造后
fun updateHeartRate(transform: (HeartRateSnapshot) -> HeartRateSnapshot) {
    mutableState.update { current ->
        current.copy(heartRate = transform(current.heartRate))
        // playbackMode 不再在这里自动跟随
    }
}
```

模式切换改为在 `AdaptiveModeManager`（或当前的 `onStableModeChanged`）中显式调用 `setPlaybackMode()`。

**收益**：
- 消除"谁最后写入 playbackMode 谁生效"的隐式竞争
- 状态变更路径可追踪：心率 → ModeManager 判断 → 显式设置模式

#### 3.10 明确状态写入权限

建议通过注释或接口拆分，明确各组件对 UiStateStore 的写入权限：

| 状态字段 | 唯一写入者 |
|---------|------------|
| `heartRate` | HeartRateSensorController 回调 |
| `playbackMode` | AdaptiveModeManager |
| `playback`（除 playbackMode） | PlaybackController |
| `librarySummary` | Service.loadLibrary() |
| `isAdaptiveEnabled`, `isDebugModeEnabled` | AdaptiveModeManager |

---

### 阶段五：可配置化（低风险，低回报）

#### 3.11 提取业务常量为配置

```kotlin
// config/PlayerConfig.kt
data class PlayerConfig(
    val heartRateThreshold: Int = 180,
    val stableSampleCount: Int = 10,
    val fadeSteps: Int = 12,
    val fadeStepDelayMs: Long = 50L,
    val tickerIntervalPlayingMs: Long = 300L,
    val tickerIntervalIdleMs: Long = 1000L,
)
```

PC 端类似：

```typescript
// renderer/config.ts
export const REMOTE_MUSIC_ROOT = "/sdcard/Music";
export const DEFAULT_LIBRARY_NAME = "晨跑歌单";
```

**收益**：常量集中管理，未来可通过 SharedPreferences 或配置文件覆盖。

---

## 四、改进优先级汇总

| 优先级 | 改进项 | 预计工作量 | 风险 | 收益 |
|--------|--------|-----------|------|------|
| **P0** | 3.1 — PC 端提取三个自定义 Hook | 1-2 天 | 低 | 高：可测试性、可维护性大幅提升 |
| **P0** | 3.2 — PC 端 JSX 拆分为子组件 | 0.5-1 天 | 低 | 高：渲染性能、代码可读性提升 |
| **P1** | 3.3-3.5 — 手表端 Service 拆分 | 2-3 天 | 中 | 高：可测试性、关注点分离 |
| **P1** | 3.9 — 消除 UiStateStore 隐式联动 | 0.5 天 | 低 | 中：消除潜在竞态 |
| **P2** | 3.7 — AppGraph 引入接口抽象 | 1 天 | 低 | 中：测试便利性 |
| **P2** | 3.8 — Service 依赖注入改造 | 0.5 天 | 低 | 中：配合 Service 拆分 |
| **P2** | 3.10 — 状态写入权限明确化 | 0.5 天 | 低 | 中：防止状态混乱 |
| **P3** | 3.6 — Service 精简为路由层 | 包含在 3.3-3.5 中 | - | - |
| **P3** | 3.11 — 业务常量配置化 | 0.5 天 | 低 | 低：灵活性提升 |

---

## 五、当前架构优点（值得保留）

在分析耦合问题的同时，以下设计决策值得肯定和保留：

1. **底层工具类隔离良好**——`FolderScanner`、`AudioFeatures`、`ExportBuilder`、`AdbBridge` 各自职责单一，依赖注入清晰，均有单元测试覆盖。
2. **preload.cts 的 Context Isolation**——主进程与渲染进程通过 `contextBridge` 严格隔离，API 边界清晰。
3. **AdaptiveQueueEngine 纯逻辑**——队列算法不依赖 Android 框架，已有完整单元测试。
4. **HeartRateModeClassifier 纯逻辑**——分类算法独立于传感器实现，已有单元测试。
5. **RunnerModels 数据类设计干净**——Kotlin data class 不可变，职责清晰。
6. **Manifest 协议定义在 shared/schema**——双端共享 JSON Schema，协议版本化。
7. **ServiceIntents 封装 Intent 常量**——比在各处硬编码 action string 好得多。
8. **StateFlow 驱动 UI 更新**——响应式数据流方向正确，只是需要更清晰的写入归属。

---

## 六、测试覆盖现状与建议

### 当前覆盖情况

| 模块 | 有测试 | 无测试 |
|------|--------|--------|
| PC `folderScanner` | ✅ | |
| PC `audioFeatures` | ✅ | |
| PC `exportBuilder` | ✅ | |
| PC `adbBridge` | ✅ | |
| PC `App.tsx` | | ❌ 无法测试（God Component） |
| PC `main.cts` | | ❌ IPC handlers 无测试 |
| Watch `AdaptiveQueueEngine` | ✅ | |
| Watch `HeartRateModeClassifier` | ✅ | |
| Watch `ManifestRepository` | ✅ | |
| Watch `AdaptivePlaybackService` | | ❌ 720 行零测试 |
| Watch Fragments | | ❌ 无测试 |
| Watch `UiStateStore` | | ❌ 无测试 |

### 解耦后可新增的测试

完成上述重构后，以下测试将变得可行：

- **PC 端**：`useLibraryScan` / `useAdbSync` / `useAudioPreview` 的 `renderHook` 测试
- **手表端**：`PlaybackController` 的播放流程单元测试（mock ExoPlayer）
- **手表端**：`AdaptiveModeManager` 的模式切换边界测试
- **手表端**：`NotificationController` 的状态→通知映射测试
- **手表端**：`UiStateStore` 的状态变更一致性测试

---

## 七、总结

On Rhythm Run 的核心领域逻辑质量良好，耦合问题集中在"表层"——UI 组件和 Android Service 承担了过多职责。改进方向不是引入复杂的架构框架，而是**将已有的好的隔离模式（如 AdaptiveQueueEngine）推广到 UI 层和 Service 层**。

建议按 P0 → P1 → P2 的顺序逐步重构，每个阶段完成后都能独立验证和提交，不需要一次性大改。
