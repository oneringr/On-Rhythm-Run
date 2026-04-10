import { promises as fs } from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import type {
  AdbDevice,
  AdbExportLibraryResult,
  AdbPushResult,
  AnalyzedTrack,
  RemoteMusicEntry,
  RemoteMusicListing,
} from "../../common/manifest.js";
import { makeRelativeTrackPath } from "../../common/manifest.js";
import { ExportBuilder } from "../export/exportBuilder.js";

const DEFAULT_REMOTE_MUSIC_ROOT = "/sdcard/Music";
const LEGACY_WATCH_APP_LIBRARY_ROOT = "/sdcard/Android/data/com.runner.smartplayer.watch/files/library";
const LEGACY_WATCH_APP_EXPORT_ROOT = `${LEGACY_WATCH_APP_LIBRARY_ROOT}/RunnerPlayerExport`;
const REMOTE_RUNNER_EXPORT_ROOT = path.posix.join(DEFAULT_REMOTE_MUSIC_ROOT, "RunnerPlayerExport");
const moduleDirectory = path.dirname(fileURLToPath(import.meta.url));

export class AdbBridge {
  async listDevices(): Promise<AdbDevice[]> {
    const result = await runAdb(["devices", "-l"]);
    return parseAdbDevices(result.stdout);
  }

  async pushTracks(params: {
    deviceId: string;
    libraryName: string;
    tracks: AnalyzedTrack[];
  }): Promise<AdbPushResult> {
    const tracksToPush = params.tracks.filter((track) => !track.excludedFromExport);
    if (tracksToPush.length === 0) {
      throw new Error("当前没有可推送到设备的歌曲。");
    }

    const remoteDirectory = path.posix.join(
      DEFAULT_REMOTE_MUSIC_ROOT,
      sanitizeRemoteDirectoryName(params.libraryName.trim() || "RunnerSmartPlayer"),
    );

    await runAdb([
      "-s",
      params.deviceId,
      "shell",
      `mkdir -p ${quoteForShell(remoteDirectory)}`,
    ]);

    const stagingRoot = await fs.mkdtemp(path.join(os.tmpdir(), "runner-adb-push-"));
    const stagedFiles = await stageTracksForPush(stagingRoot, tracksToPush);

    try {
      for (const stagedFile of stagedFiles) {
        const remoteFilePath = path.posix.join(remoteDirectory, path.basename(stagedFile));
        await runAdb(["-s", params.deviceId, "push", stagedFile, remoteFilePath]);
      }
    } finally {
      await fs.rm(stagingRoot, { recursive: true, force: true });
    }

    return {
      deviceId: params.deviceId,
      remotePath: remoteDirectory,
      pushedCount: stagedFiles.length,
    };
  }

  async pushRunnerPlayerExport(params: {
    deviceId: string;
    libraryName: string;
    tracks: AnalyzedTrack[];
  }): Promise<AdbExportLibraryResult> {
    const tracksToPush = params.tracks.filter((track) => !track.excludedFromExport);
    if (tracksToPush.length === 0) {
      throw new Error("当前没有可推送的歌曲和标签，请先恢复至少一首歌曲。");
    }

    const stagingRoot = await fs.mkdtemp(path.join(os.tmpdir(), "runner-adb-export-"));

    try {
      const exportResult = await new ExportBuilder().exportLibrary({
        libraryName: params.libraryName,
        outputDirectory: stagingRoot,
        tracks: params.tracks,
      });

      await runAdb([
        "-s",
        params.deviceId,
        "shell",
        `rm -rf ${quoteForShell(REMOTE_RUNNER_EXPORT_ROOT)}`,
      ]);
      await runAdb([
        "-s",
        params.deviceId,
        "shell",
        `mkdir -p ${quoteForShell(path.posix.join(REMOTE_RUNNER_EXPORT_ROOT, "tracks"))}`,
      ]);

      const remoteManifestPath = path.posix.join(REMOTE_RUNNER_EXPORT_ROOT, "runner_manifest.json");
      await runAdb(["-s", params.deviceId, "push", exportResult.manifestPath, remoteManifestPath]);

      for (const track of tracksToPush) {
        const relativeTrackPath = makeRelativeTrackPath(track.sourceFileName);
        const localTrackPath = path.join(exportResult.outputRoot, relativeTrackPath);
        const remoteTrackPath = path.posix.join(
          REMOTE_RUNNER_EXPORT_ROOT,
          relativeTrackPath.replace(/\\/g, "/"),
        );
        await runAdb(["-s", params.deviceId, "push", localTrackPath, remoteTrackPath]);
      }

      await runAdb([
        "-s",
        params.deviceId,
        "shell",
        `rm -rf ${quoteForShell(LEGACY_WATCH_APP_EXPORT_ROOT)}`,
      ]);

      return {
        deviceId: params.deviceId,
        remotePath: REMOTE_RUNNER_EXPORT_ROOT,
        manifestPath: remoteManifestPath,
        trackCount: tracksToPush.length,
      };
    } finally {
      await fs.rm(stagingRoot, { recursive: true, force: true });
    }
  }

