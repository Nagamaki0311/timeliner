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
- 本Dは後継のD-004により対応方針が更新された（このDの背景・経緯自体は削除しない）。

---

## D-004: T-003レビュー指摘への対応方針（Gson JsonReaderへの切替、null耐性、Records.jsonの位置づけ）

- 日付: 2026-08-19
- 状態: 採用

### 背景
- ReviewerによるT-003の敵対的レビューで3件の問題が確認された。(1) High/CONFIRMED: `parseZip`がzipエントリ順（時系列と一致しない）で点を単純追記するのみで、時刻ソート・複数データ源の統合方針が無い。典型的なTakeoutエクスポートは`Records.json`（生GPS）と`Semantic Location History/`（Googleが導出した代表点）を同一zipに同梱するため、由来の異なる点が無区別に混在しうる。(2) High/CONFIRMED: `next*()`呼び出し前に`JsonToken.NULL`を判定する処理が一切なく、1フィールドの明示的`null`が`IllegalStateException`としてファイル全体（zipなら以降の全エントリも含む）のパース失敗に伝播する。D-002が明記した「セグメント単位の部分失敗許容」という設計方針に反する。(3) Medium/CONFIRMED: D-002項目7は「Records.json(生GPS)はv1スコープ外」と決定していたが、Manager（このセッション）がT-003のタスク指示を書く際に形式D（Records.json）を対象に含めてしまい、実装・テストも完了済みという状態になっていた。docs/tasks.mdのバックログには矛盾して「未着手」の行が残っていた。
- 加えてD-003で「解消不能」としていた自動テストの欠如について、Reviewerが独立に検証し、`android.util.JsonReader`と`com.google.gson.stream.JsonReader`が呼び出しているAPI（メソッド・JsonToken列挙値）が完全に一致すること（前者は後者を直接フォークしたクラス）、Gson本体に実行時の追加依存が無いこと（単一jar約290KB）を確認した。import差し替えのみで本番と同一コードをプレーンなJVM単体テストから実行できる。

### 決定
1. **Records.json（形式D）はv1スコープに含める**（D-002項目7を上書きする）。既に実装・単体テスト済みであり、動作するコードを「決定と矛盾するから」という理由だけで削除しない。ただし、同一zip内に`Semantic Location History/`が存在する場合、`Records.json`は**インポート対象から除外する**（後述の決定4）。`Records.json`単体のエクスポート（Semantic Location Historyを含まないTakeout）を読み込む場合にのみ形式Dとして処理する。docs/tasks.mdのバックログから該当行を削除する。
2. `TimelineJsonParser.kt`のimportを`android.util.JsonReader`/`android.util.JsonToken`から`com.google.gson.stream.JsonReader`/`com.google.gson.stream.JsonToken`へ切り替える。`app/build.gradle.kts`に`com.google.gson:gson`（version catalog経由）を追加する。呼び出し箇所（メソッド名・シグネチャ）は変更不要。
3. 各フィールド読み取り箇所で`reader.peek() == JsonToken.NULL`を判定し`nextNull()`でスキップしてnull値として扱う共通ヘルパー（例: `readNullableString`/`readNullableLong`/`readNullableDouble`）を導入する。加えて、各セグメント/レコード単位の処理（`parseDeviceTimelineSegment`/`parsePlaceVisit`/`parseActivitySegment`/`parseRecordsArray`の要素ループ内側等）を`try/catch`で囲み、想定外の型不一致等が発生した要素はスキップしてログに残し、パース全体は継続する。
4. `parseZip`がzip内のエントリ種別（`Semantic Location History/`系か`Records.json`か）を認識し、前者が1件以上存在する場合は後者を読み飛ばす。加えて、`RawTrackBuilder.build()`（または`parseZip`の最終段）で全点を時刻昇順に安定ソートする（zip格納順・複数月ファイルの結合順が時系列と一致しない問題への対処。単一形式・単一エントリの場合も安全側として常に適用する）。
5. Gson導入後、`TimelineJsonParserTest.kt`に形式A〜D各1件の合成JSONを実際に`parseJson`/`parseZip`へ通す統合テストを追加し、D-003が「今回追加できていない」としていたコアパースロジックの自動検証を行う。あわせて本Dの決定3（null耐性）・決定4（ソート・Records.json除外）を検証するテストケースも追加する。

