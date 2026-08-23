# timeliner

位置情報サービスからエクスポートしたタイムラインJSONを読み込み、いつ・どこへ・どのようなルートで移動したかを地図上で振り返れるAndroidアプリ。

## 概要

- タイムラインJSONのインポート（複数のエクスポート形式に対応）
- 実際の地図上への移動ルート表示
- ルートを時間経過に沿って動かすアニメーション再生（速度変更・自動速度設定を含む）
- 期間指定（日・週・月・年など）での振り返り
- アニメーションの動画としての保存・共有
- Android端末へAPKとしてインストールして利用する

具体的な要件・技術選定・実装方針は `docs/tasks.md`（タスク管理）・`docs/decisions.md`（設計判断）・`docs/progress.md`（作業履歴）を参照。

## 開発方針

このリポジトリは `project001`（Claude CodeによるAI開発OSテンプレート）から作成した。開発方針・設計原則・ワークフローは AGENTS.md、レビュー方針は REVIEW.md、Claude Code固有の運用は CLAUDE.md に従う。

開発フロー: User → Manager → Planner → Developer → Reviewer → Manager → Complete
（外部調査が必要な場合のみResearcherが加わる）

## セットアップ

1. `git clone`等でこのリポジトリを取得する。
2. （任意）`bash .claude/bootstrap.sh`を実行し、Optional Dependency（Agent-Reach/Code Review Graph/Context7/GitHub CLI等）の導入状況を確認する。インストールは行わず案内のみを表示するため、実行しなくても開発は開始できる。
3. Androidアプリのビルド手順は下記「Androidアプリのビルド」を参照。

## Androidアプリのビルド

### 必要環境

- Android SDK（`compileSdk`/`targetSdk` 36、`minSdk` 29）。`platforms;android-36`・`build-tools;36.1.0`以上、`platform-tools`が必要。
  - `ANDROID_HOME`（または`ANDROID_SDK_ROOT`）を設定するか、リポジトリ直下に`local.properties`（`sdk.dir=<SDKのパス>`）を用意する。
- JDK 17以上（`app/build.gradle.kts`の`sourceCompatibility`/`targetCompatibility`がJava 17）。
- ネットワーク接続（初回ビルド時に依存ライブラリ・Gradle本体をダウンロードするため。地図タイル自体は実行時にOpenFreeMapのベクタータイルをネットワーク経由で取得する。位置情報データ自体は端末外へ送信しない）。

### デバッグビルド

```
./gradlew assembleDebug
```

成功すると`app/build/outputs/apk/debug/app-debug.apk`が生成される（デバッグ用の自動生成キーで署名済みのため、そのまま実機・エミュレータへインストールできる）。

単体テストの実行:

```
./gradlew testDebugUnitTest
```

### 実機へのインストール

