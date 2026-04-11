import { app, BrowserWindow, dialog, ipcMain, protocol } from "electron";
import { createReadStream, promises as fs } from "node:fs";
import path from "node:path";
import { Readable } from "node:stream";
import type { AnalyzedTrack, PushProgressUpdate, ScanProgressUpdate } from "../common/manifest.js";

const DEV_SERVER_URL = "http://127.0.0.1:5173";
const PREVIEW_MEDIA_SCHEME = "runner-media";
const allowedPreviewFolders = new Set<string>();
const allowedExportFolders = new Set<string>();

protocol.registerSchemesAsPrivileged([
  {
    scheme: PREVIEW_MEDIA_SCHEME,
    privileges: {
      standard: true,
      secure: true,
      supportFetchAPI: true,
      stream: true,
      corsEnabled: true,
    },
  },
]);

async function createWindow(): Promise<void> {
  const window = new BrowserWindow({
    width: 1480,
    height: 960,
    minWidth: 1200,
    minHeight: 780,
    backgroundColor: "#0a1620",
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  if (!app.isPackaged) {
    await loadDevServer(window);
  } else {
    await window.loadFile(path.join(__dirname, "../../dist/index.html"));
  }
}

app.whenReady()
  .then(async () => {
    registerPreviewMediaProtocol();
    registerIpc();
    await createWindow();

    app.on("activate", async () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        await createWindow();
      }
    });
  })
  .catch((error) => {
    console.error("Electron 启动失败：", error);
    app.quit();
  });

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});

function registerIpc(): void {
  ipcMain.handle("runner:pick-music-folder", async () => {
    const selectedFolder = await pickFolder("选择 MP3 音乐目录");
    if (!selectedFolder) {
      return null;
    }
    rememberAllowedFolder(allowedPreviewFolders, await resolveCanonicalPath(selectedFolder));
    return selectedFolder;
  });
  ipcMain.handle("runner:pick-export-folder", async () => {
    const selectedFolder = await pickFolder("选择导出目录");
    if (!selectedFolder) {
      return null;
    }
    rememberAllowedFolder(allowedExportFolders, await resolveCanonicalPath(selectedFolder));
    return selectedFolder;
  });
  ipcMain.handle("runner:to-preview-url", async (_event, filePath: string) => {
    const allowedFilePath = await resolveAllowedPreviewFile(filePath);
    return buildPreviewMediaUrl(allowedFilePath);
  });
  ipcMain.handle("runner:list-adb-devices", async () => {
    const { AdbBridge } = await import("./device/adbBridge.js");
    return new AdbBridge().listDevices();
  });
  ipcMain.handle(
    "runner:push-tracks-to-device",
    async (
      event,
      payload: { deviceId: string; libraryName: string; tracks: AnalyzedTrack[] },
    ) => {
      const { AdbBridge } = await import("./device/adbBridge.js");
      return new AdbBridge().pushTracks({
        ...payload,
        onProgress: createPushProgressSender(event),
      });
    },
  );
  ipcMain.handle(
    "runner:push-runner-export-to-device",
    async (
      event,
      payload: { deviceId: string; libraryName: string; tracks: AnalyzedTrack[] },
    ) => {
      const { AdbBridge } = await import("./device/adbBridge.js");
      return new AdbBridge().pushRunnerPlayerExport({
        ...payload,
        onProgress: createPushProgressSender(event),
      });
    },
  );
  ipcMain.handle("runner:list-remote-music", async (_event, payload: { deviceId: string; remotePath: string }) => {
    const { AdbBridge } = await import("./device/adbBridge.js");
    return new AdbBridge().listRemoteEntries(payload.deviceId, payload.remotePath);
  });
  ipcMain.handle("runner:delete-remote-entry", async (_event, payload: { deviceId: string; remotePath: string }) => {
    const { AdbBridge } = await import("./device/adbBridge.js");
    await new AdbBridge().deleteRemoteEntry(payload.deviceId, payload.remotePath);
    return { ok: true };
  });
  ipcMain.handle("runner:scan-folder", async (event, sourceFolder: string) => {
    const [{ FolderScanner }, { AudioAnalyzerWorker }] = await Promise.all([
      import("./audio/folderScanner.js"),
      import("./audio/audioAnalyzerWorker.js"),
    ]);
    const folderScanner = new FolderScanner();
    const audioAnalyzer = new AudioAnalyzerWorker();
    const resolvedSourceFolder = await resolveAllowedSelectedDirectory(
      sourceFolder,
      allowedPreviewFolders,
      "音乐目录",
    );
    const abortController = new AbortController();
    const sendProgress = (progress: ScanProgressUpdate) => {
      if (!event.sender.isDestroyed()) {
        event.sender.send("runner:scan-progress", progress);
      }
    };
    const abortScan = () => abortController.abort();

    event.sender.once("destroyed", abortScan);

    try {
      sendProgress({
        phase: "collecting",
        processed: 0,
        total: 0,
        percent: 0,
        message: "正在读取当前目录中的 MP3 文件...",
      });

      const scanDetails = await folderScanner.scan(
        resolvedSourceFolder,
        sendProgress,
        abortController.signal,
      );
      const tracks = await audioAnalyzer.analyze(
        scanDetails.tracks,
        sendProgress,
        abortController.signal,
      );
      sendProgress({
        phase: "done",
        processed: tracks.length,
        total: tracks.length,
        percent: 100,
        message: `扫描完成，共分析 ${tracks.length} 首歌曲。`,
      });
      rememberAllowedFolder(allowedPreviewFolders, resolvedSourceFolder);
      return {
        libraryName: path.basename(resolvedSourceFolder),
        tracks,
        otherAudioExtensions: scanDetails.otherAudioExtensions,
      };
    } finally {
      event.sender.removeListener("destroyed", abortScan);
    }
  });
  ipcMain.handle(
    "runner:export-library",
    async (
      _event,
      payload: { libraryName: string; outputDirectory: string; tracks: AnalyzedTrack[] },
    ) => {
      const { ExportBuilder } = await import("./export/exportBuilder.js");
      const exportBuilder = new ExportBuilder();
      const outputDirectory = await resolveAllowedSelectedDirectory(
        payload.outputDirectory,
        allowedExportFolders,
        "导出目录",
      );
      validateExportDirectory(outputDirectory);
      return exportBuilder.exportLibrary({
        ...payload,
        outputDirectory,
      });
    },
  );
}