### 理由
- Records.jsonを削除するより「同一zip内でSemantic Location Historyと重複する場合のみ除外する」方が、動くコードを活かしつつ指摘1（データ源混在）の実害を解消できる最小の変更である。Records.json単体エクスポートというありうる入力（ユーザーがTakeoutで生GPSのみを選択した場合）を切り捨てない。
- Gsonへの切替はAGENTS.mdの判定ラダー3（既存の成熟した外部ライブラリで足りるか）に合致し、D-002が避けたかった「ドキュメント全体をメモリに載せる高水準シリアライズライブラリ（Kotlinx Serialization/Moshi）」とは性質が異なる、同一設計思想のストリーミングAPIへの単純な実装差し替えである。
- null耐性は要件で明示された「データ損失を防ぐエラーハンドリング」（AGENTS.md原則8）そのものであり、実データでの信頼性に直結するため必須修正とする。

### 影響
- D-003が「解消不能」としていた自動テストの欠如は、本D-004の実施後に解消される想定。D-003の記述自体は経緯の記録として削除しない。
- 以降のdeveloperタスクは、`TimelineJsonParser.kt`のnull安全化・zip内ソースの優先順位付け・ソートを本Dの決定に従って実装する。

---

## D-005: TrackCleanerの実距離判定にHaversineを使う（Mercator投影距離との用途分離）

- 日付: 2026-08-19
- 状態: 採用

### 背景
- T-004（GPSノイズ除去・ルート簡略化）のレビューで、Reviewerが`Mercator.distanceMeters`（Webメルカトル投影後のユークリッド距離）を`TrackCleaner`の速度スパイク除去・停留ジッタ抑制の閾値判定（km/h・メートルという実世界の物理量との比較）にそのまま使っていることをHigh/CONFIRMEDとして検出した。Webメルカトルは`1/cos(緯度)`でスケールが歪むため、赤道以外では投影距離と実距離が乖離する（東京駅↔新宿駅で約23%過大、北緯60度では約2倍）。日本を含む大半のユーザー地域で常時発生する問題であり、ノイズ除去の判定基準が緯度依存で変動してしまう。

### 決定
- `Mercator.kt`に、2点の緯度経度から大圏距離（Haversine公式）を直接計算する関数（例: `haversineDistanceMeters`）を追加する。
- `TrackCleaner.kt`の`removeSpeedSpikes`・`suppressStationaryJitter`（実世界のkm/h・メートルと比較する箇所）はHaversine版に置き換える。
- `Simplifier.kt`のDouglas-Peucker（投影平面上での垂線距離によるepsilon比較、幾何学的な簡略化が目的でありMapLibreの描画自体もWebメルカトルベース）は、既存のMercator投影距離のままでよい（用途が異なるため置き換えの必要はない）。ただし、`Mercator.distanceMeters`という関数名が「実距離」であるかのように誤読されやすいため、コメントまたは命名で「投影空間内の距離であり実世界の距離ではない」ことを明示する。
- あわせてReviewerが指摘したLow（`Simplifier.kt`のコメントが実際には存在しないdocs/tasks.mdの文言をあたかも引用のように記載していた誤記）を修正する。
- Reviewerが指摘した残り2件のLow/Nit（`removeSpeedSpikes`が点列の先頭・末尾を判定対象外とする構造的な限界、JSON全体が`null`一つだけの場合のクラッシュ）は、実運用での発生可能性が低い・後続段（停留ジッタ抑制や簡略化）で一定緩和される既知の限界として、今回は対応せずdocs/tasks.mdのバックログへ記録するに留める。

