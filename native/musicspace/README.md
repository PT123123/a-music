# musicspace(相似推荐查询引擎,Rust)

a-music 的"相似歌曲"推荐由本 crate 在设备上本地完成:读取由
[PT123123/music-recommend](https://github.com/PT123123/music-recommend) 桌面端导出的
打分载荷,在手机上重算 song→song 排名。**特征抽取永远不在手机上做**(librosa 级别的
成本,实测 43 秒/首);手机只做查询,单次查询毫秒级。

## 来源与同步纪律

代码 vendor 自 music-recommend 的 `rust/musicspace`(std-only 移植探针),改造为
lib + bin + JNI 三形态:

| 形态 | 用途 |
|------|------|
| `lib.rs`(rlib) | 打分核心 + 单元测试 |
| `main.rs`(bin) | 宿主机 parity 校验:与 Python 引擎的导出比对,逐位次一致 |
| JNI(cdylib,仅 Android) | `open(dir)` / `similar(track_id, limit)`,零依赖手写 JNI |

改这个 crate 的任何打分语义后,**必须**在桌面端 music-recommend 仓库重新导出并跑:

```bash
cd native/musicspace
cargo run --release -- <导出目录> --verify   # 必须输出 parity 0 mismatches + control 抓到破坏
```

`--verify` 里的反向对照(故意翻倍一个权重必须被抓出)是等价性成立的前提,别跳过。
所有权重/分组/混合系数都从导出的 `manifest.kv` 读,代码里没有硬编码——上游改了
`configs/weights.yaml` 后重新导出即可,无需改代码。

## 数据从哪来

在装了 Python 环境的桌面端 music-recommend 仓库:

```bash
PYTHONPATH=src .venv/Scripts/python.exe scripts/bench_portability.py --export <目录>
```

产出 `manifest.kv` + `tracks.tsv`(+ parity 用的 `expected_top20.tsv`,app 不读)。
载荷 ≈ 1.6 KB/首 + 96 B 向量,5000 首约 8.3 MB,且只有内容哈希 id、不含标题路径——
它是"你听过什么"的指纹,别传到公开位置。把该目录整个拷到手机后,在
a-music 的 设置 → 相似推荐 导入即可;app 用文件内容哈希(size + 首尾 256KB 的 SHA1)
把它和本地曲库对上。

## 构建(Gradle 自动完成)

`app/build.gradle.kts` 里的 `buildMusicspace` 任务在 assemble 前自动执行:

```bash
rustup target add aarch64-linux-android   # 一次性
./gradlew assembleDebug                    # 自动出 libmusicspace.so 并打进 APK
```

机器上没有 Rust 工具链时构建只是告警跳过,app 侧对应功能显示"不可用",不影响其余功能。
