import { useCallback, useEffect, useMemo, useState, type RefObject } from "react";
import type { AnalyzedTrack } from "../../common/manifest";
import { INITIAL_PREVIEW_TITLE } from "../config";
import { toErrorMessage } from "../utils/formatting";

type UseAudioPreviewOptions = {
  audioRef: RefObject<HTMLAudioElement>;
  tracks: AnalyzedTrack[];
  onStatusChange: (message: string) => void;
};

export function useAudioPreview({ audioRef, tracks, onStatusChange }: UseAudioPreviewOptions) {
  const [previewTitle, setPreviewTitle] = useState(INITIAL_PREVIEW_TITLE);
  const [previewDuration, setPreviewDuration] = useState(0);
  const [previewPosition, setPreviewPosition] = useState(0);
  const [isPreviewPlaying, setIsPreviewPlaying] = useState(false);
  const [isPreviewScrubbing, setIsPreviewScrubbing] = useState(false);
  const [activePreviewTrackId, setActivePreviewTrackId] = useState("");
  const [autoAdvanceOnLabelChange, setAutoAdvanceOnLabelChange] = useState(true);

  const activePreviewTrack = useMemo(
    () => tracks.find((track) => track.id === activePreviewTrackId) ?? null,
    [activePreviewTrackId, tracks],
  );
  const activePreviewTrackIndex = useMemo(
    () => tracks.findIndex((track) => track.id === activePreviewTrackId),
    [activePreviewTrackId, tracks],
  );
  const hasPreviewTrack = activePreviewTrackId !== "";

  useEffect(() => {
    const audioElement = audioRef.current;
    if (!audioElement) {
      return;
    }

    const handleLoadedMetadata = () => {
      setPreviewDuration(Number.isFinite(audioElement.duration) ? audioElement.duration : 0);
      setPreviewPosition(audioElement.currentTime || 0);
    };
    const handleTimeUpdate = () => {
      if (!isPreviewScrubbing) {
        setPreviewPosition(audioElement.currentTime || 0);
      }
    };
    const handlePlay = () => setIsPreviewPlaying(true);
    const handlePause = () => setIsPreviewPlaying(false);
    const handleEnded = () => {
      setIsPreviewPlaying(false);
      setIsPreviewScrubbing(false);
      setPreviewPosition(0);
    };

    audioElement.addEventListener("loadedmetadata", handleLoadedMetadata);
    audioElement.addEventListener("timeupdate", handleTimeUpdate);
    audioElement.addEventListener("play", handlePlay);
    audioElement.addEventListener("pause", handlePause);
    audioElement.addEventListener("ended", handleEnded);

    return () => {
      audioElement.removeEventListener("loadedmetadata", handleLoadedMetadata);
      audioElement.removeEventListener("timeupdate", handleTimeUpdate);
      audioElement.removeEventListener("play", handlePlay);
      audioElement.removeEventListener("pause", handlePause);
      audioElement.removeEventListener("ended", handleEnded);
    };
  }, [audioRef, isPreviewScrubbing]);

  useEffect(() => {
    if (activePreviewTrackId && !tracks.some((track) => track.id === activePreviewTrackId)) {
      setActivePreviewTrackId("");
      setPreviewTitle(INITIAL_PREVIEW_TITLE);
      setPreviewDuration(0);
      setPreviewPosition(0);
      setIsPreviewPlaying(false);
      setIsPreviewScrubbing(false);
    }
  }, [activePreviewTrackId, tracks]);

  const previewTrack = useCallback(async (track: AnalyzedTrack) => {
    try {
      const url = await window.runnerApp.toPreviewUrl(track.sourcePath);
      const title = `${track.title} - ${track.artist}`;
      setPreviewTitle(title);
      setActivePreviewTrackId(track.id);

      const audioElement = audioRef.current;
      if (!audioElement) {
        return;
      }

      audioElement.pause();
      if (audioElement.src !== url) {
        audioElement.src = url;
      }
      setPreviewDuration(0);
      setIsPreviewScrubbing(false);
      audioElement.currentTime = 0;
      setPreviewPosition(0);

      try {
        await audioElement.play();
        onStatusChange(`正在试听：${title}`);
      } catch (error) {
        onStatusChange(
          error instanceof Error
            ? `试听播放失败：${error.message}`
            : "已加载试听歌曲，请点击下方播放器继续播放。",
        );
      }
    } catch (error) {
      onStatusChange(toErrorMessage(error, "试听加载失败。"));
    }
  }, [audioRef, onStatusChange]);

  const togglePreviewPlayback = useCallback(async () => {
    const audioElement = audioRef.current;
    if (!audioElement || !audioElement.src) {
      onStatusChange("请先选择一首歌曲试听。");
      return;
    }

    try {
      if (audioElement.paused) {
        await audioElement.play();
      } else {
        audioElement.pause();
      }
    } catch (error) {
      onStatusChange(toErrorMessage(error, "试听播放失败。"));
    }
  }, [audioRef, onStatusChange]);

  const seekPreview = useCallback((nextPosition: number) => {
    const audioElement = audioRef.current;
    setPreviewPosition(nextPosition);
    if (audioElement) {
      audioElement.currentTime = nextPosition;
    }
  }, [audioRef]);

  const previewRelativeTrack = useCallback((delta: number) => {
    if (tracks.length === 0) {
      onStatusChange("请先扫描并选择一首歌曲试听。");
      return;
    }

    let nextIndex = 0;
    if (activePreviewTrackIndex >= 0) {
      nextIndex = (activePreviewTrackIndex + delta + tracks.length) % tracks.length;
    } else if (delta < 0) {
      nextIndex = tracks.length - 1;
    }

    void previewTrack(tracks[nextIndex]!);
  }, [activePreviewTrackIndex, onStatusChange, previewTrack, tracks]);

  const seekPreviewByDelta = useCallback((deltaSeconds: number) => {
    const audioElement = audioRef.current;
    if (!audioElement || !audioElement.src) {
      onStatusChange("请先选择一首歌曲试听。");
      return;
    }

    const duration = Number.isFinite(audioElement.duration) ? audioElement.duration : previewDuration;
    const maxDuration = Math.max(duration || 0, 0);
    const nextPosition = Math.min(
      Math.max((audioElement.currentTime || 0) + deltaSeconds, 0),
      maxDuration,
    );
    seekPreview(nextPosition);
  }, [audioRef, onStatusChange, previewDuration, seekPreview]);

  const startPreviewScrub = useCallback(() => {
    setIsPreviewScrubbing(true);
  }, []);

  const finishPreviewScrub = useCallback(() => {
    setIsPreviewScrubbing(false);
  }, []);

  const locateActivePreviewTrack = useCallback(() => {
    if (!hasPreviewTrack) {
      onStatusChange("请先选择一首歌曲试听。");
      return;
    }

    const rowElement = document.getElementById(`track-row-${activePreviewTrackId}`);
    if (!rowElement) {
      onStatusChange("未找到当前试听歌曲在列表中的位置。");
      return;
    }

    rowElement.scrollIntoView({
      behavior: "smooth",
      block: "center",
    });
  }, [activePreviewTrackId, hasPreviewTrack, onStatusChange]);

  const toggleAutoAdvanceOnLabelChange = useCallback(
    (onChanged?: (enabled: boolean) => void) => {
      setAutoAdvanceOnLabelChange((current) => {
        const next = !current;
        onChanged?.(next);
        return next;
      });
    },
    [],
  );

  return {
    previewTitle,
    previewDuration,
    previewPosition,
    isPreviewPlaying,
    activePreviewTrackId,
    activePreviewTrack,
    activePreviewTrackIndex,
    hasPreviewTrack,
    autoAdvanceOnLabelChange,
    previewTrack,
    togglePreviewPlayback,
    seekPreview,
    previewRelativeTrack,
    seekPreviewByDelta,
    startPreviewScrub,
    finishPreviewScrub,
    locateActivePreviewTrack,
    toggleAutoAdvanceOnLabelChange,
  };
}