### 理由
- ノイズ除去の閾値は要件（GPSの揺れ・大量データでルート表示が不自然にならないこと）に直結する中核ロジックであり、緯度依存で判定基準が変動する不具合は看過できない（AGENTS.md原則8「手を抜かない対象」の「問題の理解」「データ損失を防ぐエラーハンドリング」に該当）。
- Douglas-Peuckerの投影距離は「地図上でどれだけ視覚的にズレるか」を測る幾何学的な指標として妥当であり、Haversineに統一する必要はない。用途ごとに適切な距離関数を使い分ける。
- 残り2件のLow/Nitは、Reviewer自身も「実運用上の発生可能性は極めて低い」と評価しており、AGENTS.mdの判定ラダー1（YAGNI）・REVIEW.mdの過剰指摘抑制ルールに照らし、今は対応を見送る。

### 影響
- 以降のdeveloperタスクは、実世界の物理量と比較する箇所ではHaversine、幾何学的な簡略化ではMercator投影距離、という使い分けを踏襲する。
- T-006（地図表示）以降で新たに実距離が必要になった場合も、Haversine版を再利用する。

---

## D-006: T-005レビュー指摘への対応方針（days上書きの警告、SQLite変数上限、CancellationException）

- 日付: 2026-08-19
- 状態: 採用

### 背景
- T-005（永続化とインポート導線）のレビューで、ReviewerがMedium 1件・Low 3件・Nit 1件を検出した。
  1. Medium/CONFIRMED: `days`テーブルが`date`のPRIMARY KEYで`INSERT OR REPLACE`（1日分の点列をまるごと置き換え）される設計になっているが、`segments`側は同じ上書き特性がdocs/progress.mdに明記されているのに対し、`days`（実際のGPS点列本体、アプリの主目的データ）側は未文書化・UI上の警告もない。同じ日を部分的にカバーする別ファイルを続けて取り込む等で、旧点列が復元不能な形で無警告に消えうる。
  2. Low/PLAUSIBLE: `segments`削除の`IN`句がインポート対象日付数分の`?`プレースホルダを生成するが、Android標準SQLiteの`SQLITE_MAX_VARIABLE_NUMBER=999`を超えると（1回のインポートが約2.7年超に及ぶ場合）例外でトランザクション全体が失敗しうる。
  3. Low/CONFIRMED: `TimelineViewModel`が`runCatching`で例外を捕捉しているが、`CancellationException`も無差別に`Result.failure`として扱ってしまい、構造化並行性のキャンセル伝播を壊すアンチパターンになっている。
  4. Low/Nit/CONFIRMED: 日付をまたぐ区間の移動距離がどちらの日の`distance_meters`にも計上されない（点データ自体は正しく格納される、統計表示のみの過小評価）。
  5. Nit/PLAUSIBLE: 点・セグメントとも0件のインポートが無警告で「成功」表示される。

### 決定
- 1・2・3は本D-006に基づき修正する（T-005bとして起票）。
  1. `TimelineRepository.importTrack`が、書き込み対象の日付のうち既存`days`行を持つものを検出し、呼び出し元（`TimelineViewModel`）へ「上書きされる日数」を返せるようにする。`ImportScreen`は、上書きが発生する場合はインポート実行前に確認ダイアログ（「N日分の既存データを置き換えます」）を表示し、ユーザーの明示的な確認を経てから実行する。
  2. `segments`削除の`IN`句を、日付集合を900件程度ずつのチャンクに分割して複数回`delete`を発行する形に修正する。
  3. `runCatching`を`try/catch`に置き換え、`CancellationException`は再送出（`throw`）し、それ以外の`Exception`のみ`ImportUiState.Error`へ変換する。
- 4・5は実害が軽微（統計表示の誤差、機能的な破綻なし）と判断し、今回は対応せずdocs/tasks.mdのバックログへ記録するに留める。

