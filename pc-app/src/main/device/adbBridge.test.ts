import { describe, expect, it } from "vitest";
import { ensureMusicPath, parseAdbDevices, parseRemoteEntries } from "./adbBridge.js";

describe("adbBridge parsers", () => {
  it("parses adb devices -l output", () => {
    const devices = parseAdbDevices(`
      List of devices attached
      78dcfb92 device product:owatch model:OW20W3 device:ow20 transport_id:2
      emulator-5554 offline transport_id:3
    `);

    expect(devices).toEqual([
      {
        id: "78dcfb92",
        state: "device",
        model: "OW20W3",
        product: "owatch",
        deviceName: "ow20",
      },
      {
        id: "emulator-5554",
        state: "offline",
        model: undefined,
        product: undefined,
        deviceName: undefined,
      },
    ]);
  });

  it("parses and sorts remote music entries", () => {
    const entries = parseRemoteEntries(
      ["Album/", "track-b.mp3", "track-a.mp3", ".", ".."].join("\n"),
      "/sdcard/Music",
    );

    expect(entries).toEqual([
      {
        name: "Album",
        path: "/sdcard/Music/Album",
        isDirectory: true,
      },
      {
        name: "track-a.mp3",
        path: "/sdcard/Music/track-a.mp3",
        isDirectory: false,
      },
      {
        name: "track-b.mp3",
        path: "/sdcard/Music/track-b.mp3",
        isDirectory: false,
      },
    ]);
  });

  it("normalizes managed remote paths and blocks traversal", () => {
    expect(ensureMusicPath("/sdcard/Music/RunnerPlayerExport/../test")).toBe("/sdcard/Music/test");
    expect(() => ensureMusicPath("/sdcard/Music/../../data/data/com.test")).toThrow(
      "仅允许管理 /sdcard/Music 目录中的文件。",
    );
  });
});
