import { useEffect } from "react";

type UseKeyboardShortcutsOptions = {
  hasPreviewTrack: boolean;
  onTogglePreviewPlayback: () => void | Promise<void>;
  onSeekPreviewByDelta: (deltaSeconds: number) => void;
  onPreviewRelativeTrack: (delta: number) => void;
  onApplyCalmLabel: () => void;
  onApplyExcitedLabel: () => void;
  onToggleAutoAdvance: () => void;
};

export function useKeyboardShortcuts({
  hasPreviewTrack,
  onTogglePreviewPlayback,
  onSeekPreviewByDelta,
  onPreviewRelativeTrack,
  onApplyCalmLabel,
  onApplyExcitedLabel,
  onToggleAutoAdvance,
}: UseKeyboardShortcutsOptions) {
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) {
        return;
      }

      const target = event.target;
      if (
        target instanceof HTMLElement &&
        (target.isContentEditable || target.matches("input, textarea, select"))
      ) {
        return;
      }

      switch (event.key) {
        case " ":
        case "Spacebar":
          event.preventDefault();
          void onTogglePreviewPlayback();
          break;
        case "ArrowLeft":
          if (!hasPreviewTrack) {
            return;
          }
          event.preventDefault();
          onSeekPreviewByDelta(-5);
          break;
        case "ArrowRight":
          if (!hasPreviewTrack) {
            return;
          }
          event.preventDefault();
          onSeekPreviewByDelta(5);
          break;
        case "ArrowUp":
          event.preventDefault();
          onPreviewRelativeTrack(-1);
          break;
        case "ArrowDown":
          event.preventDefault();
          onPreviewRelativeTrack(1);
          break;
        case "1":
          event.preventDefault();
          onApplyCalmLabel();
          break;
        case "2":
          event.preventDefault();
          onApplyExcitedLabel();
          break;
        case "0":
          event.preventDefault();
          onToggleAutoAdvance();
          break;
        default:
          break;
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [
    hasPreviewTrack,
    onApplyCalmLabel,
    onApplyExcitedLabel,
    onPreviewRelativeTrack,
    onSeekPreviewByDelta,
    onToggleAutoAdvance,
    onTogglePreviewPlayback,
  ]);
}