### 理由
- 1はAGENTS.md原則8「データ損失を防ぐエラーハンドリング」に直接該当し、アプリの主目的データ（GPS軌跡そのもの）が対象であるため必須修正とする。単なるドキュメント追記ではなく、ユーザーが実際に気づける確認ダイアログとする方が「データ損失を防ぐ」という原則の趣旨に沿う。
- 2は複数年分をまとめてインポートする典型的なTakeout一括エクスポートで実際に発生しうる（要件「大量の位置情報を扱っても...動作が重くならない」の裏側にある正当な入力サイズ）ため、Lowではあるが修正コストが低く、後回しにする理由がない。
- 3はKotlin公式ガイドラインが明示的に警告するアンチパターンであり、修正コストが1箇所の書き換えで済むため、今のうちに直す。
- 4・5はREVIEW.mdの過剰指摘抑制ルール（正確性・要件充足・セキュリティ・データ整合性に影響しないものはLow/Nitに分類し必須修正としない）に該当する。4は点データ自体に影響しない統計表示のみ、5はユーザーが結果画面で0件と気づける（意図的な機能欠落ではない）。

### 影響
- 以降、`TimelineRepository`の書き込みAPIは「上書き対象の検出」を呼び出し元へ返す設計になる。T-006以降でインポート導線に手を加える場合はこの契約を維持する。

---

## D-007: T-006レビュー指摘への対応方針（座標変換のスケール誤り、UIスレッドでのDP同期実行、Paint/Pathの再利用）

- 日付: 2026-08-19
- 状態: 採用

### 背景
- T-006（地図上のルート表示＋期間指定）のレビューで、Reviewerが3件の重要な問題を検出した。
  1. High/PLAUSIBLE: `RouteOverlayView`がワールド座標→画面座標変換に使う`metersPerPixel`を`MapLibreMap.projection.getMetersPerPixelAtLatitude(target.latitude)`から取得しているが、この値は緯度に応じて`cos(緯度)`倍で変動する「実世界距離」基準のスケールである。一方`Mercator.longitudeToX/latitudeToY`が返すワールド座標はWebメルカトル**投影**座標（緯度に依存しない一定スケールでMapLibre自身が描画に使う空間）であり、両者を単純に組み合わせると赤道以外（日本を含む）でルート線が実際の地図・GPS軌跡から系統的にズレる（例: 北緯35.68度で約1.23倍）。地図上へのルート表示という要件の中核が満たされない。
  2. High/CONFIRMED: `Simplifier.simplify`（Douglas-Peucker簡略化）が`maxPointCount`引数なし（無制限）で、`OnCameraMoveListener`のコールバック内、すなわちUIスレッド上で同期的に呼ばれている。期間全体の未簡略化点列（数万〜十万点規模になりうる）に対し、カメラのease中・ピンチズーム中に何度も再実行されうり、ANR・カクつきのリスクがある。
  3. Medium/CONFIRMED: `RouteFrameRenderer`の`drawRoute`/`drawMarker`/`drawDateTimeText`/`drawAttribution`が呼び出しのたびに新しい`Paint`・`Path`オブジェクトを生成しており、`onDraw`のたびにアロケーションが発生する（Android Lintの`DrawAllocation`が警告する典型的アンチパターン）。T-008（動画書き出し）で同じ`RouteFrameRenderer`を数百〜数千フレーム分連続描画する設計であるため、その際に問題が深刻化する。
- 加えてLow 2件（週ラベルの年またぎテスト未追加、fitBoundsが実質同一地点の複数点で極端ズームになりうる可能性）を検出したが、影響が軽微なためバックログへ記録するに留める。

### 決定
1. `RouteOverlayView`の`metersPerPixel`計算を、緯度に依存しない投影空間のスケールへ修正する。最小修正として`getMetersPerPixelAtLatitude(0.0)`（赤道固定、`cos(0)=1`により投影メートル/ピクセルと一致する）を使う。
2. `RouteOverlayView`の`Simplifier.simplify`呼び出しに妥当な`maxPointCount`（画面幅ピクセル数のオーダー、例: 2000〜4000点程度）を渡し、無制限の入力サイズでの実行を防ぐ。DP自体をUIスレッド外（`Dispatchers.Default`のコルーチン等）へ逃がす非同期化は、実装コストと必要性を見て可能なら行うが、必須はmaxPointCount指定とする。
3. `RouteFrameRenderer`が使う`Paint`・`Path`オブジェクトを呼び出しのたびに生成せず、キャッシュ（`Style`データクラスに`by lazy`で持たせる、またはインスタンス変数として使い回す）する設計に変更する。
4. Low 2件（週ラベルの年またぎテスト、fitBoundsの実質同一地点フォールバック）は今回対応せず、docs/tasks.mdのバックログへ記録する。

