//! The online scoring path of PT123123/music-recommend, in std-only Rust.
//!
//! Vendored from `rust/musicspace` in that repository (the portability probe) and
//! reshaped for the a-music Android app: the scoring core lives in the `rlib` with
//! unit tests, the same CLI survives as `src/main.rs` (host-side parity discipline:
//! `cargo run --release -- <dir> --verify` must stay 0 mismatches after every change
//! to this code), and a tiny hand-rolled JNI layer (`cdylib` target, Android only)
//! exposes the two calls the app needs: `open(dir)` + `similar(track_id, limit)`.
//!
//! Data format is the export written by `scripts/bench_portability.py --export` in
//! the upstream repo: `manifest.kv` (tab-separated key/value config) + `tracks.tsv`
//! (one row per track). Every weight, group definition and blend comes from the
//! manifest — nothing here is hardcoded, mirroring upstream ADR-7.
//!
//! Difference from the vendored probe, deliberate: two-stage recall mirrors the
//! Python engine on large libraries — candidates are preselected by exact f32
//! dot-product top-`rerank_k` (the on-device equivalent of its FAISS Flat IP over
//! L2-normalized vectors) *before* the group rerank. For libraries smaller than
//! `rerank_k` the truncate is a no-op, so the parity evidence from the 93-track
//! export still covers the path exactly. `rerank_k` comes from the manifest key
//! `faiss_top_k` when present (the current exporter does not write it; the default
//! 200 matches `configs/config.yaml`).

use std::collections::{BTreeMap, HashMap, HashSet};

/// Field-internal separator used by the exporter.
pub const SEP: char = '\u{1f}';

/// Candidate cap for the pre-rerank stage (FAISS `faiss_top_k` in Python).
const DEFAULT_RERANK_K: usize = 200;

#[derive(Clone)]
pub struct Track {
    pub id: String,
    scalars: Vec<Option<f64>>,
    seqs: Vec<Vec<u32>>,
    curve: Vec<f64>,
    has_vocal: bool,
    emb: Vec<f64>,
}

pub struct Cfg {
    scalar_cols: Vec<String>,
    seq_col_names: Vec<String>,
    groups: BTreeMap<String, Vec<usize>>,  // group -> scalar column indices
    seq_of_group: BTreeMap<String, usize>, // group -> index into seq_col_names
    weights: Vec<(String, f64)>,           // manifest order == Python dict order
    blend_scalar_sequence: f64,
    curve_blend: (f64, f64),
    seq_weights: [f64; 4],
    ngram_n: usize,
    limit: usize,
    rerank_k: usize,
}

pub struct Space {
    tracks: Vec<Track>,
    sorted: Vec<Vec<f64>>, // per scalar column: all present values, sorted
    cfg: Cfg,
    by_id: HashMap<String, usize>,
}

/// One ranked result of a song->song query, with the group similarities that
/// produced it (the app renders these as reasons).
pub struct Hit {
    pub id: String,
    pub score: f64,
    pub groups: Vec<(String, f64)>,
}

fn parse_opt(s: &str) -> Option<f64> {
    if s.is_empty() {
        None
    } else {
        s.parse::<f64>().ok()
    }
}

fn kv_list(s: &str) -> Vec<String> {
    s.split(',').filter(|x| !x.is_empty()).map(|x| x.to_string()).collect()
}

