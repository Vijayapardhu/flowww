import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type { DesktopEvent, DeviceSummary, LocalDevice, PairingView, TransferSummary } from "./types";

export const backend = {
  initializeNode: () => invoke<LocalDevice>("initialize_node"),
  getLocalDevice: () => invoke<LocalDevice>("get_local_device"),
  getNearbyDevices: () => invoke<DeviceSummary[]>("get_nearby_devices"),
  getTrustedDevices: () => invoke<DeviceSummary[]>("get_trusted_devices"),
  startPairing: () => invoke<PairingView>("start_pairing"),
  approvePairing: (uri: string) => invoke<DeviceSummary>("approve_pairing", { uri }),
  rejectPairing: () => invoke<boolean>("reject_pairing"),
  getTransferHistory: () => invoke<TransferSummary[]>("get_transfer_history"),
  getActiveTransfers: () => invoke<TransferSummary[]>("get_active_transfers"),
  getTransferStatus: (transferId: string) => invoke<TransferSummary>("get_transfer_status", { transferId }),
  cancelTransfer: (transferId: string) => invoke<boolean>("cancel_transfer", { transferId }),
  shutdownNode: () => invoke<void>("shutdown_node"),
  listen: (callback: (event: DesktopEvent) => void): Promise<UnlistenFn> =>
    listen<DesktopEvent>("flowww://event", (event) => callback(event.payload)),
};