### 理由
- 1・2はいずれも要件の中核（地図上へのルート表示、大量データでも実用的な速度で動作すること）に直結し、AGENTS.md原則8「手を抜かない対象」に該当するため必須修正とする。
- 3はT-008で同じレンダラーを高頻度に再利用する設計上の前提があり、後回しにするとT-008側の実装・パフォーマンスチューニングがより困難になる（根本原因を今直す方が手戻りが少ない）。
- Low 2件はREVIEW.mdの過剰指摘抑制ルールに該当し、クラッシュ耐性は確認済み・影響が特定の稀なケースに限定されるため、今は対応を見送る。

### 影響
- 以降、地図オーバーレイの座標変換ロジックを変更する場合は「投影空間で一定スケールを使う」という前提を維持する。
- `RouteFrameRenderer`のPaint/Pathキャッシュ設計は、T-008の動画書き出し実装でもそのまま再利用する。

---

## D-008: T-007レビュー指摘への対応方針（再生中シークの競合、trimByProgressの二重計算）

- 日付: 2026-08-19
- 状態: 採用

### 背景
- T-007（アニメーション再生と速度制御）のレビューで、Reviewerが以下を検出した。
  1. Medium/PLAUSIBLE: `PlaybackController.seekTo()`が`play()`/`setRoute()`/`setSpeedMode()`と異なり再生ループを一時停止しないため、再生中にシークバーをドラッグすると、ユーザーのシーク値と16msごとに進む再生ループの自動更新値が同じ`progress`を奪い合う。
  2. Low/CONFIRMED: `RouteOverlayView.onDraw`が`currentPositionAtProgress`と`draw`内部の`drawRoute`でそれぞれ独立に`trimByProgress`（`FloatArray`確保＋`arraycopy`、最大3000点分）を計算しており、毎フレーム2回の無駄な計算が発生している。T-008で同じ`RouteFrameRenderer`のAPIを動画フレームごとに呼ぶ設計のため、この無駄はT-008にも伝播する。
  3. Low/PLAUSIBLE: 同一データ時刻に複数点（異なる位置）がある場合、`binarySearch`のタイブレークが不定で、その区間の再生中にマーカーが実際の移動と無関係に固まって見える可能性がある。
  4. Nit/CONFIRMED: `buildAuto`の`alpha`/`beta`に負値の検証が無い（現状UIから到達する経路が無く実害なし）。
  5. PLAUSIBLE（設計トレードオフ、バグではない）: 手動モード最低速度(x60)と長期間選択（月/年）の組み合わせで、再生に実時間で数日かかりうる。D-002が「ユーザーが希望すれば厳密な等倍速に近い速度も選べるように」と明記した意図的な設計範囲内。

### 決定
- 1・2はT-007bとして修正する。
  1. `PlaybackController.seekTo()`の冒頭で再生ループを停止する（`pause()`と同等の処理、`playbackJob?.cancel()`）。シーク後に再生を継続したい場合は、呼び出し元（UI）がシーク完了時に明示的に`play()`を呼ぶ設計とする（一般的な動画プレーヤーのシークバーUXに合わせる）。
  2. `RouteFrameRenderer`のAPIを、`trimByProgress`の計算結果を1回だけ行い、ルート描画と現在位置マーカーの両方で使い回す形にリファクタする（`currentPositionAtProgress`と`draw`が独立に計算する現状の重複を解消する）。
- 3・4・5は今回対応せず、docs/tasks.mdのバックログへ記録するに留める。