USBデバッグを有効化した実機、または起動済みのエミュレータに対し:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb`が使えない環境では、生成された`app-debug.apk`を端末へ手動転送（ファイル共有・メール添付等）し、端末側のファイルマネージャから開いてインストールしてもよい（「提供元不明のアプリ」のインストールを許可する必要がある場合がある）。

## 対応しているタイムラインJSON形式

以下4形式のエクスポートファイル（`.json`単体、またはそれらを含む`.zip`）をインポートできる。形式は内容から自動判別するため、ユーザーが指定する必要はない。

| 形式 | 主なエクスポート元 | 判別キー |
|------|--------------------|----------|
| 端末内Timeline（Android） | Android端末の「タイムライン」アプリ内エクスポート機能 | ルートオブジェクトの`semanticSegments` |
| 端末内Timeline（iOS） | iOS版Googleマップ等のタイムラインエクスポート機能 | ルートが配列 |
| Takeout Semantic Location History | Google Takeout（`ロケーション履歴（タイムライン）` > `Semantic Location History/`配下） | ルートオブジェクトの`timelineObjects` |
| Takeout Records | Google Takeout（`ロケーション履歴（タイムライン）` > `Records.json`、生GPS記録） | ルートオブジェクトの`locations` |

エクスポート方法: Googleアカウントの[Google Takeout](https://takeout.google.com/)、または端末の「設定」アプリ内タイムライン機能からエクスポートする（具体的な手順・提供状況はOS・アプリのバージョンにより変わるため、Google公式のヘルプページを参照すること）。Takeoutのzipをそのまま（展開せずに）インポート画面で選択できる。zip内に`Semantic Location History/`と`Records.json`の両方が含まれる場合、由来の異なる点の混在を避けるため`Records.json`側は読み込まない（`Records.json`単体のエクスポートの場合はそのまま読み込む）。

## 大規模データ（560日規模）への対応

ユーザーが実際に560日分（130万行超）のTimeline JSONをインポートした際にアプリが停止する不具合報告を受け、docs/decisions.md D-017の15ステップ計画（S0〜S15）で根治・改善した。実装済みの主な内容:

- **ANR根治**: `Simplifier`の時間ガード処理の計算量退化を解消し、重い処理（簡略化・タイムライン構築）をUIスレッドから`Dispatchers.Default`へ退避。
- **大規模データ向けアーキテクチャ**: 全期間を俯瞰する軽量点列`RouteOverview`（日ごとに小予算のDouglas-Peucker、既定128点/日）と、再生位置近傍のみ全解像度で遅延ロードする`DetailWindow`の二層構成。
- **インポート体験**: 進捗表示（点数・日付範囲）、メモリ使用量の削減（`largeHeap`・不要コピー省略）。
- **期間・再生**: 全期間を表す`PeriodType.ALL`、再生時間の選択肢を30/60/120/180/300秒（既定60秒）へ拡大、関心度モデル（滞在・移動の指数飽和）の改善。
- **ルート描画**: 長時間欠損でのポリライン分断、過去/直近区間の描き分け。
- **動画のカメラ制御**: `CameraDirector`が再生時間を一定間隔でキーフレーム化し、画面再生は`MapLibreMap`への直接カメラ移動、動画書き出しは`MapSnapshotter`によるキーフレームごとのスナップショット＋クロスフェードで自動追従・自動ズームを実現。

560日規模相当（130万点超）の合成データによる通しのベンチマーク結果（JVM単体テスト環境、実機ではない）はdocs/progress.mdの「T-026」エントリを参照。実機・エミュレータが本開発環境に無いため、実際のANR解消・動画の見た目・体感速度は依然として未検証（下記「既知の制約」参照）。

## リリース署名

```
./gradlew assembleRelease
```

は署名設定（`signingConfig`）が未設定のため、`app/build/outputs/apk/release/app-release-unsigned.apk`（R8による難読化・圧縮済みだが**未署名**）を生成する。未署名のAPKはAndroidにインストールできないため、配布・実機インストールには以下の手順で署名する必要がある。

1. keystoreを作成する（初回のみ。生成される`.jks`/`.keystore`ファイルは**リポジトリにコミットしない**こと）。

   ```
   keytool -genkeypair -v -keystore release.keystore -alias timeliner \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. `app/build.gradle.kts`の`android { }`ブロックに`signingConfigs`を追加し、`buildTypes.release`から参照する。keystoreのパス・パスワードはリポジトリに直接書かず、環境変数や`local.properties`（`.gitignore`済み）等から読み込む形にすること。

   ```kotlin
   android {
       signingConfigs {
           create("release") {
               storeFile = file(System.getenv("TIMELINER_KEYSTORE_PATH") ?: "release.keystore")
               storePassword = System.getenv("TIMELINER_KEYSTORE_PASSWORD")
               keyAlias = "timeliner"
               keyPassword = System.getenv("TIMELINER_KEY_PASSWORD")
           }
       }
       buildTypes {
           release {
               signingConfig = signingConfigs.getByName("release")
               // ...(既存のisMinifyEnabled/proguardFiles等はそのまま)
           }
       }
   }
   ```

3. 環境変数を設定した上で`./gradlew assembleRelease`を実行すると、署名済みの`app-release.apk`が生成される。

## 既知の制約