/// Parse the manifest.kv text into the scoring config.
pub fn parse_manifest(manifest: &str) -> Cfg {
    let mut map: BTreeMap<String, String> = BTreeMap::new();
    for line in manifest.lines() {
        if let Some((k, v)) = line.split_once('\t') {
            map.insert(k.to_string(), v.to_string());
        }
    }

    let scalar_cols = kv_list(map.get("scalar_cols").expect("scalar_cols"));
    let seq_col_names = kv_list(map.get("seq_cols").expect("seq_cols"));
    let mut groups: BTreeMap<String, Vec<usize>> = BTreeMap::new();
    for entry in map.get("feature_groups").expect("feature_groups").split('|') {
        let (g, cols) = entry.split_once(':').expect("group:cols");
        let idx = kv_list(cols)
            .iter()
            .map(|c| scalar_cols.iter().position(|x| x == c).unwrap_or_else(|| panic!("unknown column {c}")))
            .collect();
        groups.insert(g.to_string(), idx);
    }
    let mut seq_of_group: BTreeMap<String, usize> = BTreeMap::new();
    for entry in map.get("sequence_cols_by_group").expect("sequence_cols_by_group").split('|') {
        let (g, c) = entry.split_once(':').expect("group:col");
        let i = seq_col_names.iter().position(|x| x == c).unwrap_or_else(|| panic!("unknown seq col {c}"));
        seq_of_group.insert(g.to_string(), i);
    }
    let weights = map
        .get("weights")
        .expect("weights")
        .split('|')
        .map(|e| {
            let (g, w) = e.split_once(':').expect("group:weight");
            (g.to_string(), w.parse().expect("weight"))
        })
        .collect();
    let curve_blend = map
        .get("structure_curve_blend")
        .map(|v| {
            let mut it = v.split('|');
            (it.next().unwrap().parse().unwrap(), it.next().unwrap().parse().unwrap())
        })
        .unwrap_or((0.6, 0.4));
    let seq_weights = map
        .get("combined_seq_weights")
        .map(|v| {
            let n: Vec<f64> = v.split('|').map(|x| x.parse().unwrap()).collect();
            [n[0], n[1], n[2], n[3]]
        })
        .unwrap_or([0.35, 0.25, 0.2, 0.2]);
    Cfg {
        scalar_cols,
        seq_col_names,
        groups,
        seq_of_group,
        weights,
        blend_scalar_sequence: map.get("blend_scalar_sequence").and_then(|v| v.parse().ok()).unwrap_or(0.5),
        curve_blend,
        seq_weights,
        ngram_n: map.get("ngram_n").and_then(|v| v.parse().ok()).unwrap_or(2),
        limit: map.get("limit").and_then(|v| v.parse().ok()).unwrap_or(20),
        rerank_k: map.get("faiss_top_k").and_then(|v| v.parse().ok()).unwrap_or(DEFAULT_RERANK_K),
    }
}

/// Build a Space from the raw manifest.kv and tracks.tsv texts.
pub fn build_space(manifest: &str, tracks_raw: &str) -> Space {
    let cfg = parse_manifest(manifest);
    let mut lines = tracks_raw.lines();
    let header: Vec<&str> = lines.next().expect("header").split('\t').collect();
    let col = |name: &str| header.iter().position(|h| *h == name).unwrap_or_else(|| panic!("missing column {name}"));
    let scalar_at: Vec<usize> = cfg.scalar_cols.iter().map(|c| col(c)).collect();
    let seq_at: Vec<usize> = cfg.seq_col_names.iter().map(|c| col(c)).collect();
    let (curve_at, vocal_at, emb_at) = (col("energy_curve"), col("has_vocal"), col("embedding"));

    let mut vocab: HashMap<String, u32> = HashMap::new();
    let mut tracks: Vec<Track> = Vec::new();
    for line in lines.filter(|l| !l.is_empty()) {
        let f: Vec<&str> = line.split('\t').collect();
        debug_assert_eq!(f.len(), header.len(), "a token contained a tab");
        let seqs = seq_at
            .iter()
            .map(|&i| {
                if f[i].is_empty() {
                    Vec::new()
                } else {
                    f[i]
                        .split(SEP)
                        .map(|tok| {
                            let next = vocab.len() as u32;
                            *vocab.entry(tok.to_string()).or_insert(next)
                        })
                        .collect()
                }
            })
            .collect();
        tracks.push(Track {
            id: f[0].to_string(),
            scalars: scalar_at.iter().map(|&i| parse_opt(f[i])).collect(),
            seqs,
            curve: if f[curve_at].is_empty() { Vec::new() } else { f[curve_at].split(SEP).filter_map(parse_opt).collect() },
            has_vocal: f[vocal_at] == "1",
            emb: if f[emb_at].is_empty() { Vec::new() } else { f[emb_at].split(SEP).filter_map(parse_opt).collect() },
        });
    }

    let ncols = cfg.scalar_cols.len();
    let mut sorted = vec![Vec::new(); ncols];
    for t in &tracks {
        for (i, v) in t.scalars.iter().enumerate() {
            if let Some(v) = v {
                sorted[i].push(*v);
            }
        }
    }
    for col in sorted.iter_mut() {
        col.sort_unstable_by(|a, b| a.partial_cmp(b).unwrap());
    }
    let by_id = tracks.iter().enumerate().map(|(i, t)| (t.id.clone(), i)).collect();
    Space { tracks, sorted, cfg, by_id }
}

