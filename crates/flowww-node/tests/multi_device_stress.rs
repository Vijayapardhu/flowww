//! Opt-in multi-device stress tests.
//!
//! These tests use real pinned-TLS nodes and the production transfer path, but
//! remain ignored by default because their size and runtime depend on the host.
//! Run with `--ignored -- --nocapture` and set `FLOWWW_STRESS_SIZE_MB` (100 is
//! the default; set `FLOWWW_STRESS_ONE_GB=1` for the one-gigabyte case).

use std::net::SocketAddr;
use std::path::Path;
use std::time::Instant;

use flowww_node::{Node, NodeConfig};
use tokio::io::AsyncWriteExt;

fn config(dir: &Path, name: &str) -> NodeConfig {
    NodeConfig::new(dir)
        .with_download_dir(dir.join("downloads"))
        .with_device_name(name)
        .with_discovery(false)
        .with_listen_addr("127.0.0.1:0".parse().expect("loopback"))
        .with_auto_accept(true)
}

fn endpoint(node: &Node) -> SocketAddr {
    SocketAddr::from(([127, 0, 0, 1], node.listen_port()))
}

fn trust(left: &Node, right: &Node) {
    left.store()
        .save_pair(
            right.device(),
            &right.public_key(),
            &right.tls_fingerprint(),
            None,
        )
        .expect("trust persists");
}

async fn wire(nodes: &[Node]) {
    for left in nodes {
        for right in nodes {
            if left.device_id() != right.device_id() {
                trust(left, right);
                left.register_peer_endpoint(right.device_id(), endpoint(right))
                    .await;
            }
        }
    }
}

async fn make_payload(path: &Path, size: u64) {
    let mut file = tokio::fs::File::create(path)
        .await
        .expect("payload creates");
    let block = vec![0x5a; 1024 * 1024];
    let mut remaining = size;
    while remaining > 0 {
        let length = remaining.min(block.len() as u64) as usize;
        file.write_all(&block[..length])
            .await
            .expect("payload writes");
        remaining -= length as u64;
    }
    file.sync_all().await.expect("payload syncs");
}

async fn run_stress(node_count: usize) {
    let size_mb = std::env::var("FLOWWW_STRESS_SIZE_MB")
        .ok()
        .and_then(|value| value.parse::<u64>().ok())
        .unwrap_or(100);
    let size = if std::env::var_os("FLOWWW_STRESS_ONE_GB").is_some() {
        1024 * 1024 * 1024
    } else {
        size_mb * 1024 * 1024
    };
    let dirs = (0..node_count)
        .map(|_| tempfile::tempdir().expect("temp dir"))
        .collect::<Vec<_>>();
    let mut nodes = Vec::with_capacity(node_count);
    for (index, dir) in dirs.iter().enumerate() {
        nodes.push(
            Node::start(config(dir.path(), &format!("stress-{index}")))
                .await
                .expect("node starts"),
        );
    }
    wire(&nodes).await;

    let payload = dirs[0].path().join("stress.bin");
    make_payload(&payload, size).await;
    let started = Instant::now();
    let mut sends = Vec::new();
    for destination in nodes.iter().skip(1) {
        sends.push(nodes[0].send_file(destination.device_id(), &payload));
    }
    let results = futures_util::future::join_all(sends).await;
    let elapsed = started.elapsed();
    for result in &results {
        let record = result.as_ref().expect("stress transfer succeeds");
        assert_eq!(record.bytes_transferred, size);
        assert_eq!(record.status, flowww_core::TransferState::Completed);
    }
    let aggregate = size.saturating_mul((node_count - 1) as u64);
    let throughput = aggregate as f64 / elapsed.as_secs_f64() / 1024.0 / 1024.0;
    println!(
        "stress_metrics topology={} payload_bytes={} transfers={} duration_ms={} aggregate_mib_per_second={throughput:.2}",
        node_count,
        size,
        node_count - 1,
        elapsed.as_millis()
    );
    for node in nodes {
        node.shutdown();
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 8)]
#[ignore = "opt-in LAN reliability/stress test"]
async fn five_device_concurrent_direct_stress() {
    run_stress(5).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 12)]
#[ignore = "opt-in LAN reliability/stress test"]
async fn ten_device_concurrent_direct_stress() {
    run_stress(10).await;
}
