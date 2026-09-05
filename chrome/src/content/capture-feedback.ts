import { CAPTURE_FEEDBACK_TEXT, type CaptureFeedbackState } from "../protocol/capture-feedback.js";

/** One visible capture owns the notice. An older action/timeout cannot replace a
 * newer one, and a late saved response cannot undo an authenticated delivery. */
export class CaptureFeedback {
  private captureId?: string;
  private state?: CaptureFeedbackState;
  private text?: string;
  private host?: HTMLElement;
  private status?: HTMLElement;
  private hideTimer?: ReturnType<typeof setTimeout>;

  begin(captureId: string): void {
    if (captureId === this.captureId) return;
    this.captureId = captureId;
    this.state = undefined;
    this.update(captureId, "saving");
  }

  isPending(captureId: string): boolean {
    return captureId === this.captureId && (this.state === "saving" || this.state === "waiting" || this.state === "unpaired");
  }

  update(captureId: string, state: CaptureFeedbackState, detail?: string): void {
    if (captureId !== this.captureId || this.state === "delivered" || this.state === "error") return;
    const text = detail?.slice(0, 400) || CAPTURE_FEEDBACK_TEXT[state];
    if (this.state === state && this.text === text) return;
    this.state = state;
    this.text = text;
    clearTimeout(this.hideTimer);
    if (!this.host?.isConnected) {
      this.host = document.createElement("div");
      this.host.dataset.readerUi = "status";
      const shadow = this.host.attachShadow({ mode: "closed" });
      this.status = document.createElement("div");
      this.status.setAttribute("role", "status");
      this.status.setAttribute("aria-live", "polite");
      this.status.setAttribute("aria-atomic", "true");
      this.status.style.cssText = "position:fixed;bottom:24px;right:24px;z-index:2147483647;max-width:320px;padding:12px 16px;background:#100f0f;color:#fffcf0;border:1px solid #6f6e69;border-radius:10px;font:14px/1.5 system-ui;box-shadow:0 4px 18px #0003;pointer-events:none";
      shadow.append(this.status);
      (document.body ?? document.documentElement).append(this.host);
    }
    this.status!.textContent = text;
    if (state !== "saving") {
      this.hideTimer = setTimeout(() => this.host?.remove(), state === "delivered" ? 5000 : 8000);
    }
  }
}