/// Load a Space from a directory containing manifest.kv + tracks.tsv.
pub fn load(dir: &str) -> Space {
    let manifest = std::fs::read_to_string(format!("{dir}/manifest.kv"))
        .expect("manifest.kv: run scripts/bench_portability.py --export first");
    let tracks_raw = std::fs::read_to_string(format!("{dir}/tracks.tsv")).expect("tracks.tsv");
    build_space(&manifest, &tracks_raw)
}

fn count_map(tokens: &[u32], n: usize) -> BTreeMap<Vec<u32>, usize> {
    let mut out = BTreeMap::new();
    if tokens.len() >= n {
        for g in tokens.windows(n) {
            *out.entry(g.to_vec()).or_insert(0) += 1;
        }
    }
    out
}

fn counter_overlap(a: &BTreeMap<Vec<u32>, usize>, b: &BTreeMap<Vec<u32>, usize>) -> (usize, usize) {
    let mut inter = 0usize;
    let mut union = 0usize;
    for (k, &va) in a {
        let vb = b.get(k).copied().unwrap_or(0);
        inter += va.min(vb);
        union += va.max(vb);
    }
    for (k, vb) in b {
        if !a.contains_key(k) {
            union += vb;
        }
    }
    (inter, union)
}

fn counter_ratio(a: &BTreeMap<Vec<u32>, usize>, b: &BTreeMap<Vec<u32>, usize>, both_empty: f64) -> f64 {
    if a.is_empty() && b.is_empty() {
        return both_empty;
    }
    let (inter, union) = counter_overlap(a, b);
    if union == 0 {
        0.0
    } else {
        inter as f64 / union as f64
    }
}

/// Levenshtein similarity on interned token sequences: the textbook row DP.
fn levenshtein_similarity(a: &[u32], b: &[u32]) -> f64 {
    if a.is_empty() && b.is_empty() {
        return 1.0;
    }
    let (m, n) = (a.len(), b.len());
    let mut prev: Vec<usize> = (0..=n).collect();
    for i in 1..=m {
        let mut cur = vec![i; n + 1];
        for j in 1..=n {
            let cost = if a[i - 1] == b[j - 1] { 0 } else { 1 };
            cur[j] = (prev[j] + 1).min(cur[j - 1] + 1).min(prev[j - 1] + cost);
        }
        prev = cur;
    }
    1.0 - prev[n] as f64 / m.max(n) as f64
}

fn jaccard(a: &[u32], b: &[u32]) -> f64 {
    let sa: HashSet<u32> = a.iter().copied().collect();
    let sb: HashSet<u32> = b.iter().copied().collect();
    if sa.is_empty() && sb.is_empty() {
        return 1.0;
    }
    let inter = sa.intersection(&sb).count() as f64;
    let union = sa.union(&sb).count() as f64;
    if union == 0.0 {
        0.0
    } else {
        inter / union
    }
}

fn transitions(a: &[u32]) -> BTreeMap<Vec<u32>, usize> {
    let mut out = BTreeMap::new();
    for pair in a.windows(2) {
        *out.entry(pair.to_vec()).or_insert(0) += 1;
    }
    out
}

fn combined_sequence_similarity(a: &[u32], b: &[u32], cfg: &Cfg) -> f64 {
    if a.is_empty() || b.is_empty() {
        return 0.0;
    }
    let w = &cfg.seq_weights;
    let n = cfg.ngram_n;
    let ng = counter_ratio(&count_map(a, n), &count_map(b, n), 1.0);
    let tr = counter_ratio(&transitions(a), &transitions(b), 1.0);
    w[0] * levenshtein_similarity(a, b) + w[1] * ng + w[2] * jaccard(a, b) + w[3] * tr
}

