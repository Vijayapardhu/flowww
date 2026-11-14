import { describe, expect, it } from "vitest";
import type { DeviceSummary, TransferSummary } from "./types";

const device = (online: boolean): DeviceSummary => ({
  deviceId: "dev_a", name: "A", platform: "windows", paired: true,
  online, fingerprint: "abcd", lastSeenMs: null,
});

describe("desktop state DTOs", () => {
  it("keeps offline devices distinct from paired devices", () => {
    expect(device(false).paired).toBe(true);
    expect(device(false).online).toBe(false);
  });

  it("can represent an active transfer without inventing progress", () => {
    const transfer: TransferSummary = {
      transferId: "tr_a", sourceDeviceId: "dev_a", destinationDeviceId: "dev_b",
      filename: "payload.bin", size: 100, status: "TRANSFERRING", route: "direct",
      direction: "outgoing", bytesTransferred: 0, error: null, createdAt: 1, completedAt: null,
    };
    expect(transfer.bytesTransferred).toBe(0);
    expect(transfer.status).toBe("TRANSFERRING");
  });
});
