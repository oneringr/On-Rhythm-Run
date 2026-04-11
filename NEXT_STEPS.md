# 下一步改进与发展意见

基于对 watch-app 和 pc-app 全部源码的多轮审查，从功能演进、架构改进、用户体验和工程化四个维度提出建议。

---

## 一、功能演进

### [x] 1. 播放列表点选跳转 (推荐优先级：高)

**现状：** 手表端播放列表（`PlaylistTrackAdapter`）仅做展示，无点选交互。README 已将此列为已知限制。

**建议：**
- `PlaylistTrackAdapter` 添加 `onItemClick` 回调
- `PlayerFragment` 长按弹出播放列表后，点击条目发送 `ACTION_PLAY_TRACK_BY_ID` intent
- `AdaptivePlaybackService` 接收后调用 `prepareTrack` 直接跳转

**预估：** watch-app 改 3 个文件，~30 行。这是用户感知最强的功能缺口。

**完成情况：** 已实现点选跳转、关闭弹层、按当前播放队列同步下一首位置。

---

### 2. PC 端多格式音频支持 (推荐优先级：高)

**现状：** `FolderScanner` 只采集 `.mp3`，其他格式（`.flac`、`.m4a`、`.wav`）仅统计扩展名。`ffmpeg` 本身已支持这些格式的解码。

**建议：**
- `collectAudioFiles` 扩展采集范围至 `AUDIO_FILE_EXTENSIONS` 中的常见格式
- 导出时使用 ffmpeg 转码为 MP3（手表端播放器基于 ExoPlayer，也可以直接支持 FLAC/M4A，但 MP3 最通用）
- 或者分两级：扫描时支持多格式，导出时可选"转码为 MP3"或"保留原格式"

**预估：** pc-app 改 2-3 个文件。核心是 `folderScanner.ts` 放开过滤 + `exportBuilder.ts` 增加可选转码步骤。

---

### 3. 心率阈值可配置 (推荐优先级：中)

**现状：** `HeartRateModeClassifier` 硬编码 `threshold = 180`、`stableSamples = 10`。180 BPM 对多数跑者偏高（最大心率因年龄而异）。

**建议：**
- 在 `RunModeFragment` 添加阈值滑块（范围 120-200）
- 通过 `ServiceIntents` 传递到 `AdaptivePlaybackService`
- 持久化到 `SharedPreferences`
- `HeartRateModeClassifier` 改为运行时可更新 threshold

**预估：** watch-app 改 4-5 个文件，~60 行。对不同体能水平的用户意义重大。

---

### [x] 4. PC 端推送进度反馈 (推荐优先级：中)

**现状：** `pushTracks` 和 `pushRunnerPlayerExport` 逐文件串行 push，全程无进度回报。大曲库推送时用户只能看到"推送中..."。

**建议：**
- 类似 `scan-folder` 的模式，通过 `event.sender.send("runner:push-progress", ...)` 逐文件报告
- renderer 展示进度条和当前文件名

**预估：** pc-app 改 3 个文件（`adbBridge.ts`、`main.cts`、`App.tsx`），~40 行。

**完成情况：** 已新增 `runner:push-progress` 事件，PC 端 ADB 推送面板会显示推送百分比、当前文件名和目标类型。

---

### 5. 手表端播放统计 (推荐优先级：低)

**现状：** 无任何播放数据统计。

**建议：**
- 本地 SQLite 或 JSON 文件记录每次跑步的播放模式切换时间线、总时长、平均心率
- 跑步页展示最近一次跑步摘要
- 长远可通过 ADB 或蓝牙同步到 PC 端做可视化

---

## 二、架构改进

### 6. 手表端引入依赖注入 (推荐优先级：中)

**现状：** `AppGraph` 是手动管理的单例容器，`AdaptivePlaybackService` 直接 `new` 所有依赖。

**建议：**
- 引入 Hilt 或保持手动 DI 但让 `AppGraph` 提供所有依赖
- 使 `AdaptivePlaybackService` 可测试（当前完全不可单元测试，只能集成测试）
- 将 `heartRateSensorController`、`queueEngine` 等从 Service 内部创建改为外部注入

**收益：** Service 核心逻辑可单元测试，当前 watch-app 只有 `AdaptiveQueueEngineTest` 和 `ManifestParserTest` 两个测试文件。

---

### 7. PC 端状态管理 (推荐优先级：中)

**现状：** `App.tsx` 有 20+ 个 `useState`，所有业务逻辑集中在一个 875 行的组件中。

**建议：**
- 将状态按领域拆分为自定义 hooks：`useLibraryScan`、`useAdbSync`、`useAudioPreview`、`useRemoteBrowser`
- 每个 hook 封装对应的 state + 事件处理
- `App.tsx` 变为纯布局组件，组合各 hook 的返回值

**示例：**
```tsx
function useLibraryScan() {
  const [tracks, setTracks] = useState<AnalyzedTrack[]>([]);
  const [isScanning, setIsScanning] = useState(false);
  // ... scanLibrary, updateFinalLabel, toggleExclude
  return { tracks, isScanning, scanLibrary, updateFinalLabel, ... };
}
```