  async listRemoteEntries(deviceId: string, remotePath: string = DEFAULT_REMOTE_MUSIC_ROOT): Promise<RemoteMusicListing> {
    ensureMusicPath(remotePath);

    await runAdb(["-s", deviceId, "shell", `mkdir -p ${quoteForShell(remotePath)}`]);
    const [result, recursiveMp3Count] = await Promise.all([
      runAdb([
        "-s",
        deviceId,
        "shell",
        `ls -a -1 -p ${quoteForShell(remotePath)}`,
      ]),
      countRemoteMp3Files(deviceId, remotePath),
    ]);
    const entries = parseRemoteEntries(result.stdout, remotePath);

    return {
      currentPath: remotePath,
      parentPath: remotePath === DEFAULT_REMOTE_MUSIC_ROOT ? null : path.posix.dirname(remotePath),
      entries,
      stats: {
        directoryCount: entries.filter((entry) => entry.isDirectory).length,
        fileCount: entries.filter((entry) => !entry.isDirectory).length,
        mp3Count: entries.filter((entry) => !entry.isDirectory && isMp3File(entry.name)).length,
        recursiveMp3Count,
      },
    };
  }

  async deleteRemoteEntry(deviceId: string, remotePath: string): Promise<void> {
    ensureMusicPath(remotePath);
    await runAdb(["-s", deviceId, "shell", `rm -rf ${quoteForShell(remotePath)}`]);
  }
}

async function stageTracksForPush(stagingRoot: string, tracks: AnalyzedTrack[]): Promise<string[]> {
  const stagedFiles: string[] = [];

  for (const track of tracks) {
    const stagedFile = path.join(stagingRoot, track.sourceFileName);
    await fs.copyFile(track.sourcePath, stagedFile);
    stagedFiles.push(stagedFile);
  }

  return stagedFiles;
}

async function runAdb(args: string[]): Promise<{ stdout: string; stderr: string }> {
  const adbExecutable = await resolveAdbExecutable();

  return new Promise((resolve, reject) => {
    const child = spawn(adbExecutable, args, {
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
    });

    const stdoutChunks: Buffer[] = [];
    const stderrChunks: Buffer[] = [];

    child.stdout.on("data", (chunk: Buffer | string) => {
      stdoutChunks.push(Buffer.from(chunk));
    });
    child.stderr.on("data", (chunk: Buffer | string) => {
      stderrChunks.push(Buffer.from(chunk));
    });
    child.on("error", reject);
    child.on("close", (code) => {
      const stdout = Buffer.concat(stdoutChunks).toString("utf8");
      const stderr = Buffer.concat(stderrChunks).toString("utf8");
      if (code === 0) {
        resolve({ stdout, stderr });
        return;
      }
      reject(new Error(stderr.trim() || stdout.trim() || `adb exited with code ${code}`));
    });
  });
}