async function pickFolder(title: string): Promise<string | null> {
  const result = await dialog.showOpenDialog({
    title,
    properties: ["openDirectory", "createDirectory"],
  });
  if (result.canceled || result.filePaths.length === 0) {
    return null;
  }
  return result.filePaths[0] ?? null;
}

function registerPreviewMediaProtocol(): void {
  protocol.handle(PREVIEW_MEDIA_SCHEME, async (request) => {
    const requestUrl = new URL(request.url);
    const filePath = requestUrl.searchParams.get("path");

    if (!filePath) {
      return new Response("缺少音频路径。", { status: 400 });
    }

    let safeFilePath: string;
    try {
      safeFilePath = await resolveAllowedPreviewFile(filePath);
    } catch (error) {
      return new Response(
        error instanceof Error ? error.message : "无权访问该试听文件。",
        { status: 403 },
      );
    }

    let fileStats;
    try {
      fileStats = await fs.stat(safeFilePath);
    } catch {
      return new Response("找不到试听文件。", { status: 404 });
    }

    if (!fileStats.isFile()) {
      return new Response("试听路径不是有效文件。", { status: 400 });
    }

    const headers = {
      "Accept-Ranges": "bytes",
      "Cache-Control": "no-store",
      "Content-Type": inferAudioContentType(filePath),
    };

    if (request.method === "HEAD") {
      return new Response(null, {
        status: 200,
        headers: {
          ...headers,
          "Content-Length": String(fileStats.size),
        },
      });
    }

    const requestedRange = request.headers.get("range");
    if (requestedRange) {
      const range = parseRangeHeader(requestedRange, fileStats.size);
      if (!range) {
        return new Response("无效的范围请求。", {
          status: 416,
          headers: {
            ...headers,
            "Content-Range": `bytes */${fileStats.size}`,
          },
        });
      }

      const stream = Readable.toWeb(createReadStream(safeFilePath, range));
      return new Response(stream as BodyInit, {
        status: 206,
        headers: {
          ...headers,
          "Content-Length": String(range.end - range.start + 1),
          "Content-Range": `bytes ${range.start}-${range.end}/${fileStats.size}`,
        },
      });
    }

    const stream = Readable.toWeb(createReadStream(safeFilePath));
    return new Response(stream as BodyInit, {
      status: 200,
      headers: {
        ...headers,
        "Content-Length": String(fileStats.size),
      },
    });
  });
}

