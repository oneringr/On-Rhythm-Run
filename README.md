# On Rhythm Run / 跑者智能音乐播放器

这是一个为跑者设计的双端项目：

- [watch-app](d:/code/On-Rhythm-Run/watch-app)：Android 8.1 手表端播放器，目标设备为 OPPO Watch 2 ECG 同类安卓手表
- [pc-app](d:/code/On-Rhythm-Run/pc-app)：Windows 桌面标注与同步工具
- [shared/schema](d:/code/On-Rhythm-Run/shared/schema)：`runner_manifest.json` 协议与示例

项目目标很直接：在 PC 端扫描 MP3、分析节奏和能量、标记 `舒缓 / 激动` 标签，再把曲库同步到手表；手表根据心率或 Debug 模式自动切换播放池。

## 当前完成度

### PC 端

- 扫描当前文件夹中的 MP3
- 扫描与分析过程显示进度条
- 提取 BPM、RMS、频谱质心并自动建议标签
- 支持人工改标为 `舒缓 / 激动`
- 支持“删除导出”操作，仅排除导出，不删除本地文件
- 支持中下方浮动试听条、暂停、拖动进度条、定位当前歌曲
- 支持键盘快捷键快速试听和改标
- 导出 `RunnerPlayerExport`，保留原始文件名
- Windows 发布包内置 ADB，即使本机环境没有配置 `adb` 也可直接推送
- 支持通过 ADB 一键推送歌曲或完整曲库
- 支持浏览 `/sdcard/Music` 并做简单文件管理
- 导出完成后会提示用户切换手表到 USB“传输文件”模式，再手动放置文件
- 支持打包为 Windows 便携版发布包
- 渲染层已拆分为 hooks + components，便于维护与后续测试扩展

### 手表端

- 从 `/sdcard/Music/RunnerPlayerExport` 读取完整标签曲库
- 兼容读取 `/sdcard/Music` 根目录或其子目录中的普通 MP3
- 播放本地 MP3
- 支持蓝牙耳机 / 媒体键控制 `播放 / 暂停 / 上一曲 / 下一曲`
- 支持 `随机 / 列表循环 / 单曲循环`
- 支持长按“随机”按钮弹出播放列表
- 支持在播放列表中点选切歌
- 支持播放页环形进度、音量控制、跑马灯标题
- 读取心率传感器并按阈值切换 `舒缓 / 激动`
- 跑步页顶部可直接调整切换心率，默认 `160 BPM`
- 记忆播放模式和切换心率，首次启动默认 `随机播放 + 160 BPM`
- 支持模式切换振动反馈，默认开启，并记忆用户开关
- 支持 Debug 模式，停用传感器后可长按播放键手动切换模式
- 播放服务已拆分为 `PlaybackController / AdaptiveModeManager / NotificationController`

## 仓库结构

```text
On-Rhythm-Run/
  watch-app/              Android 手表应用
  pc-app/                 Electron + React 桌面工具
  shared/
    schema/               runner_manifest.json schema
  gradlew / gradlew.bat   Gradle Wrapper
```

## 架构现状

- `pc-app/renderer`：UI 已拆分为 `hooks + components`，`App.tsx` 只负责组合与快捷键分发
- `pc-app/main`：Electron 主进程继续负责扫描、分析、导出、ADB 与 IPC 边界
- `watch-app/player`：播放、模式切换、通知逻辑已从 Service 中拆分为独立类
- `watch-app/state`：`UiStateStore` 通过 `StateStore` 接口暴露，状态写入归属更清晰
- `shared/schema`：双端继续共享 `runner_manifest.json` 协议

## 最近验证

- PC 端已通过：`npm test`、`npm run build`
- 手表端已通过：`.\gradlew.bat :watch-app:testDebugUnitTest`、`.\gradlew.bat :watch-app:lintDebug`
- 真机 ADB 验证已通过：
  - 设备：`OW20W3`
  - 应用冷启动正常
  - 可读取 `/sdcard/Music/RunnerPlayerExport`
  - 可开始播放并切换到下一首
  - 可正确响应蓝牙耳机媒体键 `播放 / 暂停 / 上一曲 / 下一曲`
  - 未观察到 `AndroidRuntime` 崩溃或明显 `PlaybackException`