- **実機/エミュレータでの動作確認は本開発環境では未実施**。サンドボックス環境にAndroid実機・エミュレータが無いため、地図タイルの実描画・ルート線の座標ズレ・アニメーションの滑らかさ・動画書き出し結果の実際の再生・MediaStoreへの登録・SQLite書き込みの実測性能等は、コードレビューとJVM単体テスト（`android.*`APIに依存しない純Kotlinロジックのみ）、Media3等ライブラリの公開ソースコード調査（docs/decisions.md D-009）による検証に留まる（docs/decisions.md D-003以降で継続して記録）。560日規模の実データでANRが実際に解消したかどうか自体も含め、視覚的・体感的な検証はできていない。
- **実際のGoogle Takeoutエクスポートファイルでの検証は未実施**。個人の位置情報という機微データの提供をユーザーへ要求しなかったため（docs/decisions.md D-002）、パーサは公開されている形式ドキュメントに基づく合成フィクスチャで検証している。未知キーの無視・セグメント単位の部分失敗許容という防御的設計で実データとのスキーマ差分に備えているが、実データでの動作は保証されない。
- 大量位置情報（数十万点規模、560日規模で130万点超）でのパイプライン処理速度は合成データでの計測に基づく（docs/progress.md T-009・T-026参照）。実機でのUI体感速度（地図描画のフレームレート等）そのものは未確認。
- 関心度モデルの係数（`movementSaturationMeters`等）・カメラ演出の間隔等は実データでのチューニングができていない既定値のまま（docs/decisions.md D-017/D-025）。
- そのほかの既知の制約・見送り事項はdocs/decisions.md・docs/tasks.mdのバックログを参照。

## 構成

- AGENTS.md
  - 開発方針・設計原則・ワークフロー（全AIエージェント共通、最優先で読む）

- REVIEW.md
  - レビュー方針（敵対的検証 / Adversarial Review）。reviewer Agentが従う

- CLAUDE.md
  - Claude Code固有の設定・運用ルール（AGENTS.mdをimportする）

- .claude/agents
  - planner / researcher / developer / reviewer

- .claude/settings.json
  - SessionStart / PreCompact / PostToolUse / SubagentStop / SessionEnd Hook（セッション継続性・ドキュメント品質の補助）、subagentStatusLine（サブエージェント進捗の可視化）。詳細はdocs/agents.md

- .claude/bootstrap.sh
  - Optional Dependency（Capability Layer）の導入状況を案内のみで表示する検出スクリプト。インストールは行わない

- .claude/commands/init-project.md
  - `/init-project`コマンド。新規プロジェクトでの初期化手順を実行する（このリポジトリでは実行済み）

- docs
  - tasks.md: タスクと状態管理
  - progress.md: 作業履歴
  - decisions.md: 設計判断の記録
  - agents.md: Agent構成・モデル構成・Hook/Status Line構成の詳細
  - agent-reach.md: [Agent-Reach](https://github.com/Panniantong/Agent-Reach) 対応（Optional Dependency、検出・フォールバック方針）
  - code-review-graph.md: [Code Review Graph](https://github.com/tirth8205/code-review-graph) 対応（Optional Dependency、影響範囲解析）
  - context7.md: [Context7](https://github.com/upstash/context7) 対応（Optional Dependency、ライブラリドキュメント確認）
  - capability-layer.md: 外部ツール検出の共通規約（Capability Layer）
  - research-workflow.md: 外部調査ワークフロー
  - status-line.md: サブエージェント進捗の可視化（Status Line）の仕様

アプリ本体（`app/src/main/java/com/nagamaki0311/timeliner/`）のソースコード構成:

- `data/parser`: タイムラインJSON（4形式）のストリーミングパース（`TimelineJsonParser`等）
- `model`: 共通中間モデル（`RawTrack`/`TimelineSegment`/`Period`等）
- `process`: GPSノイズ除去・ルート簡略化（`TrackCleaner`/`Simplifier`/`Mercator`）
- `store`: 永続化（`TimelineDb`/`TimelineRepository`/`PointBlobCodec`、素のSQLite）
- `playback`: 再生速度制御（`PlaybackTimeline`/`PlaybackController`）
- `render`: 地図オーバーレイ・画面/動画共通描画（`RouteOverlayView`/`RouteFrameRenderer`/`ScreenProjection`）
- `export`: 動画書き出し（`VideoExporter`/`RouteBitmapOverlay`/`VideoOutput`、Media3 Transformer）
- `ui`: Compose画面（`ImportScreen`/`TimelineScreen`/`PlaybackControls`/`ExportDialog`等）、`TimelineViewModel`

各パッケージの詳細な設計判断はdocs/decisions.mdを参照。
