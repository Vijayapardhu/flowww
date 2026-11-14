//! Windows sign-in auto-start opt-in for the background agent.
//!
//! Registers/unregisters the agent to launch at user sign-in via the
//! Windows Run registry key. Opt-in only; the user can disable at any time.
//!
//! All operations are idempotent and degrade gracefully when registry
//! access fails (e.g., due to permissions).

use std::path::Path;

/// Registry key path for per-user auto-start.
pub const RUN_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
pub const VALUE_NAME: &str = "Flowww";

/// Enable auto-start at user sign-in.
///
/// On Windows, adds a value under `HKCU\...\Run` pointing to the agent
/// executable. Idempotent: calling with `auto_start = true` when already
/// enabled succeeds without error. On non-Windows platforms, this is a
/// no-op returning Ok.
pub fn enable(auto_start: bool, agent_exe: Option<&Path>) -> std::io::Result<()> {
    #[cfg(target_os = "windows")]
    {
        enable_windows(auto_start, agent_exe)
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = (auto_start, agent_exe);
        Ok(())
    }
}

/// Check whether auto-start is currently enabled.
pub fn is_enabled() -> std::io::Result<bool> {
    #[cfg(target_os = "windows")]
    {
        is_enabled_windows()
    }
    #[cfg(not(target_os = "windows"))]
    {
        Ok(false)
    }
}

/// Validate that a startup entry points to an existing executable.
pub fn validate_startup_entry() -> std::io::Result<bool> {
    #[cfg(target_os = "windows")]
    {
        validate_startup_entry_windows()
    }
    #[cfg(not(target_os = "windows"))]
    {
        Ok(false)
    }
}

#[cfg(target_os = "windows")]
fn enable_windows(auto_start: bool, agent_exe: Option<&Path>) -> std::io::Result<()> {
    if auto_start {
        let exe = agent_exe
            .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::InvalidInput, "agent executable path required"))?;

        // Idempotent: if the entry already exists with the same value, skip.
        if is_enabled_windows()? {
            let existing = get_existing_value()?;
            if let Some(ref current) = existing {
                if current.as_str() == exe.to_string_lossy().as_ref() {
                    return Ok(()); // Already configured correctly.
                }
            }
        }

        let exe_str = exe.to_string_lossy();
        let output = std::process::Command::new("reg")
            .args(["add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d", &*exe_str, "/f"])
            .output()?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("reg add failed: {stderr}"),
            ));
        }
        Ok(())
    } else {
        // Idempotent: if already disabled, skip.
        if !is_enabled_windows()? {
            return Ok(());
        }

        let output = std::process::Command::new("reg")
            .args(["delete", RUN_KEY, "/v", VALUE_NAME, "/f"])
            .output()?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("reg delete failed: {stderr}"),
            ));
        }
        Ok(())
    }
}

#[cfg(target_os = "windows")]
fn is_enabled_windows() -> std::io::Result<bool> {
    let output = std::process::Command::new("reg")
        .args(["query", RUN_KEY, "/v", VALUE_NAME])
        .output()?;
    Ok(output.status.success())
}

#[cfg(target_os = "windows")]
fn get_existing_value() -> std::io::Result<Option<String>> {
    let output = std::process::Command::new("reg")
        .args(["query", RUN_KEY, "/v", VALUE_NAME])
        .output()?;

    if !output.status.success() {
        return Ok(None);
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    // reg query output format: "HKCU\...\Run    VALUE_NAME    REG_SZ    value"
    for line in stdout.lines() {
        if line.contains(VALUE_NAME) {
            // Split on whitespace and take the last part as the value.
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 4 {
                return Ok(Some(parts[3..].join(" ")));
            }
        }
    }
    Ok(None)
}

#[cfg(target_os = "windows")]
fn validate_startup_entry_windows() -> std::io::Result<bool> {
    if !is_enabled_windows()? {
        return Ok(false);
    }

    let existing = match get_existing_value()? {
        Some(v) => v,
        None => return Ok(false),
    };

    let path = Path::new(&existing);
    Ok(path.exists())
}

/// Return the path to the current executable, if available.
pub fn current_exe_path() -> Option<std::path::PathBuf> {
    std::env::current_exe().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constants_are_defined() {
        assert!(!RUN_KEY.is_empty());
        assert!(!VALUE_NAME.is_empty());
    }

    #[test]
    fn current_exe_path_returns_option() {
        // Just verify it doesn't panic.
        let _ = current_exe_path();
    }

    #[test]
    fn validate_startup_entry_returns_bool() {
        // On non-Windows, returns false. On Windows, may return true/false.
        let _ = validate_startup_entry();
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn parse_reg_query_line() {
        // Simulate parsing reg query output
        let line = r#"HKCU\Software\Microsoft\Windows\CurrentVersion\Run    Flowww    REG_SZ    C:\flowww\agent.exe"#;
        let parts: Vec<&str> = line.split_whitespace().collect();
        assert!(parts.len() >= 4);
        assert_eq!(parts[3..].join(" "), r#"C:\flowww\agent.exe"#);
    }
}
