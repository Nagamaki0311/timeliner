# 設計判断記録 (ADR)

設計判断、採用理由、変更履歴を記録する。新しいエントリは末尾に追加する（古い順）。

## 記録フォーマット

```
## D-XXX: タイトル

- 日付: YYYY-MM-DD
- 状態: 採用 / 却下 / 廃止（廃止の場合は後継のDを記載）

### 背景
- なぜこの判断が必要になったか

### 決定
- 何を決定したか

### 理由
- なぜその選択をしたか（検討した代替案があれば併記）

### 影響
- この決定が及ぼす影響、制約
```

---

## D-001: project001テンプレートからtimelinerアプリリポジトリへ初期化する

- 日付: 2026-08-19
- 状態: 採用

### 背景
- 本リポジトリ（Nagamaki0311/timeliner）はproject001テンプレートから作成されたが、README.mdの「新規プロジェクトでの初期化」手順（docs/tasks.md・docs/progress.md・docs/decisions.md・README.mdのリセット）が未実施のままだった。
- project001自体の運用ルール（README.md）は「個別アプリの実装は、このテンプレートから作成した別リポジトリで行う。project001自体には追加しない」と明記しているが、本リポジトリ名（timeliner）とタスクで依頼された内容（Androidタイムライン可視化アプリ「timeliner」の新規開発）が一致しており、本リポジトリがその「別リポジトリ」に相当することが明らかだった。

### 決定
- README.mdの初期化手順に従い、docs/tasks.md（T-001〜T-024行・バックログを削除、表構造は維持）、docs/progress.md・docs/decisions.md（「## 記録フォーマット」直後の区切り線より下を削除）、README.md（プロジェクト名・概要をtimelinerアプリ向けに書き換え、初期化手順の節を削除）をリセットした。
- AGENTS.md・CLAUDE.md・REVIEW.md・.claude/配下（Agent定義・Hook・bootstrap.sh）・docs/agents.md等の運用ルールは変更しない。これらは開発プロセス自体の定義であり、以降のAndroidアプリ開発でもそのまま適用する。

### 理由
- ユーザーへの追加確認より、リポジトリ名とタスク内容の一致という一次情報から意図が十分に読み取れると判断した（Auto Mode方針）。
- project001自身の構築履歴（T-001〜T-024等）をtimelinerのdocs/に残すと、今後のタスク管理・SessionStart Hookの表示がテンプレート開発の文脈と混在し、走査性・トークン効率の両面で不利益が大きい。

### 影響
- 今後のタスク管理（T-001〜）はtimelinerアプリの開発に関するもののみをdocs/tasks.mdへ記録する。
- project001テンプレート自体の設計判断（D-001〜D-023、リセット前のdocs/decisions.md）は本リポジトリのgit履歴（このコミット以前）から参照可能だが、以降のdocs/decisions.mdには複製しない。

---

## D-002: timelinerアプリの技術選定と、Plannerが提示した要確認事項への回答

- 日付: 2026-08-19
- 状態: 採用

### 背景
- Plannerがタイムライン可視化アプリの実装計画を作成した。地図ライブラリ・動画書き出し方式・JSON対応形式・永続化方式・再生速度モデル等の技術選定に加え、7件の要確認事項が提示された。Auto Mode方針（ユーザーが不在でも合理的な判断で進める）に従い、Managerが即答できるものはここで決定し実装を進める。

### 決定（技術選定、Plannerの推奨をそのまま採用）
- 言語/UI: Kotlin + Jetpack Compose（地図は`AndroidView`でラップ）。状態管理はViewModel + StateFlow、DIライブラリは導入しない。
- 地図: MapLibre Native Android + OpenFreeMapのベクタータイル（APIキー不要、osmdroidは保守停止のため不採用、Google Maps SDKは動画書き出し要件と利用規約が衝突するため不採用）。回転・チルトは無効化する。
- JSON: `android.util.JsonReader`によるストリーミングパースで4形式（端末内Timeline Android/iOS、Takeout Semantic Location History、Takeout Records）を吸収し、共通中間モデルへ正規化する。Kotlinx Serialization/Moshi等の追加依存は導入しない。
- ノイズ除去・簡略化: 正規化→速度スパイク除去→停留ジッタ抑制→時間ガード付きDouglas-Peucker（明示スタック実装）→長期間欠損の分断、の4〜5段パイプライン。
- 永続化: Room等は使わず素の`SQLiteOpenHelper`、1日1行のBLOB格納。
- 動画書き出し: AndroidX Media3 Transformer（`BitmapOverlay`で地図スナップショット静止画に毎フレームの進捗・現在地・日時を重ねる）。手動MediaCodec+EGL実装は行わない。
- minSdk 29（Android 10）。ストレージ権限分岐（API 28以下向け`WRITE_EXTERNAL_STORAGE`）を実装しない。
- 再生速度: データ時刻↔再生時刻の単調写像を画面再生・動画書き出しで共有する。既定は関心度（滞在は圧縮、移動は自然速度）に基づく自動モード、手動固定倍率モードも用意する。