fn clip01(x: f64) -> f64 {
    x.clamp(0.0, 1.0)
}

fn pearson(a: &[f64], b: &[f64]) -> f64 {
    let n = a.len() as f64;
    let ma = a.iter().sum::<f64>() / n;
    let mb = b.iter().sum::<f64>() / n;
    let mut saa = 0.0;
    let mut sbb = 0.0;
    let mut sab = 0.0;
    for i in 0..a.len() {
        let da = a[i] - ma;
        let db = b[i] - mb;
        saa += da * da;
        sbb += db * db;
        sab += da * db;
    }
    if saa > 0.0 && sbb > 0.0 {
        sab / (saa.sqrt() * sbb.sqrt())
    } else {
        0.0
    }
}

trait Round4 {
    fn round_to_4dp(self) -> f64;
}
impl Round4 for f64 {
    fn round_to_4dp(self) -> f64 {
        (self * 10000.0).round() / 10000.0
    }
}

/// Python's own top-20 for each sampled seed, from `expected_top20.tsv`.
pub fn expected(dir: &str) -> BTreeMap<String, Vec<(usize, String, f64)>> {
    let mut out: BTreeMap<String, Vec<(usize, String, f64)>> = BTreeMap::new();
    let raw = match std::fs::read_to_string(format!("{dir}/expected_top20.tsv")) {
        Ok(s) => s,
        Err(_) => return out,
    };
    for line in raw.lines().skip(1) {
        let f: Vec<&str> = line.split('\t').collect();
        if f.len() != 4 {
            continue;
        }
        out.entry(f[0].to_string()).or_default().push((f[1].parse().unwrap(), f[2].to_string(), f[3].parse().unwrap()));
    }
    out
}

/// Diff the port's rankings against Python's exported top-N.
/// Returns (order mismatches, max |score delta|, comparisons actually made).
pub fn parity(space: &Space, exp: &BTreeMap<String, Vec<(usize, String, f64)>>, report: bool) -> (usize, f64, usize) {
    let mut order_bad = 0usize;
    let mut max_delta = 0f64;
    let mut compared = 0usize;
    for (seed_id, rows) in exp.iter() {
        let si = match space.by_id.get(seed_id) {
            Some(i) => *i,
            None => {
                if report {
                    println!("seed {seed_id} missing from export");
                }
                order_bad += 1;
                continue;
            }
        };
        let got = space.similar(si);
        if got.len() != rows.len() {
            if report {
                println!("{seed_id}: length {} != {}", got.len(), rows.len());
            }
            order_bad += 1;
            continue;
        }
        for (rank, (ci, cscore)) in got.iter().enumerate() {
            let (erank, eid, escore) = &rows[rank];
            if erank != &rank {
                continue;
            }
            compared += 1;
            if &space.tracks[*ci].id != eid {
                if report && order_bad < 5 {
                    println!("{seed_id} rank {rank}: rust={} python={}", space.tracks[*ci].id, eid);
                }
                order_bad += 1;
            }
            max_delta = max_delta.max((cscore.round_to_4dp() - escore).abs());
        }
    }
    (order_bad, max_delta, compared)
}

impl Space {
    fn percentile(&self, ti: usize, col: usize) -> Option<f64> {
        let v = self.tracks[ti].scalars[col]?;
        let arr = &self.sorted[col];
        if arr.is_empty() {
            return None;
        }
        let i = arr.partition_point(|x| *x <= v);
        Some(i as f64 / arr.len() as f64)
    }