## 功能说明

### PC 端工作流

1. 选择一个包含 MP3 的文件夹
2. 点击“扫描 MP3”
3. 等待分析完成并校对标签
4. 可试听任意歌曲，必要时把某首歌标为“不导出”
5. 选择下面两种同步方式之一：
   - 导出到本地目录
   - 用 ADB 直接推送到手表

如果不使用 ADB，PC 端导出完成后会提示把手表切换到 USB“传输文件”模式，再手动将 `RunnerPlayerExport` 放到 `/sdcard/Music/` 下。

### PC 端试听与快捷键

当应用窗口处于前台时，以下快捷键可直接作用于浮动试听条：

- `空格`：播放 / 暂停
- `← / →`：后退 / 前进 5 秒
- `↑ / ↓`：上一首 / 下一首试听
- `1`：把当前试听歌曲改为 `舒缓`
- `2`：把当前试听歌曲改为 `激动`
- `0`：开启 / 关闭“改标签后自动下一曲”

补充说明：

- 这些快捷键在按钮获得焦点时仍然生效
- 为避免干扰输入，聚焦在文本框、下拉框等可编辑控件时不会触发
- 浮动试听条里的“定位当前”按钮可把列表滚动到当前试听歌曲

### 手表端工作流

1. 启动应用
2. 进入“曲库”页点击“重新扫描曲库”
3. 进入“播放”页开始播放
4. 进入“跑步”页查看心率、自适应状态或启用 Debug 模式

## 曲库目录与导入规则

### 推荐目录

PC 端导出的完整曲库目录结构如下：

```text
RunnerPlayerExport/
  runner_manifest.json
  tracks/
    周杰伦 - 晴天.mp3
    Westlife - My Love.mp3
```

手表端优先读取：

```text
/sdcard/Music/RunnerPlayerExport/
```

### 兼容目录

如果没有 `runner_manifest.json`，手表端也会尝试读取：

```text
/sdcard/Music
/sdcard/Music/任意子文件夹
```

这类普通 MP3 目录会被当作“无标签曲库”导入，默认按 `舒缓` 处理。

## 心率与模式切换规则

- 首次启动默认切换心率为 `160 BPM`
- 跑步页顶部可把切换心率按 `5 BPM` 步进上下调整
- 连续 10 个有效样本 `>= 当前切换心率` 切到 `激动`
- 连续 10 个有效样本 `< 当前切换心率` 切回 `舒缓`
- 播放模式和切换心率会在本地记忆，后续启动自动恢复
- `NO_CONTACT`、`UNRELIABLE` 或无样本时保持当前模式

Debug 模式开启后：

- 停止使用传感器数据
- 长按播放页主播放按钮可以手动切换 `舒缓 / 激动`

## Manifest 协议

共享 schema 位于：

- [runner_manifest.schema.json](d:/code/On-Rhythm-Run/shared/schema/runner_manifest.schema.json)

当前 `schemaVersion = 1`。标签字段推荐使用新结构：

```json
{
  "labels": {
    "suggested": "calm",
    "final": "excited"
  }
}
```

为了兼容旧导出格式，手表端也仍然接受：

```json
{
  "suggestedLabel": "calm",
  "finalLabel": "excited"
}
```

## 运行 PC 端

### 安装依赖

```powershell
cd pc-app
npm install
```

### 开发模式

```powershell
cd pc-app
npm run dev
```

`dev` 会同时完成三件事：

1. 启动 Vite 前端
2. 监听编译 Electron 主进程和 preload
3. 等待前端和主进程就绪后启动 Electron

### 构建

```powershell
cd pc-app
npm run build
```