async function resolveAdbExecutable(): Promise<string> {
  const candidates = new Set<string>();
  const envSdkPaths = [process.env.ANDROID_SDK_ROOT, process.env.ANDROID_HOME].filter(Boolean) as string[];
  for (const sdkPath of envSdkPaths) {
    candidates.add(path.join(sdkPath, "platform-tools", adbFileName()));
  }

  const repoLocalPropertiesSdk = await readSdkFromLocalProperties();
  if (repoLocalPropertiesSdk) {
    candidates.add(path.join(repoLocalPropertiesSdk, "platform-tools", adbFileName()));
  }

  if (process.platform === "win32") {
    candidates.add(path.join(process.env.LOCALAPPDATA ?? path.join(os.homedir(), "AppData", "Local"), "Android", "Sdk", "platform-tools", "adb.exe"));
  } else {
    candidates.add(path.join(os.homedir(), "Android", "Sdk", "platform-tools", adbFileName()));
  }

  for (const candidate of candidates) {
    if (!candidate) continue;
    try {
      await fs.access(candidate);
      return candidate;
    } catch {
      // Continue searching.
    }
  }

  return adbFileName();
}

function adbFileName(): string {
  return process.platform === "win32" ? "adb.exe" : "adb";
}

async function readSdkFromLocalProperties(): Promise<string | null> {
  const repoRoot = path.resolve(moduleDirectory, "../../../");
  const localPropertiesPath = path.join(repoRoot, "local.properties");

  try {
    const contents = await fs.readFile(localPropertiesPath, "utf8");
    const match = contents.match(/^sdk\.dir=(.+)$/m);
    if (!match?.[1]) {
      return null;
    }
    return match[1].trim().replace(/\\\\/g, "\\").replace(/\\:/g, ":");
  } catch {
    return null;
  }
}

function sanitizeRemoteDirectoryName(input: string): string {
  return input.replace(/[<>:"/\\|?*\u0000-\u001f]/g, "_").trim() || "RunnerSmartPlayer";
}

function quoteForShell(input: string): string {
  return `'${input.replace(/'/g, `'\\''`)}'`;
}

function ensureMusicPath(remotePath: string): void {
  if (!remotePath.startsWith(DEFAULT_REMOTE_MUSIC_ROOT)) {
    throw new Error("仅允许管理 /sdcard/Music 目录中的文件。");
  }
}

async function countRemoteMp3Files(deviceId: string, remotePath: string): Promise<number> {
  try {
    const result = await runAdb([
      "-s",
      deviceId,
      "shell",
      `find ${quoteForShell(remotePath)} -type f -iname '*.mp3' | wc -l`,
    ]);
    return parseCountResult(result.stdout);
  } catch {
    return 0;
  }
}

function parseCountResult(rawOutput: string): number {
  const match = rawOutput.trim().match(/(\d+)/);
  return match ? Number.parseInt(match[1] ?? "0", 10) : 0;
}

function isMp3File(fileName: string): boolean {
  return fileName.toLowerCase().endsWith(".mp3");
}

export function parseAdbDevices(rawOutput: string): AdbDevice[] {
  return rawOutput
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("List of devices attached"))
    .map((line) => {
      const parts = line.split(/\s+/);
      const id = parts[0] ?? "";
      const state = parts[1] ?? "unknown";
      const metadata = new Map(
        parts.slice(2).map((part) => {
          const separatorIndex = part.indexOf(":");
          if (separatorIndex === -1) {
            return [part, ""];
          }
          return [part.slice(0, separatorIndex), part.slice(separatorIndex + 1)];
        }),
      );
      return {
        id,
        state,
        model: metadata.get("model"),
        product: metadata.get("product"),
        deviceName: metadata.get("device"),
      } satisfies AdbDevice;
    })
    .filter((device) => Boolean(device.id));
}

export function parseRemoteEntries(rawOutput: string, remotePath: string): RemoteMusicEntry[] {
  return rawOutput
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line)
    .map((line) => {
      const isDirectory = line.endsWith("/");
      const name = isDirectory ? line.slice(0, -1) : line;
      return {
        name,
        isDirectory,
        path: path.posix.join(remotePath, name),
      } satisfies RemoteMusicEntry;
    })
    .filter((entry) => entry.name !== "." && entry.name !== "..")
    .sort((left, right) => {
      if (left.isDirectory !== right.isDirectory) {
        return left.isDirectory ? -1 : 1;
      }
      return left.name.localeCompare(right.name, "zh-CN");
    });
}
