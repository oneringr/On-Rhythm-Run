import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("music-metadata", () => ({
  parseFile: vi.fn(async (filePath: string) => ({
    common: {
      title: path.basename(filePath, path.extname(filePath)),
      artist: "测试歌手",
    },
    format: {
      duration: 123,
    },
  })),
}));

import { FolderScanner } from "./folderScanner.js";

const temporaryDirectories: string[] = [];

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { recursive: true, force: true }),
    ),
  );
});

describe("FolderScanner", () => {
  it("只扫描当前文件夹中的 mp3，不递归子文件夹", async () => {
    const rootDirectory = await mkdtemp(path.join(os.tmpdir(), "runner-folder-scanner-"));
    temporaryDirectories.push(rootDirectory);

    const currentLevelTrack = path.join(rootDirectory, "current-level.mp3");
    const currentLevelM4a = path.join(rootDirectory, "ignore-me.m4a");
    const nestedDirectory = path.join(rootDirectory, "nested");
    const nestedTrack = path.join(nestedDirectory, "nested-track.mp3");

    await mkdir(nestedDirectory);
    await writeFile(currentLevelTrack, "fake mp3");
    await writeFile(currentLevelM4a, "fake m4a");
    await writeFile(nestedTrack, "fake nested mp3");

    const scanner = new FolderScanner();
    const result = await scanner.scan(rootDirectory);

    expect(result.tracks).toHaveLength(1);
    expect(result.tracks[0]?.sourceFileName).toBe("current-level.mp3");
    expect(result.otherAudioExtensions).toEqual([".m4a"]);
  });
});