### 生成 Windows 发布包

```powershell
cd pc-app
npm run release:win
```

生成结果默认位于：

```text
pc-app/release/
```

通常会包含：

- `OnRhythmRun-PC-1.0.5-x64.exe`：Windows 便携版
- `OnRhythmRun-PC-1.0.5-x64.zip`：压缩分发包

### 测试

```powershell
cd pc-app
npm test
```

## Android 环境配置

### 基础要求

- Android Studio
- Android SDK Platform 34
- Android SDK Build-Tools 34.x
- Android Platform-Tools
- Android Command-line Tools
- JDK 17

### 配置 `local.properties`

仓库根目录新建 `local.properties`：

```properties
sdk.dir=C:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
watch.signing.storeFile=D\\:/path/to/sign-key.jks
watch.signing.configFile=D\\:/path/to/config.txt
```

示例文件见 [local.properties.example](d:/code/On-Rhythm-Run/local.properties.example)。

其中：

- `watch.signing.storeFile` 指向本机 keystore 文件
- `watch.signing.configFile` 指向单独保存签名口令和 alias 的配置文件
- Windows 下请按 `.properties` 规范转义盘符冒号，例如 `D\\:/path/to/file`

`config.txt` 推荐格式如下：

```properties
storePassword=你的仓库密码
keyAlias=你的别名
keyPassword=你的密钥密码
```

这样做的好处是：

- `local.properties` 只保存本机路径
- 真实口令不进入仓库
- Android Studio 和 `gradlew` 都能直接复用同一套签名配置

### 可选环境变量

```text
ANDROID_SDK_ROOT=C:\Users\你的用户名\AppData\Local\Android\Sdk
ANDROID_HOME=C:\Users\你的用户名\AppData\Local\Android\Sdk
```

并把以下路径加入 `Path`：

```text
C:\Users\你的用户名\AppData\Local\Android\Sdk\platform-tools
C:\Users\你的用户名\AppData\Local\Android\Sdk\cmdline-tools\latest\bin
```

### 验证环境

```powershell
adb version
sdkmanager --list
java -version
```

## 构建与安装手表端

### 编译

```powershell
.\gradlew.bat :watch-app:assembleDebug
```

如果本地已经配置好签名信息，也可以直接生成正式签名包：

```powershell
.\gradlew.bat :watch-app:assembleRelease
```

Gradle 会自动读取 `local.properties` 中的 keystore 路径，再从 `config.txt` 读取 `storePassword / keyAlias / keyPassword`。

正式签名 APK 默认输出到：

```text
watch-app/build/outputs/apk/release/watch-app-release.apk
```

### 安装到已连接设备

```powershell
.\gradlew.bat :watch-app:installDebug
```

### 运行单元测试

```powershell
.\gradlew.bat :watch-app:testDebugUnitTest
```

### 检查设备连接

```powershell
adb devices
```

## ADB 同步说明

PC 端已经提供图形化 ADB 同步面板，通常不需要手敲命令。

Windows 发布包会优先使用随包内置的 `adb`，因此即使本机没有额外配置 Android Platform-Tools，也可以直接使用图形化同步。

当前支持两种推送：

- 推送歌曲到 `/sdcard/Music/<曲库名>/`
- 推送完整标签曲库到 `/sdcard/Music/RunnerPlayerExport/`

同时支持：

- 刷新 ADB 设备
- 浏览 `/sdcard/Music`
- 统计当前目录和递归目录中的 MP3 数量
- 删除 `/sdcard/Music` 下的文件或文件夹
- 在长路径目录下保持“上一级 / 刷新”按钮稳定单行显示

如果当前不打算开启 ADB 调试，也可以先把完整曲库导出到本地，再根据应用提示把手表切换到 USB“传输文件”模式后手动放置文件。

## 手表界面说明

### 播放页

