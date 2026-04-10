import { app, BrowserWindow, dialog, ipcMain, net, protocol } from "electron";
import { createReadStream, promises as fs } from "node:fs";
import path from "node:path";
import { Readable } from "node:stream";
import type { AnalyzedTrack, ScanProgressUpdate } from "../common/manifest.js";

const DEV_SERVER_URL = "http://127.0.0.1:5173";
const PREVIEW_MEDIA_SCHEME = "runner-media";

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
  ipcMain.handle("runner:pick-music-folder", async () => pickFolder("选择 MP3 音乐目录"));
  ipcMain.handle("runner:pick-export-folder", async () => pickFolder("选择导出目录"));
  ipcMain.handle("runner:to-preview-url", async (_event, filePath: string) =>
    buildPreviewMediaUrl(filePath),
  );
  ipcMain.handle("runner:list-adb-devices", async () => {
    const { AdbBridge } = await import("./device/adbBridge.js");
    return new AdbBridge().listDevices();
  });
  ipcMain.handle(
    "runner:push-tracks-to-device",
    async (
      _event,
      payload: { deviceId: string; libraryName: string; tracks: AnalyzedTrack[] },
    ) => {
      const { AdbBridge } = await import("./device/adbBridge.js");
      return new AdbBridge().pushTracks(payload);
    },
  );
  ipcMain.handle(
    "runner:push-runner-export-to-device",
    async (
      _event,
      payload: { deviceId: string; libraryName: string; tracks: AnalyzedTrack[] },
    ) => {
      const { AdbBridge } = await import("./device/adbBridge.js");
      return new AdbBridge().pushRunnerPlayerExport(payload);
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
    const sendProgress = (progress: ScanProgressUpdate) => {
      if (!event.sender.isDestroyed()) {
        event.sender.send("runner:scan-progress", progress);
      }
    };

    sendProgress({
      phase: "collecting",
      processed: 0,
      total: 0,
      percent: 0,
      message: "正在读取当前目录中的 MP3 文件...",
    });

    const scanDetails = await folderScanner.scan(sourceFolder, sendProgress);
    const tracks = await audioAnalyzer.analyze(scanDetails.tracks, sendProgress);
    sendProgress({
      phase: "done",
      processed: tracks.length,
      total: tracks.length,
      percent: 100,
      message: `扫描完成，共分析 ${tracks.length} 首歌曲。`,
    });
    return {
      libraryName: path.basename(sourceFolder),
      tracks,
      otherAudioExtensions: scanDetails.otherAudioExtensions,
    };
  });
  ipcMain.handle(
    "runner:export-library",
    async (
      _event,
      payload: { libraryName: string; outputDirectory: string; tracks: AnalyzedTrack[] },
    ) => {
      const { ExportBuilder } = await import("./export/exportBuilder.js");
      const exportBuilder = new ExportBuilder();
      return exportBuilder.exportLibrary(payload);
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

    let fileStats;
    try {
      fileStats = await fs.stat(filePath);
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

      const stream = Readable.toWeb(createReadStream(filePath, range));
      return new Response(stream as BodyInit, {
        status: 206,
        headers: {
          ...headers,
          "Content-Length": String(range.end - range.start + 1),
          "Content-Range": `bytes ${range.start}-${range.end}/${fileStats.size}`,
        },
      });
    }

    const stream = Readable.toWeb(createReadStream(filePath));
    return new Response(stream as BodyInit, {
      status: 200,
      headers: {
        ...headers,
        "Content-Length": String(fileStats.size),
      },
    });
  });
}

function buildPreviewMediaUrl(filePath: string): string {
  return `${PREVIEW_MEDIA_SCHEME}://preview?${new URLSearchParams({ path: filePath }).toString()}`;
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
