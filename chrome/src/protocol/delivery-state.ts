export type DeliveryStatus = "queued" | "relay_accepted" | "awaiting_device" | "failed";

export interface DeliveryStateItem {
  status: DeliveryStatus;
  attemptCount: number;
  expiresAt: number;
}

export interface DeliveryStateSummary {
  queued: number;
  relayAccepted: number;
  awaitingDevice: number;
  delivered: number;
  failed: number;
}

/** Hourly capped backoff plus this ceiling can never retry indefinitely. */
export const MAX_DELIVERY_ATTEMPTS = 168;

export function deliveryCeilingReason(item: DeliveryStateItem, nowSecs: number): string | null {
  if (nowSecs >= item.expiresAt) return "delivery-age-cap";
  if (item.attemptCount >= MAX_DELIVERY_ATTEMPTS) return "delivery-attempt-cap";
  return null;
}

export function summarizeDeliveryStates(
  items: readonly Pick<DeliveryStateItem, "status">[],
  delivered: number,
): DeliveryStateSummary {
  const summary: DeliveryStateSummary = {
    queued: 0,
    relayAccepted: 0,
    awaitingDevice: 0,
    delivered: Math.max(0, delivered),
    failed: 0,
  };
  for (const item of items) {
    if (item.status === "queued") summary.queued += 1;
    else if (item.status === "relay_accepted") summary.relayAccepted += 1;
    else if (item.status === "awaiting_device") summary.awaitingDevice += 1;
    else summary.failed += 1;
  }
  return summary;
}
