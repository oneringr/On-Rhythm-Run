import type { AdbDevice } from "../../common/manifest";

export function buildScanStatus(trackCount: number, otherAudioExtensions: string[]): string {
  if (trackCount > 0) {
    return `已扫描 ${trackCount} 首歌曲，请确认标签后再导出。`;
  }

  if (otherAudioExtensions.length > 0) {
    return `当前目录中没有找到 .mp3 文件，仅发现 ${formatExtensions(otherAudioExtensions)}。PC 端当前只扫描当前文件夹中的 MP3。`;
  }

  return "当前目录中没有找到 .mp3 文件，请重新选择包含 MP3 的目录。";
}

export function formatExtensions(extensions: string[]): string {
  return extensions.map((extension) => extension.toLowerCase()).join("、");
}

export function formatDevice(device: AdbDevice): string {
  const parts = [device.model, device.deviceName, device.id].filter(Boolean);
  return `${parts.join(" / ")}${device.state && device.state !== "device" ? ` (${device.state})` : ""}`;
}

export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return "00:00";
  }
  const totalSeconds = Math.floor(seconds);
  const minutes = Math.floor(totalSeconds / 60);
  const remainingSeconds = totalSeconds % 60;
  return `${minutes.toString().padStart(2, "0")}:${remainingSeconds.toString().padStart(2, "0")}`;
}

export function toErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

export function toChineseLabel(label: "calm" | "excited"): string {
  return label === "calm" ? "舒缓" : "激动";
}
