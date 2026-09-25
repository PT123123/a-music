//! Host-side parity/bench CLI. The scoring core lives in the library (`src/lib.rs`);
//! this binary keeps the upstream verification discipline runnable: it loads an export,
//! re-derives song->song rankings, and diffs them against Python's own top-20.
//!
//!   cargo run --release -- data/portability
//!   cargo run --release -- data/portability --verify

use std::process::exit;
use std::time::Instant;

use musicspace::{expected, load, parity, Space};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let dir = args.get(1).cloned().unwrap_or_else(|| "data/portability".to_string());
    let verify = args.iter().any(|a| a == "--verify");
    let t_load = Instant::now();
    let space = load(&dir);
    let load_ms = t_load.elapsed().as_secs_f64() * 1000.0;
    println!(
        "loaded {} tracks, {} scalar columns, {} weights in {:.1} ms",
        space.track_count(),
        space.scalar_col_count(),
        space.weight_count(),
        load_ms
    );

    let mut t0 = Instant::now();
    let mut per_query = Vec::new();
    for round in 0..2 {
        // round 0 warms caches and branch predictors; report round 1
        if round == 1 {
            per_query.clear();
            t0 = Instant::now();
        }
        for seed in 0..space.track_count() {
            let s = Instant::now();
            let _ = space.similar(seed);
            per_query.push(s.elapsed().as_secs_f64() * 1000.0);
        }
    }
    per_query.sort_by(|a, b| a.partial_cmp(b).unwrap());
    println!(
        "all {} seeds: median {:.2} ms/query, max {:.2} ms (host x86 release build)",
        per_query.len(),
        per_query[per_query.len() / 2],
        per_query[per_query.len() - 1]
    );
    println!("total {:.1} ms for the whole library", t0.elapsed().as_secs_f64() * 1000.0);

    let exp = expected(&dir);
    if exp.is_empty() {
        println!("no expected_top20.tsv -> parity not checked");
        return;
    }
    let (order_bad, max_delta, compared) = parity(&space, &exp, true);
    println!(
        "parity vs Python over {} seeds / {} ranked slots: order mismatches = {order_bad}, max |score delta| = {max_delta:.2e}",
        exp.len(),
        compared
    );
    if verify {
        // Control: a zero-mismatch run is only meaningful if a deliberate weight
        // break is caught by the same comparison. Without this a misparsed manifest
        // could pass by comparing nothing.
        let mut broken = load(&dir);
        broken.double_first_non_embedding_weight();
        let (c_bad, c_delta, c_compared) = parity(&broken, &exp, false);
        println!("control (one weight doubled): order mismatches = {c_bad}, max |score delta| = {c_delta:.2e}");
        if c_bad == 0 || c_compared < compared {
            println!("CONTROL FAILED: parity diff cannot see a weight change -> not trustworthy");
            exit(1);
        }
        if order_bad != 0 || max_delta > 1e-4 {
            println!("FAIL");
            exit(1);
        }
    }
    println!("OK");
}
