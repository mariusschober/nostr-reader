export type CaptureFeedbackState = "saving" | "waiting" | "unpaired" | "delivered" | "error";

export interface CaptureFeedbackRoute {
  captureId: string;
  tabId: number;
  frameId: number;
  documentId?: string;
}

export const CAPTURE_FEEDBACK_TEXT: Record<CaptureFeedbackState, string> = {
  saving: "Saving…",
  waiting: "Saved — waiting for your phone",
  unpaired: "Saved — connect your phone",
  delivered: "On your phone",
  error: "Not delivered — open Reader settings to retry or export",
};

export function isCaptureFeedbackState(value: unknown): value is CaptureFeedbackState {
  return typeof value === "string" && Object.hasOwn(CAPTURE_FEEDBACK_TEXT, value);
}