- 顶部品牌区：`On Rhythm Run`
- 右上角：当前模式、BPM、BPM 进度条
- 中间：歌曲标题跑马灯
- 中部控制：上一首 / 播放暂停 / 下一首
- 下方控制：音量、播放模式
- 长按“随机 / 循环 / 单曲”按钮：打开播放列表
- 播放列表右上角带关闭按钮，可直接收起

### 跑步页

- 顶部：切换心率调节区
- `自适应` 按钮
- `Debug` 按钮
- `振动反馈` 按钮
- 当前心率与传感器状态
- `舒缓 -> 激动` 时快速震动 3 次，`激动 -> 舒缓` 时慢速震动 2 次
- 振动反馈默认开启，并记忆用户选择
- Debug 开启后，长按播放页主按钮可手动切换 `舒缓 / 激动`

### 曲库页

- 曲库名称
- 歌曲总数
- `舒缓 / 激动` 数量
- 最近导入时间
- 重新扫描按钮

## 常见问题

### 1. PC 端点击扫描没有结果

- 目前 PC 端只扫描当前文件夹中的 `.mp3`
- 不扫描子文件夹
- 不会分析 `.flac`、`.m4a`

### 2. Electron 无法启动

如果你手动执行 `electron .`，又设置过 `ELECTRON_RUN_AS_NODE=1`，Electron 可能会被误当成 Node 运行。

可以先检查：

```powershell
Get-ChildItem Env:ELECTRON_RUN_AS_NODE
```

如果存在：

```powershell
Remove-Item Env:ELECTRON_RUN_AS_NODE
```

正常情况下直接运行 `npm run dev` 即可，仓库已经对这个问题做了兼容处理。

### 3. 手表端扫不到曲库

请确认曲库位于以下路径之一：

```text
/sdcard/Music/RunnerPlayerExport
/sdcard/Music
/sdcard/Music/任意子目录
```

并且至少有一个 `.mp3` 文件。

### 4. 手表端没有心率数据

请确认：

- 应用已经授予 `BODY_SENSORS`
- 设备本身有心率传感器
- 手表佩戴正常，传感器接触稳定

## 已知限制

- PC 端当前只支持 Windows
- PC 端当前只扫描 MP3，不支持 FLAC / M4A
- PC 端扫描当前文件夹，不递归子文件夹
- 手表端当前针对 Android 8.1 / OPPO Watch 2 ECG 同类设备适配
- Windows 便携版当前未做代码签名，首次运行可能触发系统安全提示
- Debug 模式用于联调，不建议长期开启

## 收尾说明

当前仓库已经具备完整的开发、构建、安装、联调闭环：

- PC 端可扫描、分析、试听、导出、ADB 同步
- PC 端可打包为 Windows 便携发布包
- 手表端可导入、播放、切换模式、读取心率、Debug 联调、播放列表点选切歌
- 根目录已包含 Gradle Wrapper，可直接用 `gradlew` 构建

如果后续还要继续演进，比较自然的下一步会是：

1. PC 端增加多格式音频支持
2. 完整导出后手表端自动重扫曲库
3. 手表端增加更稳定的后台保活与跑步场景锁屏体验

## 后续计划

- ADB 断点续传或失败重试
- PC 端读取手表上的 `runner_manifest.json` 做回读校验
- 进一步统一主题色和按钮视觉比例
- 持续优化 `舒缓 / 激动` 识别策略

## 工程备注

- 当前 GitHub Release 中的 PC 包已包含打包白屏和 `ffmpeg.exe ENOENT` 修复
- `v1.0.5` 增加了 Windows 包内置 ADB、导出后的 USB“传输文件”提示，以及手表端模式切换振动反馈开关与记忆
- `v1.0.4` 调整了跑步页布局，把切换心率放到最上方，并为播放模式与切换心率增加了记忆功能
- 手表端 `release` 已接入 Gradle 签名配置，但 keystore 与口令文件仍只保存在本机
- 这份仓库目前以 Windows + Android Studio 开发环境为主，其他平台尚未做发布验证
- 当前发布版本为 `v1.0.5`