### 決定（要確認事項への回答）
1. パッケージ名: `com.nagamaki0311.timeliner`とする。
2. 実データサンプル: 本セッションではユーザーへ個人の位置情報ファイルの提供を求めない（機微データであり、Auto Mode下で要求を待つと停止するため）。公開されている形式ドキュメント（locationhistoryformat.com等）に基づく合成フィクスチャでパーサを検証し、未知キー無視・セグメント単位の部分失敗許容という防御的設計でスキーマ差分に備える。実データでの検証はREADMEに既知の制約として明記し、docs/tasks.mdのバックログに残す。
3. 自動再生速度の既定挙動: Plannerの非線形圧縮（滞在・夜間を圧縮し移動を自然速度に近づける）を既定とする。要件「冗長にならない推奨再生速度」に最も直接的に応える設計であるため。手動固定倍率モードも併設し、ユーザーが希望すれど厳密な等倍速も選べるようにする。
4. 地図のオフライン動作: 必須としない。要件の「端末内で完結」は位置情報データ自体の取り扱い（外部サーバーへアップロードしない）についての要件と解釈し、地図タイルの表示は既存の要件（実際の地理情報の利用、重量級ライブラリ回避）を踏まえてネットワーク経由のベクタータイルとする。
5. minSdk 29で確定する。
6. 動画の既定仕様: 固定のアスペクト比を決め打ちせず、書き出し時点で画面に表示されている地図ビューのアスペクト比をそのまま使う（短辺1080pxを上限に解像度を決定）。プレビューと書き出し結果の見た目を一致させる設計方針（RouteFrameRendererの共通化）と整合する。fpsは30、既定の目標再生時間は30秒（10/30/60/120秒から選択可）、音声トラックなし。
7. Records.json（生GPS）・`rawSignals`への対応: v1のスコープ外とする。YAGNI（判定ラダー1）に従い、まず主要形式（セマンティックタイムライン系）で完了条件を満たすことを優先する。将来必要になった時点でdocs/tasks.mdのバックログへ追加する。

### 理由
- いずれも実装の細部であり、仕様の大枠（要件定義書）を変更するものではないため、Auto Mode方針に沿ってManagerが判断して進める方が、確認待ちで開発を止めるより合理的と判断した。
- 判断の根拠は各項目に明記し、後で誤りが判明した場合に該当個所だけを見直せるようにした。

### 影響
- 以降のdeveloperタスクは本D-002の決定を前提に実装する。
- 実データ未検証（項目2）は既知のリスクとして残る。ユーザーが実データでの検証を望む場合、後日サンプル提供を受けて追加のdeveloper/reviewerサイクルを回す。

---

## D-003: `android.util.JsonReader`はプレーンなJVM単体テスト（`app/src/test`）から実行できない（実測確認）

- 日付: 2026-08-19
- 状態: 採用

### 背景
- T-003のタスク指示には「`JsonReader`を使う走査部分はAndroid API依存のためJVM単体テストの対象外でよい（Robolectric等の追加依存は導入しない）」という記述と、「`TimelineJsonParserTest.kt`...`JsonReader`はJVM単体テストから直接使えるので実際にパーサを通してテストできる」という記述が併記されており、内容が矛盾していた。
- 実装前に最小の再現テスト（`JsonReader(StringReader(...))`で単純なJSONを読むだけのテスト）を`./gradlew testDebugUnitTest`で実行して確認したところ、`java.lang.RuntimeException: Method beginObject in android.util.JsonReader not mocked.`で失敗した。これはAGP（Android Gradle Plugin）がローカル単体テスト用に生成する「モック化android.jar」が、`isReturnDefaultValues`未設定（既定のfalse）の場合、android.*のAPI呼び出しをすべて例外送出に置き換えるという既知の仕組みによるもので、対象クラスの実装が純粋なJavaコードであるか（`android.util.JsonReader`自体はネイティブ依存のない自己完結したクラス）どうかに関わらず一律に適用される。Robolectric（対象クラスを実装ごと差し替えるフレームワーク）以外にこれを回避する設定オプションは存在しない。

### 決定
- `TimelineJsonParser.kt`本体（4形式のルート判別・セグメント/E7/geo:/度記号パースの分岐ロジック）は、指示通り`android.util.JsonReader`によるストリーミング実装のまま維持する（実機・実際のアプリ実行では正しく動作する。Robolectricは導入しない）。
- JVM単体テスト（`app/src/test`）では、`android.util.JsonReader`に依存しない部分のみを検証する: 純Kotlinの`CoordinateParsing.kt`・`TimestampParsing.kt`（座標・時刻パース）と、`TimelineJsonParser.kt`内の`isTargetZipEntry`（zipエントリのパス判定、純Kotlin文字列処理）。
- `TimelineJsonParserTest.kt`は作成するが、上記の制約をコメントで明記した上で`isTargetZipEntry`のみを検証する内容とする。4形式（A〜D）の合成JSONを実際に`TimelineJsonParser.parseJson`へ通して点数・座標・時刻を検証する自動テストは、この制約により今回追加できていない。

### 理由
- 判定ラダー3（標準ライブラリ・既存の成熟した外部ライブラリで足りるか）に照らすと、`android.util.JsonReader`は本番動作としては標準ライブラリで十分足りており、問題はテストツール側の制約でしかない。この制約を回避するためだけに独自のJSON字句解析器を再実装する案も検討したが、本番実装（`android.util.JsonReader`）とテスト専用実装という2つの独立したJSONパーサを維持することになり、挙動の乖離リスクと保守コストが利益を上回ると判断し却下した（AGENTS.md「責務は分離するがファイルは増やさない」「投機的な抽象化は追加しない」に反する）。
- Robolectric導入はタスク指示で明示的に禁止されている。

### 影響
- 4形式（端末内Timeline Android/iOS、Takeout Semantic Location History、Takeout Records）それぞれのパース分岐ロジックの正しさは、コードレビューによる確認と手動でのフィクスチャ照合に留まり、自動テストによる継続的な保証がない状態が残る（D-002項目2の「実データ未検証」とは別の、テスト自動化に関する既知のリスクとして追加で記録する）。
- 将来、実機/エミュレータが利用可能になった場合はandroidTest（instrumented test）としてこの部分を検証することが望ましい。Robolectric導入の是非を再検討する場合は、その時点で改めてユーザー確認を取ること。