    fn structure_similarity(&self, a: usize, b: usize, seq_col: Option<usize>) -> Option<f64> {
        let mut seq_sim = None;
        if let Some(sc) = seq_col {
            let (sa, sb) = (&self.tracks[a].seqs[sc], &self.tracks[b].seqs[sc]);
            if sa.len() >= 2 && sb.len() >= 2 {
                seq_sim = Some(combined_sequence_similarity(sa, sb, &self.cfg));
            }
        }
        let (ca, cb) = (&self.tracks[a].curve, &self.tracks[b].curve);
        if ca.len() == cb.len() && ca.len() > 1 && ca.iter().any(|x| *x != 0.0) && cb.iter().any(|x| *x != 0.0) {
            let curve = clip01((pearson(ca, cb) + 1.0) / 2.0);
            return Some(match seq_sim {
                None => curve,
                Some(s) => clip01(self.cfg.curve_blend.0 * curve + self.cfg.curve_blend.1 * s),
            });
        }
        seq_sim
    }

    fn group_similarity(&self, a: usize, b: usize, group: &str) -> Option<f64> {
        if group == "structure" {
            return self.structure_similarity(a, b, self.cfg.seq_of_group.get(group).copied());
        }
        let cols = self.cfg.groups.get(group)?;
        if group == "vocal" && !(self.tracks[a].has_vocal && self.tracks[b].has_vocal) {
            return None;
        }
        let mut sum = 0.0;
        let mut n = 0usize;
        for &c in cols {
            if let (Some(pa), Some(pb)) = (self.percentile(a, c), self.percentile(b, c)) {
                sum += (pa - pb).abs();
                n += 1;
            }
        }
        let scalar = if n > 0 { Some(clip01(1.0 - sum / n as f64)) } else { None };
        if let Some(&sc) = self.cfg.seq_of_group.get(group) {
            let (sa, sb) = (&self.tracks[a].seqs[sc], &self.tracks[b].seqs[sc]);
            if sa.len() >= 2 && sb.len() >= 2 {
                let seq = combined_sequence_similarity(sa, sb, &self.cfg);
                return Some(match scalar {
                    None => seq,
                    Some(s) => clip01(self.cfg.blend_scalar_sequence * s + (1.0 - self.cfg.blend_scalar_sequence) * seq),
                });
            }
        }
        scalar
    }

    fn emb_sim(&self, a: usize, b: usize) -> Option<f64> {
        let (va, vb) = (&self.tracks[a].emb, &self.tracks[b].emb);
        if va.is_empty() || va.len() != vb.len() {
            return None;
        }
        // FAISS stores float32 and accumulates in float32; mirror that so the cosine
        // agrees with Python's rather than differing in the 7th decimal.
        let mut dot: f32 = 0.0;
        for i in 0..va.len() {
            dot += (va[i] as f32) * (vb[i] as f32);
        }
        Some(clip01((f64::from(dot) + 1.0) / 2.0))
    }

    fn score_pair(&self, a: usize, b: usize, emb: Option<f64>) -> (f64, BTreeMap<String, f64>) {
        let mut detail = BTreeMap::new();
        let mut total = 0.0;
        let mut weight_sum = 0.0;
        for (group, weight) in self.cfg.weights.iter() {
            let sim = if group == "embedding" {
                emb
            } else {
                self.group_similarity(a, b, group)
            };
            if let Some(s) = sim {
                detail.insert(group.clone(), s);
                total += weight * s;
                weight_sum += weight;
            }
        }
        let score = if weight_sum > 0.0 { total / weight_sum } else { 0.0 };
        (score, detail)
    }

    /// Score seed against the candidates given in embedding order (already
    /// preselected); returns (candidate, score, per-group similarities).
    fn score_candidates(&self, seed: usize, cands: &[(usize, f64)]) -> Vec<(usize, f64, BTreeMap<String, f64>)> {
        cands
            .iter()
            .map(|(i, e)| {
                let (score, detail) = self.score_pair(seed, *i, Some(*e));
                (*i, score, detail)
            })
            .collect()
    }