### 理由
- 1はユーザーが実際に触れる操作（シークバードラッグ）で発生し、UXとして明確に破綻する（値の奪い合いでスライダーが震える等）ため、要件「再生中の現在位置・日時などの表示」の一部として必須修正とする。
- 2は現状クラッシュ等の実害はないが、T-008で動画フレームごとに同じ無駄が発生する設計になっており、今のうちに直す方が手戻りが少ない（T-006bでPaint/Pathキャッシュを先んじて直した判断と同じ理由）。
- 3はGPSデータの実際の重複頻度が不明で、影響も「マーカーが一瞬固まる」程度に限定的（アプリのクラッシュやデータ破損はない）。4は現状到達不能なコードパス。REVIEW.mdの過剰指摘抑制ルールに照らし、いずれも今は対応を見送る。
- 5は意図的な設計判断（D-002）の範囲内であり対応不要。

### 影響
- 以降、`PlaybackController`のシークは「シーク時に自動停止」という前提でUIを設計する。
- `RouteFrameRenderer`のtrim計算共有化は、T-008の動画書き出し実装でもこの設計を踏襲する。

---

## D-009: T-008スパイク検証の結果（静止画+BitmapOverlayが機能することをMedia3ソースコードで確認）

- 日付: 2026-08-20
- 状態: 採用

### 背景
- T-008のタスク指示は、D-002が決めた動画書き出し方式（地図スナップショット静止画を`MediaItem`として`Transformer`へ渡し、`BitmapOverlay`の`getBitmap(presentationTimeUs)`で毎フレームの進捗・現在地・日時・地図帰属表示を重ねる）について、「静止画入力でTransformerが同一フレームを最適化・重複排除し、オーバーレイが毎フレーム更新されない可能性がある」という懸念を明記し、実装開始前に最小限のスパイク実装で確認することを求めていた。
- 本セッションの環境にはAndroid実機・エミュレータが無い（T-002以降一貫した既知の制約、docs/progress.md各エントリに記録済み）。そのため「実際にTransformerを実行して動画を書き出し目視確認する」という一般的な意味でのスパイクは実施不可能。代わりに、Maven Centralから`androidx.media3:media3-transformer:1.11.0`・`media3-effect:1.11.0`・`media3-common:1.11.0`のaarを取得し（T-002がMapLibreのaarを`javap`で逆コンパイルして確認した手法と同じ方針）、加えて`androidx/media`のGitHub公開リポジトリ（`github.com/androidx/media`、Media3の実装そのもの）から該当クラスの実際のソースコードを直接読んで検証した。

### 決定（スパイク検証で確認した事実）
1. `ImageAssetLoader.queueBitmapInternal`は、読み込んだ1枚の`Bitmap`を`sampleConsumer.queueInputBitmap(bitmap, ConstantRateTimestampIterator(durationUs, frameRate))`へ渡す。`ConstantRateTimestampIterator`は`durationUs`・`frameRate`から一定間隔の`presentationTimeUs`列（0, 1/30秒, 2/30秒, ...)を生成し、同じ`Bitmap`オブジェクトが**フレーム数分だけ**個別の`presentationTimeUs`とともにVideoFrameProcessorへキューイングされる。すなわち「静止画1枚」であっても内部的には動画のフレーム数だけ個別に処理される設計であり、そもそも1フレームに最適化・重複排除される余地がない。
2. `OverlayShaderProgram.drawFrame(inputTexId, presentationTimeUs)`は`BaseGlShaderProgram`から**キューイングされた各フレームごとに**呼ばれ、その中で`overlay.getTextureId(presentationTimeUs)`（`BitmapOverlay`実装では内部で`getBitmap(presentationTimeUs)`を呼ぶ）を毎回呼び出す。ここでの`presentationTimeUs`は決定1の個別フレームのタイムスタンプそのものであり、フレームごとに異なる値が渡る。
3. `BitmapOverlay.getTextureId(presentationTimeUs)`は`getBitmap(presentationTimeUs)`の戻り値を`bitmap != lastBitmap || generationId != lastBitmapGenerationId`で前回描画時と比較し、異なる場合のみGLテクスチャを再アップロードする。`Bitmap.getGenerationId()`のJavadocは「changes whenever the bitmap is modified」であり、`Canvas`による描画を含むBitmapの変更で自動的に更新される（Java側から明示的に通知するAPIである旧`notifyPixelsChanged()`は現行SDK（`android-36`）には存在しない。実装時にこの呼び出しを試みてコンパイルエラーで判明した）。
4. 以上（1〜3）より、`RouteBitmapOverlay.getBitmap`が毎フレーム異なる内容を描画したBitmap（同一インスタンスを使い回す場合も`Canvas`描画によりgenerationIdが変化する）を返す限り、Transformerの静止画入力パイプラインは実装計画（D-002）どおりに「毎フレーム更新されるオーバーレイ」として機能する。懸念されていた「同一フレームへの最適化・重複排除」は発生しない設計であることをソースコードレベルで確認した。

