use std::net::SocketAddr;
use std::path::PathBuf;

use anyhow::Context;
use clap::Parser;
use tracing_subscriber::EnvFilter;

use flowww_bridge::{Bridge, BridgeConfig};
use flowww_node::{Node, NodeConfig};

/// flowww — offline-first content transfer over your local network.
#[derive(Debug, Parser)]
#[command(name = "flowww", version, about)]
struct Args {
    /// Port for the local interface. Use 0 to pick a free port.
    #[arg(long, default_value_t = 47800)]
    port: u16,

    /// Where identity, certificates and history are stored.
    #[arg(long, env = "FLOWWW_DATA_DIR")]
    data_dir: Option<PathBuf>,

    /// Where received files are written.
    #[arg(long, env = "FLOWWW_DOWNLOAD_DIR")]
    download_dir: Option<PathBuf>,

    /// Name shown to nearby devices.
    #[arg(long, env = "FLOWWW_DEVICE_NAME")]
    name: Option<String>,

    /// Do not advertise or browse on the local network.
    #[arg(long)]
    no_discovery: bool,

    /// Accept incoming offers without asking in the interface.
    #[arg(long)]
    auto_accept: bool,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .with_target(false)
        .compact()
        .init();

    let args = Args::parse();

    let mut config = NodeConfig::new(args.data_dir.unwrap_or_else(NodeConfig::default_path));
    if let Some(download_dir) = args.download_dir {
        config = config.with_download_dir(download_dir);
    }
    if let Some(name) = args.name {
        config = config.with_device_name(name);
    }
    config.enable_discovery = !args.no_discovery;
    config.auto_accept = args.auto_accept;

    let node = Node::start(config)
        .await
        .context("could not start the daemon")?;
    let bridge = Bridge::start(
        node.clone(),
        BridgeConfig {
            bind_addr: SocketAddr::from(([127, 0, 0, 1], args.port)),
            ..BridgeConfig::default()
        },
    )
    .await
    .context("could not start the local interface")?;

    println!();
    println!("  flowww is running.");
    println!();
    println!("  Open this URL in a browser:");
    println!();
    println!("    {}", bridge.url());
    println!();
    println!(
        "  Files arrive in: {}",
        node.config().download_dir().display()
    );
    println!("  Press Ctrl+C to stop.");
    println!();

    tokio::signal::ctrl_c()
        .await
        .context("could not listen for shutdown")?;

    println!("Stopping flowww…");
    bridge.shutdown().await;
    node.shutdown();
    Ok(())
}