    /// song -> song, two-stage: exact dot-product top-`rerank_k` preselection (the
    /// on-device stand-in for Python's FAISS Flat recall), then the group rerank.
    fn rank(&self, seed: usize, limit: usize) -> Vec<(usize, f64, BTreeMap<String, f64>)> {
        let mut cands: Vec<(usize, f64)> = self
            .tracks
            .iter()
            .enumerate()
            .filter(|(i, _)| *i != seed)
            .map(|(i, _)| {
                let s = self.emb_sim(seed, i).unwrap_or(0.0);
                (i, s)
            })
            .collect();
        // FAISS hands Python candidates in descending similarity; keep that order so
        // equal scores break the same way (sort_by is stable).
        cands.sort_by(|x, y| y.1.partial_cmp(&x.1).unwrap_or(std::cmp::Ordering::Equal));
        cands.truncate(self.cfg.rerank_k);
        let mut scored: Vec<(usize, f64, BTreeMap<String, f64>)> = self.score_candidates(seed, &cands);
        scored.sort_by(|x, y| y.1.partial_cmp(&x.1).unwrap_or(std::cmp::Ordering::Equal));
        scored.truncate(limit);
        scored
    }

    /// Full-library ranking at the manifest's limit (the CLI/parity path).
    pub fn similar(&self, seed: usize) -> Vec<(usize, f64)> {
        self.rank(seed, self.cfg.limit).into_iter().map(|(i, s, _)| (i, s)).collect()
    }

    pub fn track_count(&self) -> usize {
        self.tracks.len()
    }

    pub fn scalar_col_count(&self) -> usize {
        self.sorted.len()
    }

    pub fn weight_count(&self) -> usize {
        self.cfg.weights.len()
    }

    /// Parity self-check control (CLI only): double one non-embedding weight so the
    /// diff can prove it would notice a real semantic break.
    pub fn double_first_non_embedding_weight(&mut self) {
        if let Some((_, w)) = self.cfg.weights.iter_mut().find(|(g, _)| g != "embedding") {
            *w *= 2.0;
        }
    }

    /// App entry point: rank by track id with an explicit limit. `None` when the id
    /// is not in the loaded payload.
    pub fn similar_by_id(&self, id: &str, limit: usize) -> Option<Vec<Hit>> {
        let seed = *self.by_id.get(id)?;
        Some(
            self.rank(seed, limit)
                .into_iter()
                .map(|(i, score, detail)| {
                    let mut groups: Vec<(String, f64)> = detail.into_iter().collect();
                    groups.sort_by(|x, y| y.1.partial_cmp(&x.1).unwrap_or(std::cmp::Ordering::Equal));
                    groups.truncate(4);
                    Hit { id: self.tracks[i].id.clone(), score, groups }
                })
                .collect(),
        )
    }
}

// ---------------------------------------------------------------------------
// Android JNI surface (cdylib target only). Hand-rolled against the stable JNI
// function-table indices to keep the crate dependency-free: the table layout is
// fixed ABI, NewStringUTF = 167, GetStringUTFChars = 169, ReleaseStringUTFChars = 170.
// ---------------------------------------------------------------------------
#[cfg(target_os = "android")]
mod jni {
    use super::{load, Space};
    use std::ffi::{c_char, c_void, CStr, CString};
    use std::sync::Mutex;

    static SPACE: Mutex<Option<Space>> = Mutex::new(None);

    unsafe fn fn_ptr(env: *mut c_void, idx: usize) -> *const c_void {
        let table = env as *const *const c_void;
        *table.add(idx)
    }

    unsafe fn jstring_to_string(env: *mut c_void, js: *const c_void) -> Option<String> {
        if env.is_null() || js.is_null() {
            return None;
        }
        type GetChars = unsafe extern "C" fn(*mut c_void, *const c_void, *mut u8) -> *const c_char;
        let get: GetChars = std::mem::transmute(fn_ptr(env, 169));
        let mut is_copy: u8 = 0;
        let p = get(env, js, &mut is_copy);
        if p.is_null() {
            return None;
        }
        let s = CStr::from_ptr(p).to_string_lossy().into_owned();
        type ReleaseChars = unsafe extern "C" fn(*mut c_void, *const c_void, *const c_char);
        let rel: ReleaseChars = std::mem::transmute(fn_ptr(env, 170));
        rel(env, js, p);
        Some(s)
    }

    unsafe fn string_to_jstring(env: *mut c_void, s: &str) -> *const c_void {
        type NewUtf = unsafe extern "C" fn(*mut c_void, *const c_char) -> *const c_void;
        let new: NewUtf = std::mem::transmute(fn_ptr(env, 167));
        match CString::new(s) {
            Ok(c) => new(env, c.as_ptr()),
            Err(_) => std::ptr::null(),
        }
    }