### 理由
- 実機・エミュレータでの実行確認ができない環境制約下では、タスク指示が求める「最小限のスパイク実装での確認」の代替として、Media3本体の実装ソースコードを直接読み実際の処理フロー（フレームのキューイング→シェーダー描画→オーバーレイ取得の各段でpresentationTimeUsがどう伝播するか）を追跡することが、実行して目視確認する以上に確実な検証手段だと判断した（ソースコードはビルドされたバイナリそのものの挙動を規定するため、実行結果を観察するより高い確信度が得られる）。
- D-003・T-005〜T-007bが繰り返し記録してきた「実機/エミュレータでの目視確認は環境制約により未実施」という既知の制約の範囲内で、可能な限り高い確信度の検証を行うという一貫した方針に従った。

### 影響
- D-002が決めた動画書き出し方式（静止画+BitmapOverlay）をそのまま採用し、代替方式（短尺無地動画の生成、MediaCodec+EGLでの手動フレーム描画）への切り替えは行わない。
- 実際の動画ファイルを実機で再生し、フレームごとにルート進捗・現在地マーカー・日時が正しく変化することの最終確認は、実機/エミュレータが利用可能になった時点で行うことが望ましい（D-003以来の既知の制約の一部として残る）。

---

## D-010: T-008レビュー指摘への対応方針（MediaStoreロールバック、snapshotタイムアウト、単一点ルートのエラー文言）

- 日付: 2026-08-20
- 状態: 採用

### 背景
- T-008（アニメーションの動画書き出し）のレビューで、ReviewerがMedium 3件を検出した。
  1. `VideoOutput.saveToMediaStore`が`insert`後のコピー失敗・キャンセル時に、作成済みMediaStore行をロールバック（削除）する処理を持たない。キャンセル直後にコピーが完了間際だった場合、意図に反して動画がギャラリーへ残り続ける、あるいはI/Oエラー時に`IS_PENDING=1`のまま孤立した行が残りうる。
  2. `VideoExporter.awaitSnapshot`が`MapLibreMap.snapshot()`のコールバックを無期限に待つが、`MapView`が`started`状態でない場合はコールバックが一切呼ばれない（MapLibre側の仕様、逆コンパイルで確認済み）ため、タイムアウトが無いと進捗0%のまま無期限にスタックしうる。
  3. 選択期間のルートが点1つのみの場合、`PlaybackTimeline`の総再生時間が0になり`VideoExporter.export`の`require`が投げる内部的な例外メッセージ（"durationMsは正の値である必要があります: 0"）がそのままユーザーへ表示される。

### 決定
- 3件ともT-008bとして修正する。
  1. `saveToMediaStore`全体を`try/catch`で囲み、失敗時は`resolver.delete(itemUri, null, null)`でロールバックする。呼び出し側（`TimelineViewModel.exportVideo`）でも、コピー中に`CancellationException`が発生した場合は同様に発行済みの`itemUri`を削除する。
  2. `awaitSnapshot`を`withTimeout(...)`（妥当な秒数、例えば10秒程度）で包み、タイムアウト時は`ExportUiState.Error`へ変換する。
  3. エクスポート開始前（`exportVideo`冒頭、または「動画として保存」ボタンの`enabled`条件）で選択期間のルート点数が2未満の場合を検出し、「この期間はデータが少なく動画を作成できません」等のユーザー向けメッセージを直接`ExportUiState.Error`に設定する（`VideoExporter`内部の`require`例外をそのまま露出させない）。

