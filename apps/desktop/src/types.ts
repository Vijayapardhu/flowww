export type Platform = "windows" | "android" | "web" | "macos" | "linux" | "unknown";

export type DeviceSummary = {
  deviceId: string;
  name: string;
  platform: Platform;
  paired: boolean;
  online: boolean;
  fingerprint: string | null;
  lastSeenMs: number | null;
};

export type LocalDevice = {
  deviceId: string;
  name: string;
  platform: Platform;
  protocolVersion: string;
  fingerprint: string;
  listenPort: number;
};

export type TransferStatus =
  | "CREATED" | "OFFERED" | "ACCEPTED" | "CONNECTING" | "TRANSFERRING"
  | "PAUSED" | "VERIFYING" | "COMPLETED" | "CANCELLED" | "FAILED";

export type TransferSummary = {
  transferId: string;
  sourceDeviceId: string;
  destinationDeviceId: string;
  filename: string | null;
  size: number;
  status: TransferStatus;
  route: "direct" | "phone_relay" | "phone_to_desktop";
  direction: "incoming" | "outgoing";
  bytesTransferred: number;
  error: { code: string; message: string } | null;
  createdAt: number;
  completedAt: number | null;
};

export type PairingView = { uri: string; endpoint: string; expiresAt: number };

export type DesktopEvent =
  | { kind: "transfer-updated"; transfer: TransferSummary }
  | { kind: "peer-found"; device: DeviceSummary }
  | { kind: "peer-lost"; deviceId: string }
  | { kind: "pairing-started"; pairing: PairingView }
  | { kind: "pairing-completed"; device: DeviceSummary }
  | { kind: "pairing-failed"; reason: string }
  | { kind: "resync-required" }
  | { kind: "error"; code: string; message: string };