    fn escape_json(s: &str) -> String {
        let mut out = String::with_capacity(s.len());
        for ch in s.chars() {
            match ch {
                '"' => out.push_str("\\\""),
                '\\' => out.push_str("\\\\"),
                c => out.push(c),
            }
        }
        out
    }

    /// Open a payload directory (manifest.kv + tracks.tsv). Returns the track count,
    /// or -1 on failure (missing/unparsable files — the app keeps the old state).
    #[no_mangle]
    pub extern "system" fn Java_com_amusic_data_recommend_MusicSpaceNative_open(
        env: *mut c_void,
        _class: *const c_void,
        dir: *const c_void,
    ) -> i32 {
        let result = std::panic::catch_unwind(|| {
            let dir = unsafe { jstring_to_string(env, dir) }?;
            let space = std::panic::catch_unwind(|| load(&dir)).ok()?;
            let n = space.track_count() as i32;
            if let Ok(mut guard) = SPACE.lock() {
                *guard = Some(space);
            }
            Some(n)
        });
        result.ok().flatten().unwrap_or(-1)
    }

    /// song -> song similar. JSON: `[{"id":"..","score":0.6860,"groups":{"timbre":0.82,..}},..]`.
    /// Returns null when the seed id is not in the payload, "[]" when it has no candidates.
    #[no_mangle]
    pub extern "system" fn Java_com_amusic_data_recommend_MusicSpaceNative_similar(
        env: *mut c_void,
        _class: *const c_void,
        track_id: *const c_void,
        limit: i32,
    ) -> *const c_void {
        let out = std::panic::catch_unwind(|| {
            let id = unsafe { jstring_to_string(env, track_id) }?;
            let guard = SPACE.lock().ok()?;
            let space = guard.as_ref()?;
            let hits = space.similar_by_id(&id, limit.max(0) as usize)?;
            let mut json = String::from("[");
            for (i, h) in hits.iter().enumerate() {
                if i > 0 {
                    json.push(',');
                }
                json.push_str("{\"id\":\"");
                json.push_str(&escape_json(&h.id));
                json.push_str("\",\"score\":");
                json.push_str(&format!("{:.4}", h.score));
                json.push_str(",\"groups\":{");
                for (j, (g, s)) in h.groups.iter().enumerate() {
                    if j > 0 {
                        json.push(',');
                    }
                    json.push_str(&format!("\"{}\":{:.4}", escape_json(g), s));
                }
                json.push_str("}}");
            }
            json.push(']');
            Some(json)
        });
        match out.ok().flatten() {
            Some(json) => unsafe { string_to_jstring(env, &json) },
            None => std::ptr::null(),
        }
    }