### 理由
- 1はAGENTS.md原則8「データ損失を防ぐエラーハンドリング」に該当し、ユーザーの意図（キャンセル）に反してファイルが残る・孤立行が溜まり続けるのは看過できない。
- 2・3はいずれも実装コストが低く（`withTimeout`1箇所、点数チェック1箇所）、ユーザー体験に直接関わるため今のうちに直す。

### 影響
- 以降、`VideoExporter`/`VideoOutput`のAPIを変更する場合も、この「失敗・キャンセル時のロールバック」「無期限待機の禁止」「内部例外をユーザー向け文言に変換する」という3方針を踏襲する。

---

## D-011: MapLibre SDKが自身のマニフェストで宣言する位置情報権限（ACCESS_FINE/COARSE_LOCATION）をマニフェストマージで除外する

- 日付: 2026-08-20
- 状態: 採用

### 背景
- T-009（仕上げ）で`aapt dump badging`により生成APKのマニフェストを確認したところ、本アプリの`AndroidManifest.xml`には一切記載していない`android.permission.ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`が最終マニフェストに含まれていることが判明した。原因を調査したところ、依存に含めているMapLibre Native Android SDK自身のマニフェストが、任意機能である現在地表示（LocationComponent、本アプリは未使用）向けにこれらの権限を宣言しており、Android Gradle Pluginのマニフェストマージによって本アプリのマニフェストへ自動的に統合されていた。
- 本アプリのコード（`app/src/main`全体）には`android.location.*`・MapLibreの`LocationComponent`・`FusedLocationProvider`等の呼び出しが一切無いことをgrepで確認済み（端末の現在地を取得する機能はそもそも存在しない。インポートしたタイムラインJSON内の過去の位置情報を可視化するのみ）。

### 決定
- `AndroidManifest.xml`に`xmlns:tools`を追加し、`<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" tools:node="remove" />`・同`ACCESS_COARSE_LOCATION`をマニフェストマージ除外として明示的に宣言する。
- `ACCESS_NETWORK_STATE`/`ACCESS_WIFI_STATE`/`WAKE_LOCK`（同じくMapLibre由来、地図タイルのネットワーク取得に関連する「normal」権限でランタイム許可プロンプトを伴わない）は変更しない。

### 理由
- 本アプリは未使用の「dangerous」権限（ランタイム許可プロンプトを伴う）をAPKへ含めるべきではない。ユーザーが実際には求められていない位置情報アクセスをインストール時のパーミッション一覧やストア掲載情報で目にすることは、要件が明記する「位置情報データを端末内で完結させる」という設計意図（docs/decisions.md D-002決定4）とも整合しない不要なプライバシー面の懸念であり、AGENTS.md原則8「手を抜かない対象」のセキュリティに隣接する事項として対応した。
- 除去は`tools:node="remove"`という標準的なマニフェストマージ機構のみで完結し、アプリの挙動（地図表示・タイル取得等）に一切影響しない（実際に`aapt dump badging`で除去後もビルド成功・地図関連の`INTERNET`等の権限は保持されることを確認済み）。

### 影響
- 将来MapLibre側のLocationComponent機能（現在地の青い点表示等）を使う要件が追加された場合、まずこの2行の削除（`tools:node="remove"`除去）とランタイム許可リクエストの実装が必要になる。
- 実機/エミュレータでの動作確認は本開発環境では未実施のため、除去後も地図タイル取得・地図表示自体に影響が無いことは`aapt dump badging`によるマニフェスト確認・ビルド成功の確認に留まる（D-003以来の既知の制約）。