**收益：** 各领域逻辑独立可测试，`App.tsx` 从 875 行降至 ~200 行。

---

### 8. 手表端 Service 拆分 (推荐优先级：低)

**现状：** `AdaptivePlaybackService` 672 行，承担播放控制、心率管理、Debug 模式、通知、状态发射等全部职责。

**建议：** 如果功能继续增长，考虑：
- 抽出 `NotificationHelper` 类管理通知构建和更新
- 抽出 `PlaybackStateMachine` 管理模式切换的状态转换逻辑
- Service 仅作为 Android 生命周期入口和事件路由

---

## 三、用户体验

### 9. 手表端跑步中锁屏保活 (推荐优先级：高)

**现状：** 前台 Service 保证播放不被杀死，但手表息屏后用户无法快速操作。

**建议：**
- 添加 `WAKE_LOCK` 权限和 `WakeLock` 管理
- 在自适应模式开启时请求 partial wake lock，确保心率传感器持续工作
- 考虑支持 Wear OS 的 Ambient Mode（如果目标设备支持）

---

### 10. PC 端拖拽导入 (推荐优先级：中)

**现状：** 必须通过系统文件选择器选目录。

**建议：** 支持将文件夹拖拽到窗口区域来设置源目录，降低操作步骤。Electron 的 `webContents` 可通过 `will-navigate` + drop event 实现。

---

### 11. 手表端振动反馈 (推荐优先级：中)

**现状：** 模式切换（舒缓↔激动）仅通过屏幕文字提示。跑步中用户可能不看屏幕。

**建议：**
- 模式切换时通过 `Vibrator` 给出不同振动模式（短振 = 舒缓，长振 = 激动）
- 让用户在跑步时无需看屏幕即可感知模式变化

---

### 12. PC 端批量标签操作 (推荐优先级：低)

**现状：** 修改标签逐首操作。

**建议：**
- 表格支持多选（checkbox 或 shift-click）
- 批量设为"舒缓"/"激动"/"排除"
- 按 BPM 范围批量设标签（如 `BPM >= 150` 全部标为激动）

---

## 四、工程化

### [x] 13. 添加 CI 流水线 (推荐优先级：高)

**现状：** 无 CI/CD 配置。

**建议：**
- GitHub Actions：
  - watch-app：`gradlew testDebugUnitTest` + lint
  - pc-app：`npm test` + TypeScript 编译检查
- PR 合并前自动运行，防止回归

**预估：** 1 个 `.github/workflows/ci.yml` 文件，~40 行。

**完成情况：** 已新增 GitHub Actions 工作流，覆盖 `pc-app` 的测试与构建，以及 `watch-app` 的单测与 lint。

---

### 14. pc-app 端到端测试 (推荐优先级：中)

**现状：** pc-app 只有单元测试（parser、scanner、exporter、audio features）。无渲染层测试。

**建议：**
- Playwright 或 Electron 的 `@playwright/test` 做端到端测试
- 覆盖核心流程：选目录 → 扫描 → 修改标签 → 导出
- Mock IPC 层而非真实文件系统

---

### 15. watch-app 集成测试 (推荐优先级：中)

**现状：** watch-app 只有 2 个测试文件。`AdaptivePlaybackService` 完全无测试覆盖。

**建议：**
- 先做架构改进（建议 6），使 Service 可注入 mock 依赖
- 添加 `AdaptivePlaybackService` 的状态转换测试（模式切换、Debug 开关、队列切换的组合场景）
- 添加 `ManifestRepository` 的集成测试（含真实文件系统读取）

---

### 16. Electron 打包与自动更新 (推荐优先级：低)

**现状：** pc-app 有 `vite build` + `tsc` 但无打包配置（electron-builder / electron-forge）。

**建议：**
- 配置 `electron-builder` 生成 Windows 安装包
- 添加 `electron-updater` 实现 GitHub Releases 自动更新
- 让非开发者用户可以直接安装使用

---

## 五、优先级路线图

| 阶段 | 建议项 | 预估工作量 |
|------|--------|-----------|
| **近期** | 1. 播放列表点选跳转 | ~30 行 |
| | 13. CI 流水线 | ~40 行 |
| | 4. 推送进度反馈 | ~40 行 |
| **中期** | 2. 多格式音频支持 | ~80 行 |
| | 3. 心率阈值可配置 | ~60 行 |
| | 7. PC 端状态管理重构 | ~200 行（重构） |
| | 9. 跑步锁屏保活 | ~30 行 |
| | 11. 振动反馈 | ~20 行 |
| **远期** | 6. 依赖注入 + 15. 集成测试 | ~300 行（重构） |
| | 14. 端到端测试 | ~150 行 |
| | 5. 播放统计 | ~200 行 |
| | 16. Electron 打包 | 配置为主 |

近期 3 项投入最小但用户感知最高，建议优先实施。
