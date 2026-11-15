//! System tray for the Windows background agent.
//!
//! The tray is for configuration only, not a mandatory transfer workflow.
//! Menu items: connection status, trusted devices, pause/resume, settings,
//! diagnostics, exit.

use tauri::{AppHandle, Emitter};
use tauri::menu::{Menu, MenuItem};

/// Build and attach the system tray menu.
pub fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let menu = Menu::new(app)?;
    menu.append(&MenuItem::new(app, "tray_status", true, None::<&str>)?)?;
    menu.append(&MenuItem::new(app, "tray_trusted_devices", true, None::<&str>)?)?;
    menu.append(&MenuItem::new(app, "tray_pause_resume", true, None::<&str>)?)?;
    menu.append(&MenuItem::new(app, "tray_settings", true, None::<&str>)?)?;
    menu.append(&MenuItem::new(app, "tray_diagnostics", true, None::<&str>)?)?;
    menu.append(&MenuItem::new(app, "tray_exit", true, None::<&str>)?)?;

    let _ = tauri::tray::TrayIconBuilder::new()
        .menu(&menu)
        .show_menu_on_left_click(true)
        .on_menu_event(|app, event| {
            handle_tray_event(app, event.id.0.clone());
        })
        .build(app)?;

    tracing::info!("system tray built");
    Ok(())
}

/// Handle tray menu item clicks.
fn handle_tray_event(app: &AppHandle, item_id: String) {
    match item_id.as_str() {
        "tray_status" => {
            let _ = app.emit("flowww://tray", serde_json::json!({"kind": "status"}));
        }
        "tray_trusted_devices" => {
            let _ = app.emit("flowww://tray", serde_json::json!({"kind": "trusted-devices"}));
        }
        "tray_pause_resume" => {
            let _ = app.emit("flowww://tray", serde_json::json!({"kind": "pause-resume-toggled"}));
        }
        "tray_settings" => {
            let _ = app.emit("flowww://tray", serde_json::json!({"kind": "settings"}));
        }
        "tray_diagnostics" => {
            let _ = app.emit("flowww://tray", serde_json::json!({"kind": "diagnostics"}));
        }
        "tray_exit" => {
            tracing::warn!("tray exit requested");
            app.exit(0);
        }
        _ => {
            tracing::warn!("unknown tray item: {}", item_id);
        }
    }
}