function createPushProgressSender(
  event: Electron.IpcMainInvokeEvent,
): (progress: PushProgressUpdate) => void {
  return (progress: PushProgressUpdate) => {
    if (!event.sender.isDestroyed()) {
      event.sender.send("runner:push-progress", progress);
    }
  };
}

function buildPreviewMediaUrl(filePath: string): string {
  return `${PREVIEW_MEDIA_SCHEME}://preview?${new URLSearchParams({ path: filePath }).toString()}`;
}

async function resolveAllowedPreviewFile(filePath: string): Promise<string> {
  const resolvedFilePath = await resolveCanonicalPath(filePath);
  const isAllowed = Array.from(allowedPreviewFolders).some((folderPath) =>
    isPathWithinDirectory(resolvedFilePath, folderPath),
  );

  if (!isAllowed) {
    throw new Error("试听文件不在已授权的音乐目录中。请重新选择并扫描音乐目录。");
  }

  return resolvedFilePath;
}

async function resolveAllowedSelectedDirectory(
  directoryPath: string,
  allowedFolders: Set<string>,
  label: string,
): Promise<string> {
  const resolvedPath = await resolveCanonicalPath(directoryPath);
  if (!allowedFolders.has(normalizePathForComparison(resolvedPath))) {
    throw new Error(`请先通过界面重新选择${label}。`);
  }
  return resolvedPath;
}

async function resolveCanonicalPath(inputPath: string): Promise<string> {
  const resolvedPath = path.resolve(inputPath);
  try {
    return await fs.realpath(resolvedPath);
  } catch {
    return resolvedPath;
  }
}

function rememberAllowedFolder(allowedFolders: Set<string>, directoryPath: string): void {
  allowedFolders.add(normalizePathForComparison(directoryPath));
}

function normalizePathForComparison(inputPath: string): string {
  const normalized = path.normalize(inputPath);
  return process.platform === "win32" ? normalized.toLowerCase() : normalized;
}

function isPathWithinDirectory(candidatePath: string, directoryPath: string): boolean {
  const normalizedCandidate = normalizePathForComparison(candidatePath);
  const normalizedDirectory = normalizePathForComparison(directoryPath);
  const relative = path.relative(normalizedDirectory, normalizedCandidate);
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

function validateExportDirectory(outputDirectory: string): void {
  if (!path.isAbsolute(outputDirectory)) {
    throw new Error("导出目录必须是绝对路径。");
  }
  const rootDirectory = path.parse(outputDirectory).root;
  if (normalizePathForComparison(outputDirectory) === normalizePathForComparison(rootDirectory)) {
    throw new Error("导出目录不能直接使用磁盘根目录，请选择一个具体文件夹。");
  }
}

async function loadDevServer(window: BrowserWindow): Promise<void> {
  let lastError: unknown;
  for (let attempt = 0; attempt < 10; attempt += 1) {
    try {
      await window.loadURL(DEV_SERVER_URL);
      return;
    } catch (error) {
      lastError = error;
      await new Promise((resolve) => setTimeout(resolve, 300));
    }
  }
  throw lastError ?? new Error("无法连接到开发服务器");
}

function inferAudioContentType(filePath: string): string {
  switch (path.extname(filePath).toLowerCase()) {
    case ".mp3":
      return "audio/mpeg";
    case ".m4a":
      return "audio/mp4";
    case ".wav":
      return "audio/wav";
    case ".flac":
      return "audio/flac";
    default:
      return "application/octet-stream";
  }
}

function parseRangeHeader(
  rangeHeader: string,
  totalSize: number,
): { start: number; end: number } | null {
  const match = rangeHeader.match(/^bytes=(\d*)-(\d*)$/i);
  if (!match) {
    return null;
  }

  const startText = match[1] ?? "";
  const endText = match[2] ?? "";

  if (startText === "" && endText === "") {
    return null;
  }

  if (startText === "") {
    const suffixLength = Number.parseInt(endText, 10);
    if (!Number.isFinite(suffixLength) || suffixLength <= 0) {
      return null;
    }
    const start = Math.max(totalSize - suffixLength, 0);
    return {
      start,
      end: totalSize - 1,
    };
  }

  const start = Number.parseInt(startText, 10);
  const requestedEnd = endText === "" ? totalSize - 1 : Number.parseInt(endText, 10);
  if (!Number.isFinite(start) || !Number.isFinite(requestedEnd)) {
    return null;
  }
  if (start < 0 || start >= totalSize || requestedEnd < start) {
    return null;
  }

  return {
    start,
    end: Math.min(requestedEnd, totalSize - 1),
  };
}