    /// Drop the loaded payload (called when the import is replaced).
    #[no_mangle]
    pub extern "system" fn Java_com_amusic_data_recommend_MusicSpaceNative_close(
        _env: *mut c_void,
        _class: *const c_void,
    ) {
        if let Ok(mut guard) = SPACE.lock() {
            *guard = None;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_manifest() -> String {
        [
            "scalar_cols\tenv_rms,bpm,tempo_strength,chord_rate",
            "seq_cols\tchord_seq,melody_seq",
            "feature_groups\tenergy:env_rms|timbre:bpm,tempo_strength|harmony:chord_rate",
            "sequence_cols_by_group\tharmony:chord_seq|melody:melody_seq",
            "weights\tenergy:0.3|timbre:0.3|harmony:0.2|melody:0.2",
            "blend_scalar_sequence\t0.5",
            "structure_curve_blend\t0.6|0.4",
            "combined_seq_weights\t0.35|0.25|0.2|0.2",
            "ngram_n\t2",
            "limit\t2",
        ]
        .join("\n")
    }

    fn row(id: &str, rms: &str, bpm: &str, ts: &str, rate: &str, chord: &str, melody: &str, emb: &str) -> String {
        // header: id, env_rms, bpm, tempo_strength, chord_rate, chord_seq, melody_seq, energy_curve, has_vocal, embedding
        format!("{id}\t{rms}\t{bpm}\t{ts}\t{rate}\t{chord}\t{melody}\t0.1\u{1f}0.5\u{1f}0.9\t0\t{emb}")
    }

    /// Header + rows, matching the exporter's tracks.tsv layout.
    fn tracks_table(rows: &[String]) -> String {
        let mut header = "track_id\tenv_rms\tbpm\ttempo_strength\tchord_rate\tchord_seq\tmelody_seq\tenergy_curve\thas_vocal\tembedding".to_string();
        for r in rows {
            header.push('\n');
            header.push_str(r);
        }
        header
    }

    #[test]
    fn manifest_defaults_and_unknown_column() {
        let cfg = parse_manifest(&test_manifest());
        assert_eq!(cfg.limit, 2);
        assert_eq!(cfg.rerank_k, DEFAULT_RERANK_K);
        assert_eq!(cfg.seq_of_group["harmony"], 0);
        let bad = test_manifest().replace("scalar_cols\tenv_rms", "scalar_cols\tnope");
        assert!(std::panic::catch_unwind(|| parse_manifest(&bad)).is_err());
    }

    #[test]
    fn ranks_by_weighted_similarity_and_reports_groups() {
        // track b shares both scalar neighborhood and sequences with a; c differs.
        let tracks = [
            row("a", "0.5", "120", "0.8", "0.4", "1\u{1f}2\u{1f}3\u{1f}4", "10\u{1f}11\u{1f}12", "0.9|0.1"),
            row("b", "0.55", "122", "0.75", "0.42", "1\u{1f}2\u{1f}3\u{1f}5", "10\u{1f}11\u{1f}13", "0.89|0.1"),
            row("c", "0.1", "60", "0.2", "0.9", "9\u{1f}8\u{1f}7\u{1f}6", "20\u{1f}21\u{1f}22", "0.1|0.9"),
        ];
        let space = build_space(&test_manifest(), &tracks_table(&tracks));
        assert_eq!(space.track_count(), 3);
        let hits = space.similar_by_id("a", 2).expect("seed present");
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0].id, "b");
        assert!(hits[0].score > hits[1].score);
        // harmony is a manifest group, so its sequence similarity must surface.
        assert!(hits[0].groups.iter().any(|(g, _)| g == "harmony"));
        assert!(space.similar_by_id("missing", 2).is_none());
    }

    #[test]
    fn two_stage_recall_caps_rerank_candidates() {
        let mut manifest = test_manifest();
        manifest.push_str("\nfaiss_top_k\t1");
        let tracks = [
            row("s", "0.5", "120", "0.8", "0.4", "1\u{1f}2\u{1f}3\u{1f}4", "10\u{1f}11\u{1f}12", "1|0"),
            // nearest by embedding (dot ≈ 0.99) but least similar on every group.
            row("near_emb", "0.0", "40", "0.1", "0.9", "7\u{1f}7\u{1f}7\u{1f}7", "30\u{1f}30\u{1f}30", "0.99|0"),
            // far by embedding (dot ≈ 0.14) but identical groups otherwise.
            row("grp", "0.5", "120", "0.8", "0.4", "1\u{1f}2\u{1f}3\u{1f}4", "10\u{1f}11\u{1f}12", "0.14|0.99"),
        ];
        let space = build_space(&manifest, &tracks_table(&tracks));
        let hits = space.similar_by_id("s", 2).expect("seed present");
        // rerank_k=1 keeps only the best-embedding candidate (near_emb), so grp never
        // gets reranked even though its group similarities are perfect.
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].id, "near_emb");
    }

    #[test]
    fn embedding_only_rows_still_rank() {
        let tracks = [
            row("x", "", "", "", "", "", "", "0.8|0.6"),
            row("y", "", "", "", "", "", "", "0.8|0.6"),
            row("z", "", "", "", "", "", "", "-0.8|0.6"),
        ];
        let space = build_space(&test_manifest(), &tracks_table(&tracks));
        let hits = space.similar_by_id("x", 2).expect("seed present");
        assert_eq!(hits[0].id, "y");
    }
}
