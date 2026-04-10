# 跑者智能音乐播放器

这是一个为跑者准备的双端项目：

- `watch-app/`：Android 8.1 手表端播放器，目标设备为 OPPO Watch 2 ECG 同类安卓手表。
- `pc-app/`：Windows 桌面打标工具，负责扫描 MP3、分析节奏与能量、人工修正标签并导出。
- `shared/schema/`：共享的 `runner_manifest.json` 协议与示例文件。

## 功能概览

### 手表端

- 从 ` /sdcard/Music/RunnerPlayerExport/ ` 读取完整标签曲库
- 也支持直接读取 ` /sdcard/Music ` 根目录或其子文件夹中的普通 MP3
- 解析 `runner_manifest.json`
- 播放本地 MP3
- 读取光学心率传感器
- 连续 10 个有效样本 `>= 180 BPM` 切到“激动”模式
- 连续 10 个有效样本 `< 180 BPM` 切回“舒缓”模式
- 按标签池自动切歌，并带有淡出淡入

### PC 端

- 扫描文件夹中的 MP3
- 提取 BPM、RMS、频谱质心
- 自动建议 `舒缓 / 激动` 标签
- 支持人工修改最终标签
- 导出手表可直接读取的 `RunnerPlayerExport` 目录

## 运行 PC 端

```powershell
cd pc-app
npm install
npm run dev
```

首次启动时，`dev` 脚本会同时做三件事：

1. 启动 Vite 前端
2. 监听编译 Electron 主进程和 preload 脚本
3. 等待编译输出完成后启动 Electron

如果只想构建：

```powershell
cd pc-app
npm run build
```

如果只想跑测试：

```powershell
cd pc-app
npm test
```

### PC 端启动排错

- 如果 `npm run dev` 正常，直接用它即可。当前脚本会自动清理 `ELECTRON_RUN_AS_NODE`，避免 Electron 被误当成 Node 运行。
- 如果你手动执行 `electron .`，而终端里又设置过 `ELECTRON_RUN_AS_NODE=1`，就可能出现这些现象：
  - `require("electron")` 返回的是 `electron.exe` 路径字符串
  - 主进程里拿不到 `app`
  - `ERR_MODULE_NOT_FOUND`、`Cannot read properties of undefined (reading 'exports')` 之类的启动错误
- 可以先检查：

```powershell
Get-ChildItem Env:ELECTRON_RUN_AS_NODE
```

- 如果确实存在，当前终端里先清掉再手动启动：

```powershell
Remove-Item Env:ELECTRON_RUN_AS_NODE
```

- 如果想永久清理它，可以在“系统环境变量”里删除 `ELECTRON_RUN_AS_NODE`，然后重新打开终端。

## Android 环境配置

当前仓库已经有 Android 工程源码，但没有自带 Gradle Wrapper 和本机 SDK 配置，所以需要先把环境补齐。

### 1. 安装 Android Studio

- 安装最新 Android Studio
- 首次打开时勾选：
  - Android SDK
  - Android SDK Platform-Tools
  - Android SDK Command-line Tools

### 2. 安装必要 SDK

在 Android Studio 中打开：

`File > Settings > Languages & Frameworks > Android SDK`

至少安装这些组件：

- `Android SDK Platform 34`
- `Android SDK Build-Tools 34.x`
- `Android SDK Platform-Tools`
- `Android SDK Command-line Tools (latest)`

### 3. 配置 SDK 路径

推荐把 SDK 放在：

```text
C:\Users\你的用户名\AppData\Local\Android\Sdk
```

然后在仓库根目录新建 `local.properties`：

```properties
sdk.dir=C:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

仓库里附了一个示例文件 [local.properties.example](d:/code/watch-metronome-player/local.properties.example)，可以照着改。

### 4. 配置环境变量（可选但推荐）

Windows 环境变量里新增：

```text
ANDROID_SDK_ROOT=C:\Users\你的用户名\AppData\Local\Android\Sdk
ANDROID_HOME=C:\Users\你的用户名\AppData\Local\Android\Sdk
```

并把下面两个目录加入 `Path`：

```text
C:\Users\你的用户名\AppData\Local\Android\Sdk\platform-tools
C:\Users\你的用户名\AppData\Local\Android\Sdk\cmdline-tools\latest\bin
```

配置完成后，重新打开终端，确认：

```powershell
adb version
sdkmanager --list
```

### 5. 打开手表工程

用 Android Studio 打开仓库根目录：

```text
d:\code\watch-metronome-player
```

第一次同步时如果提示下载 Gradle 或 Android 依赖，允许即可。

### 6. 连接手表调试

如果你已经开启手表的开发者模式和 ADB 调试：

```powershell
adb devices
```

能看到设备后，就可以在 Android Studio 中直接运行 `watch-app`。

## 手表端曲库目录

PC 端导出的目录结构如下：

```text
RunnerPlayerExport/
  runner_manifest.json
  tracks/
    *.mp3
```

把整个 `RunnerPlayerExport` 复制到手表的：

```text
/sdcard/Music/
```

手表端会优先读取：

```text
/sdcard/Music/RunnerPlayerExport/
```

如果没有标签清单，也可以直接把普通 MP3 放到：

```text
/sdcard/Music
/sdcard/Music/任意子文件夹
```

然后在手表端进入“曲库”页点击“重新扫描曲库”即可。

## 说明

- 手表端目前按 Android 8.1 / OPPO Watch 2 ECG 这一类设备适配。
- PC 端目前优先面向 Windows。
- 仓库里包含示例 schema 和 sample manifest，便于联调与测试。
