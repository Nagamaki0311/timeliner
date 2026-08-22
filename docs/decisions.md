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

---

## D-012: edge-to-edge表示で画面端のUI要素がシステムバーに隠れる不具合への対応（システムバーごとに個別のwindowInsetsPaddingを適用）

- 日付: 2026-08-21
- 状態: 採用

### 背景
- ユーザーが実機にAPKをインストールしたところ、画面上部の「地図」「インポート」タブ・日/週/月/年の期間選択タブがステータスバー（時刻・バッテリー表示）に、画面下部の「動画として保存」ボタンがナビゲーションバー（ジェスチャーバー）にそれぞれ重なって操作不能になっている、と実機スクリーンショット付きで報告された（T-010）。
- 原因を`MainActivity.kt`で調査したところ、T-002導入時から`enableEdgeToEdge()`が呼ばれておりウィンドウはシステムバーの背後まで描画される設定になっていたが、Composeレイアウト側（`MainActivity.kt`のルート`Column`/`TabRow`、`TimelineScreen.kt`、`ImportScreen.kt`）のいずれもシステムバー分の余白（`WindowInsets`）を確保していなかった。`app/build.gradle.kts`の`targetSdk = 36`（Android 15相当以降）自体はedge-to-edgeを強制する副次要因ではあるが、直接の原因は`enableEdgeToEdge()`導入時点でinset paddingの実装が漏れていたことにある。

### 決定
- 画面全体へ一律`Modifier.safeDrawingPadding()`を適用するのではなく、システムバーと直接隣接する要素にのみ個別に`Modifier.windowInsetsPadding(...)`を適用する。
  1. `MainActivity.kt`: ルート`Column`直下の`TabRow`（「地図」「インポート」タブ、画面最上部）に`Modifier.windowInsetsPadding(WindowInsets.statusBars)`を適用する。
  2. `TimelineScreen.kt`: 画面最下部の「動画として保存」`Button`に`Modifier.windowInsetsPadding(WindowInsets.navigationBars)`を適用する。
  3. `ImportScreen.kt`: ルート`Column`（他画面と異なり上下に固定バーを持たず、単一の`Column`で完結する構成）に`Modifier.windowInsetsPadding(WindowInsets.navigationBars)`を適用する（画面上部は既にMainActivity側のTabRowの下に位置するため追加対応不要）。
- `TimelineScreen.kt`の`PeriodSelector`（日/週/月/年タブ）・`MapContainer`（地図本体）・`PlaybackControls`には個別のinset paddingを追加しない。いずれもレイアウト上、常にMainActivity側のTabRow（ステータスバー余白確保済み）とTimelineScreen側のButton（ナビゲーションバー余白確保済み）に挟まれる位置にあり、システムバーと直接接することがないため。
- `ExportDialog.kt`・`ImportScreen.kt`内の上書き確認`AlertDialog`は変更しない。Compose Material3の`AlertDialog`は独自の`Window`（`Dialog`）上に表示され、既定でシステムバー背後まで描画されない（`decorFitsSystemWindows`が既定のtrueのまま）ため、ホストActivity側の`enableEdgeToEdge()`の影響を受けない。

### 理由
- MainActivity.ktの`Column`構成（TabRow→Box(weight 1f)）上、地図（`MapContainer`）は常にTabRow・PeriodSelector・PlaybackControls・Buttonに囲まれた内側にあり、画面全体へ`safeDrawingPadding()`を適用した場合と、TabRow・Buttonという「システムバーに実際に隣接する要素」にのみ適用した場合とで、地図の実際の表示面積は変わらない（Columnのweight計算上、システムバー分の余白は結局TabRow側かButton側のいずれかで一度だけ消費されるため）。後者を選んだのは、タスク指示が「タブ・ボタン等の操作可能なUI要素だけがシステムバーと重ならないようにし、地図自体は必要以上に余白で狭めすぎない」という意図を明示していたため、意図がコードからも読み取れる形（余白の発生源をシステムバーに隣接する要素へ明示的に紐付ける）を優先した。
- ImportScreenは地図のような全画面表示要素を持たない単純な`Column`構成のため、ルート`Column`へ適用すれば判定ラダー6（1行で書けるか）に合致し、過剰な粒度分割を避けられる。

### 影響
- 実機・エミュレータが無い本開発環境では、修正が実機上で正しく見た目を解消するかの目視確認はできない（D-003以来の既知の制約）。Android公式のedge-to-edge対応ドキュメント・`WindowInsets`APIの一般的な使用方法との整合性、コードレビューでの確認に留める。
- 今後、`MainActivity.kt`のタブ構造・`TimelineScreen.kt`のレイアウト構成（PeriodSelector→地図→PlaybackControls→Button）を変更する場合、システムバーに新たに隣接することになる要素へ同様の`windowInsetsPadding`適用が必要にならないか確認すること。
- 本Dの「常にシステムバーと直接接することがない」という前提は縦方向のみを検証したものであり、後継のD-013で横方向（ランドスケープ+レガシーナビゲーションバー）の見落としが判明し対応方針が追加された。

---

## D-013: T-010レビュー指摘への対応方針（ランドスケープ+レガシーナビゲーションバーでの横方向inset未対応）

- 日付: 2026-08-20
- 状態: 採用

### 背景
- T-010（edge-to-edge対応）のレビューで、ReviewerがMedium/PLAUSIBLEを検出した。D-012は「`PeriodSelector`・`PlaybackControls`は常にTabRow（ステータスバー対応済み）とButton（ナビゲーションバー対応済み）に挟まれているためシステムバーと直接接しない」という縦方向の位置関係のみを根拠にinset対応を省略していたが、この判断は横方向（左右）を検証していなかった。
- `AndroidManifest.xml`に`screenOrientation`の指定が無くコード内にも画面回転ロックが無いため、端末をランドスケープに回転できる。ランドスケープかつ2/3ボタンナビゲーション（レガシーナビゲーションバー、ジェスチャーナビゲーションでない設定）の端末では、Android標準仕様上ナビゲーションバーが画面左右いずれかの端に移動し、`WindowInsets.navigationBars`の`left`/`right`が非ゼロになる。この状態で、`PeriodSelector`の「前の期間」「次の期間」ボタン、`PlaybackControls`のシークバー・速度モード選択ボタン群はいずれも`fillMaxWidth()`で画面端に接するため、横方向のinset paddingを持たず、ナビゲーションバーの下に隠れて操作不能になりうる。ユーザーが報告した症状と同種の不具合が、別の画面向き・ナビゲーション設定の組み合わせで再現しうる状態だった。

### 決定
- `TimelineScreen.kt`の`PeriodSelector`・`PlaybackControls`を包む領域（またはそれぞれの呼び出し箇所）に、`Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))`を追加し、横方向のみのナビゲーションバーinsetを確保する（縦方向は既存のTabRow/Button側の対応と重複させない）。
- `MainActivity.kt`のTabRow（ステータスバー、常に画面上端固定）はこの横方向の懸念自体が該当しないため変更不要。`ImportScreen.kt`はルート`Column`に`WindowInsets.navigationBars`（全方向）を既に適用済みのため対応不要。

### 理由
- 縦方向のみで「常にシステムバーと直接接しない」と判断したD-012の前提が不正確だった。ユーザーが実機で踏んだ不具合と同じ種類（操作可能なUI要素がシステムバーの下に隠れる）であり、AGENTS.md原則8「手を抜かない対象」に照らし、発見できた時点で対応するのが妥当と判断した。
- 画面回転ロックを新たに追加する（不具合を回避するために機能を制限する）よりも、insetを正しく確保する方が根本的な修正になる。

### 影響
- 以降、`TimelineScreen.kt`にシステムバーと接しうる新規要素を追加する場合、縦方向だけでなく端末回転時の横方向のinsetも検討すること。

---

## D-014: T-011実機報告への対応方針（rawSignals読み飛ばし失敗によるインポート全体の失敗を修正）

- 日付: 2026-08-21
- 状態: 採用

### 背景
- ユーザーが実際の端末内Timeline(Android形式、`semanticSegments`ルート)のエクスポートファイル（130万行超）をインポートしたところ、「インポートに失敗しました: End of input at line 1325233 column 25 path $.rawSignals[12163]..[137610].」で失敗した。
- コード調査で、`TimelineJsonParser.parseRootObject`が`semanticSegments`の兄弟キー（v1スコープ外の`rawSignals`等、D-002決定7参照）を`else -> reader.skipValue()`で無条件に読み飛ばしており、この`skipValue()`が例外を投げると、既に`semanticSegments`から正常にパースできていた有効なデータ（`builder`蓄積済みの点・セグメント）ごとすべて破棄されインポート全体が失敗する設計上の欠陥を確認した。

### 決定（原因調査の結果）
1. **`skipValue()`自体の巨大配列スキップに関するバグではないことを実測で確認した**。Gson `JsonReader`（2.14.0、gsonの実ソース`JsonReader.skipValue()`をGitHubから直接確認）を用い、15万要素の合成`rawSignals`配列を含む正常な（切り詰めていない）JSONに対し`skipValue()`を実行したところ問題なく完走した（`/tmp`のスクラッチ検証、docs/には残さない一時スクリプト）。一方、同じ配列を意図的に末尾切り詰めたJSONでは、実際に`java.io.EOFException: End of input`が`skipValue()`から送出されることを確認した。すなわち`skipValue()`自体に大規模配列特有の不具合は無く、EOFExceptionは入力ストリームが構造的に完結する前に終わった場合にのみ発生する。
2. **`ImportSource.kt`のストリーム処理には人為的な打ち切り要因が無いことを確認した**。`ContentResolver.openInputStream()`が返す`InputStream`をバッファサイズ制限・タイムアウト・独自ラッピングなしでそのまま`InputStreamReader`→`JsonReader`へ渡しているのみで、`isZip()`のzipマジックナンバー確認も同一ストリームを使い回さず新規に`opener()`を呼び直しているため、状態破壊の余地も無い。したがって、ユーザーの実ファイルが本当に途中で切れていた（Google側のエクスポート処理・端末間のファイル転送・SAFプロバイダ側の大容量ファイル読み込み制限等、アプリのコード外の要因）可能性が高いと判断する。
3. **同種の設計欠陥が`parseArrayElementSafely`（`semanticSegments`/`timelineObjects`/`locations`各配列の要素単位パースで共有される関数）にも存在することを実測で追加確認した**。同関数は`JsonParser.parseReader(reader)`で1要素をJSONツリーとして丸ごと消費した後、その解釈のみをtry/catchで保護する設計（D-004決定3）だが、`JsonParser.parseReader(reader)`自体（ツリーへの変換、streaming readerでの消費）はtry/catchの外にあり、要素の消費中に元のstreaming readerが構造的に不完全な入力に到達した場合（`JsonSyntaxException`/`MalformedJsonException`）は保護されず、呼び出し元の配列走査ループを抜けて上位へ伝播することを最小の合成JSON（配列2要素、2要素目を意図的に途中で切り詰め）で確認した。これは`parseRootObject`直下の`else -> skipValue()`と同じ「巨大な未知データの読み飛ばし失敗が既存の有効データごと破棄させる」という根本原因の別の発生箇所である。
4. 加えて、実測により**リカバリ実装上重要な制約**を確認した: `skipValue()`等がEOFException/MalformedJsonExceptionを送出した後、同一`JsonReader`インスタンスに対する以降の呼び出し（`hasNext()`/`endObject()`等）はすべて同じ例外を再送出する（内部状態が破損したまま復旧しない）。したがって、例外発生後は当該`reader`に一切触れず、既に構築済みの`builder`データのみを使って即座に処理を終える必要がある。

### 決定（修正方針）
1. `TimelineJsonParser.parseRootObject`のwhileループ本体（`when`ブロック全体。`else -> skipValue()`だけでなく`semanticSegments`/`timelineObjects`/`locations`の各分岐も含む）を`try/catch (e: Exception)`で囲む。例外発生時、その時点で`format`が確定していれば（＝いずれかの主要形式から有効なデータを読み終えている）警告ログを出力し`reader`へは以降触れずその`format`を返して正常終了する。`format`が未確定であれば回復可能なデータが無いためこれまで通り再送出する。
   - `when`ブロック全体を対象にした理由: `else`分岐だけを保護すると、決定3で確認した`parseArrayElementSafely`起因の例外（`semanticSegments`等の既知キー処理中に発生する構造的パース失敗）が素通りしてしまうため。`when`全体を1箇所で保護することで、`parseArrayElementSafely`側を個別に改修せず（呼び出しの深いネストへ手を入れずに）同じ根本原因を一度に塞げる（AGENTS.md原則7「共有関数側を一度だけ直す」の精神に沿い、実際には共有の失敗点を`parseRootObject`という単一の境界に集約する設計とした）。
2. `TimelineJsonParserTest.kt`に、`semanticSegments`が正常な1件のVISITセグメントを含み、`rawSignals`配列（5000要素）が意図的に閉じ括弧なしで終わる（切り詰めを再現する）合成JSONを追加し、例外を投げずに`semanticSegments`由来の有効なデータ（点1件・セグメント1件）が正しく返ることを検証した。

### 理由
- 「アプリが一切使わない補助データ（`rawSignals`）の読み込み失敗によって、ユーザーが実際に必要とするデータ（移動ルート）まで失われる」のは、AGENTS.md原則8「データ損失を防ぐエラーハンドリング」に直接反する重大な欠陥であり、必須修正とする。
- `when`ブロック全体を保護範囲とする設計は、決定3で発見した`parseArrayElementSafely`の類似欠陥を、そちらのコードへ触れずに閉じられる最小差分であり、AGENTS.md判定ラダー7（要件を過不足なく満たす最小実装）に合致する。`parseArrayElementSafely`自体を「要素の消費失敗時に配列走査を打ち切る」設計へ改修する案も検討したが、`reader`が例外後は使用不能という決定4の制約により`parseArrayElementSafely`単体では「今読んでいる配列を安全に打ち切って正常終了する」ことができず（＝結局呼び出し元である`parseRootObject`まで「打ち切って良い」という判断を伝播させる必要がある）、`parseRootObject`側の1箇所で吸収する方が変更範囲が小さく、責務も自然（「ルート直下のどのフィールドであれ、致命的でない失敗はそこで打ち切ってこれまでの成果を返す」という一貫した境界になる）と判断した。
- `parseZip`が複数エントリを走査する際、あるエントリの`parseRoot`が`format`未確定のまま例外を投げると、そのエントリ以前に処理済みだった別エントリのデータもろとも失われる問題が残ることを認識しているが、これは「同一zip内に完全に読み取れない別形式のエントリが混在する」というより稀な複合条件であり、かつ今回報告された実際の不具合（単一の巨大`.json`ファイル）の対象外であるため、YAGNI（判定ラダー1）に従い今回は対応せずdocs/tasks.mdのバックログへ記録するに留める。

### 影響
- 以降、`TimelineJsonParser`に新たなルート直下キー・配列を追加する場合も、「主要データを読み終えた後に発生した回復不能なストリーム破損は、収集済みデータを保持したまま打ち切ってよい」という`parseRootObject`の境界設計を踏襲する。
- `parseZip`の複数エントリ間でのこの種の部分失敗保護は未対応のまま残る（上記「理由」参照、バックログに追加）。
- ユーザーの実ファイル自体は機微な個人位置情報のため入手できず、実際にファイルが途中で切れていた具体的原因（エクスポート処理側かファイル転送側か）そのものの特定はできていない。今回の修正は原因を問わず症状（有効データの巻き添え破棄）を根本から防ぐものであり、原因特定ができない場合でも有効な対処である。
- 本Dの修正は後継のD-015により不完全だったことが判明し、対応方針が追加された。

---

## D-015: T-011レビュー指摘への対応方針（保護範囲の見落とし2件、例外型の絞り込み）

- 日付: 2026-08-21
- 状態: 採用

### 背景
- T-011（D-014の修正）のレビューで、Reviewerが実際に合成JSONで再現し、T-011が解決しようとした問題（有効な既存データの巻き添え破棄）が別のトリガー箇所で再発することをHigh 2件として確認した。
  1. `format`は`when`分岐（例: `parseDeviceTimelineArray`呼び出し）が**正常に完了した後**にのみ代入されるため、その配列自身の2件目以降の要素で例外が発生した場合、1件目が既に`builder`へ追加済みでも`format`はまだ`null`のままcatchの救済条件（`format != null`）を満たさず、再送出されてデータが失われる。
  2. `while (reader.hasNext())`の条件式自体が`try`ブロックの外にあるため、あるキーの処理が成功し`format`が確定した直後の「次のキー名確認」自体が例外を投げた場合、その例外はtry/catchに一切捕捉されず伝播する。
- 加えてMedium（`catch (e: Exception)`が広すぎ、ストリーム破損由来ではない実装バグまで同じ回復パスで握りつぶしうる）とLow（境界値テストの不足）も検出された。

### 決定
1. `parseRootObject`のtry/catchの範囲を、`when`ブロックだけでなく`while (reader.hasNext())`のループ全体（条件式の評価を含む）に広げる。
2. `format`の代入を、対応するキー名が判明した時点（`when`の分岐に入った直後、配列パース呼び出しの前）に前倒しする。
3. 「回復可能かどうか」の判定条件を`format != null`から`format != null && !builder.isEmpty()`に変更する（`RawTrackBuilder`に`isEmpty()`アクセサを追加する）。これにより「キーは判明したが1件も読めなかった真の失敗」と「一部読めた部分成功」を正しく区別する。
4. `catch (e: Exception)`を、D-014の実測で確認された2系統（`java.io.IOException`とそのサブクラス、`com.google.gson.JsonSyntaxException`）に限定する。それ以外の`RuntimeException`（実装バグの可能性が高い）は握りつぶさず再送出する。
5. `TimelineJsonParserTest.kt`に、Reviewerが指摘した3種の境界値テスト（`format`未確定のまま切り詰め→再送出、主要配列自身の2件目以降で切り詰め→1件目のデータを保持して復旧、ルートオブジェクトの閉じ`}`直前＝キー境界で切り詰め→既存データを保持して復旧）を追加する。

### 理由
- 1・2はAGENTS.md原則8「データ損失を防ぐエラーハンドリング」に直接反する（T-011が解決したはずの問題そのものが再発する）ため必須修正とする。
- 3は「1件も読めなかった」場合まで誤って成功扱いにしてしまうと、従来出ていた失敗通知が失われ別の形でユーザーに不利益が生じるため、あわせて修正する。
- 4はAGENTS.md原則7（根本原因を直す）に沿い、ストリーム破損由来の例外と実装バグ由来の例外を型で区別することで、将来のコード変更で紛れ込むバグが静かに握りつぶされるリスクを塞ぐ。
- 5は今回の修正が実際に機能することを自動テストで継続的に保証するため。

### 影響
- 以降、`parseRootObject`の保護範囲は「ループ全体（条件式含む）」かつ「収集済みデータの有無」で判定する設計を踏襲する。
- 本Dの決定4（例外型の絞り込み）は後継のD-016で修正された（`JsonSyntaxException`単体では不十分だったことが判明）。

---

## D-016: T-011b再検証指摘への対応方針（JsonIOExceptionが例外型絞り込みの穴になっていた）

- 日付: 2026-08-21
- 状態: 採用

### 背景
- T-011b（D-015決定4）で`catch (e: Exception)`を`catch (e: IOException)`/`catch (e: JsonSyntaxException)`の2節に絞り込んだ。Reviewerが再検証時、実際のgson 2.14.0のソースを確認し、`parseArrayElementSafely`が使う`JsonParser.parseReader(reader)`（`Streams.parse`経由）が、主ストリームからの**非EOF系の通常`IOException`**を`com.google.gson.JsonIOException`にラップして送出することを発見した。`JsonIOException`は`JsonParseException`の直接のサブクラスであり、`JsonSyntaxException`にも`java.io.IOException`にも該当しないため、D-015で絞り込んだいずれのcatch節にも一致せず、そのまま`parseRootObject`の外へ伝播する。実際に本番コードパスへ非EOFの`IOException`を注入する再現テストで、`format`確定済み・有効データありの状態でも全データが失われることを実測で確認した。
- D-015決定4は「D-014の実測で確認された2系統（EOFException/MalformedJsonException経由のJsonSyntaxException）」のみを根拠にしており、Gsonが`IOException`全般を`JsonIOException`へラップするパターンを見落としていた。旧実装の`catch (e: Exception)`（広すぎる捕捉）はこのケースも偶然拾えていたため、型を絞り込んだことで回帰的にこの経路が塞がれていた。

### 決定
- `TimelineJsonParser.kt`の`catch (e: JsonSyntaxException)`を`catch (e: JsonParseException)`に変更する（`import`も同様に変更する）。`JsonSyntaxException`・`JsonIOException`はいずれも`com.google.gson.JsonParseException`の直接のサブクラスであるため、この1行の型変更で両方を捕捉できる。`catch (e: IOException)`はそのまま維持する（Gson内部でラップされずそのまま伝播する`IOException`もありうるため）。

### 理由
- 1行の型変更で、D-015決定4が意図した「ストリーム破損由来の例外と実装バグ由来の例外を型で区別する」という目的を保ったまま（`JsonParseException`を継承しない実装バグ由来の`RuntimeException`は引き続き再送出される）、見落としていた穴だけを塞げる最小差分であるため。

### 影響
- 以降、`parseRootObject`の例外捕捉範囲は`IOException`と`JsonParseException`（そのサブクラス全般）の2系統とする。

---

## D-017: 560日規模の実データ対応・大規模改修の計画確定と要確認事項への回答

- 日付: 2026-08-21
- 状態: 採用

### 背景
- ユーザーが実際に560日分（130万行超）のTimeline JSONをインポートし、再生開始時にアプリが停止・クラッシュする不具合を報告した。PlannerがコードレベルでANRの根本原因（`Simplifier`の時間ガード処理がO(n²/s)に退化し、保護点間隔が狭い実データではUIスレッド上で最大61回反復される）を特定し、560日規模での安定動作・進捗表示・段階描画・動画のカメラ制御（追従・自動ズーム）・再生時間選択肢拡大（30/60/120/180/300秒、既定60秒）・関心度モデル改善を含む15ステップ（S0〜S15、6フェーズ）の実装計画を作成した。計画本文はPlanner出力（本セッションの会話履歴）に記録済みで、docs/decisions.mdには複製しない。5件の要確認事項にManagerが回答する。

### 決定（要確認事項への回答）
1. **既定期間・既定動画スコープ**: オンスクリーンの初期表示期間、および動画書き出しの既定スコープの両方を「全期間（PeriodType.ALL）」とする。ユーザー要件「デフォルトは読み込んだ全期間を60秒で再生する動画」の意図を、動画書き出しだけでなくブラウジング体験にも一貫させる。概観構築（1〜2秒想定）中はローディング表示を出すことでUXの遅延を許容する。
2. **手動固定倍率モードと全期間の組み合わせ**: 全期間（ALL）選択時は手動固定倍率モードを無効化する（Planner推奨(a)採用）。既存の日/週/月/年での手動モードは変更しない。UIには全期間選択時のみ自動モードのみ選択可能である旨を表示する。
3. **概観点列の1日あたり点数**: Plannerの初期値（128点/日）を採用し、S0のベンチマーク実測結果に応じてS3実装時に調整してよい。ユーザーへの追加確認は行わない（Auto Mode方針、実装の細部であるため）。
4. **動画カメラ演出の強さ**: Planner推奨の「まず控えめな演出（ショット切替はクロスフェードのみ、現在位置マーカーの脈動等の派手な演出は入れない）」を採用する。実機確認ができないため、過度な演出によるリスクを避け保守的に倒す。
5. **「動画として保存」ボタンのenabled条件**: `MapLibreMap`インスタンス依存を維持する（地図表示中のみ書き出し可能、現状維持）。S14で`MapSnapshotter`方式に移行しても、UIの一貫性（地図タブを開いていない状態でのエクスポート開始を防ぐ）を優先し、あえて条件を緩めない。

### 理由
- いずれも実装の細部、またはPlannerが既に妥当な推奨を示した論点であり、Auto Mode方針に沿ってManagerが判断する方が確認待ちで開発を止めるより合理的と判断した。
- 1は、ユーザーの要件文言「デフォルトは...全期間を60秒で再生する動画」を素直に読むと、動画だけでなくアプリの基本体験として「まず全体を見せる」ことを意図していると解釈できるため。

### 影響
- 以降のdeveloperタスクは本D-017の決定と、Plannerが作成した15ステップの計画（S0〜S15）に従って実装する。作業手順の詳細はdocs/tasks.mdへ都度追記する。
- フェーズ1（S0〜S3、クラッシュ根治）を最優先・独立してリリース可能な単位として先に完了させ、以降のフェーズを段階的に積み増す（AGENTS.md原則4）。
- 実機・エミュレータが本開発環境に無いため、フェーズ5（カメラ制御、特にS14の動画上の見た目）は依存ライブラリのソース読解・JVM単体テスト・合成データ計測による検証に留まる既知の制約が継続する。

---

## D-018: T-012レビュー指摘への対応方針（decimateToLimitが時間ガード保護点を無差別に間引く）

- 日付: 2026-08-21
- 状態: 採用

### 背景
- T-012（Simplifierの計算量退化・ANR根治）のレビューで、ReviewerがHigh/CONFIRMEDを検出した。ANRの根本原因（計算量の退化）自体は解消されたが、新設した`decimateToLimit`（`maxPointCount`超過時のハードキャップ）が、DP適用後の結果配列を均等間隔でサンプリングするだけで、どのインデックスが時間ガード保護点（区切り点）由来かを一切考慮しない。実測（560日規模、保護点間隔s≈25の合成データ）で、保護点候補22,076点中3,058点（約14%）しか最終出力に残らないことを確認した。
- これはT-004由来の要件「時間ガードで保護された点（高速移動中の点）は必ず最終出力に残し、アニメーションの現在位置がワープするのを防ぐ」を、まさに本改修が対象とする大規模データ規模で実質的に破る。この動作は`RouteOverlayView`（画面再生）・`VideoExporter`（動画書き出し、ユーザー要件「全期間を60秒で再生する動画」の主要成果物）の両方に影響する。

### 決定
- `decimateToLimit`に保護点（時間ガード区切り点）の情報を渡し、以下の優先順位で間引く方式へ変更する。
  1. 保護点数が`maxPointCount`以下なら、保護点は全て残し、残り枠を非保護点の均等間引きに充てる。
  2. 保護点数自体が`maxPointCount`を超える場合のみ、保護点も間引き対象にする（この場合のワープは、データそのものが密すぎて上限内に収まらないという原理的な限界であり、既存のKDoc通り許容する）。
- あわせて、Reviewerが指摘したLow項目（`maxPointCount`が2未満の場合の契約とKDocの不一致、`Simplifier`が複数セグメントを跨いで呼ばれている点の潜在リスク、計算量表記の不正確さ）は、実害が無い、または現状のデフォルト値の組み合わせでは問題が起きないため、今回は対応せずdocs/tasks.mdのバックログへ記録するに留める。

### 理由
- 「保護点を必ず残す」はT-004以来の既存要件であり、AGENTS.md原則8「手を抜かない対象」の「問題の理解」に照らし、実データ規模で実質的に無効化される状態のまま次のステップへ進むべきではないと判断した。
- 特にユーザーの今回の依頼が「移動軌跡が時間経過に合わせて自然に表示される」ことを明示的な完了条件に含めているため、ワープ防止の実効性はこの改修全体の目的そのものに直結する。

### 影響
- 以降、`decimateToLimit`（およびそれに類する間引き処理）を追加・変更する場合は「保護点優先」の原則を踏襲する。
- T-013以降のフェーズは本修正の完了後に着手する。

---

## D-019: T-013レビュー指摘への対応方針（ズームバケット往復時のキャッシュ確定条件、PlaybackControllerの並行性テスト欠如）

- 日付: 2026-08-21
- 状態: 採用

### 背景
- T-013（重い処理のUIスレッドからの排除）のレビューで、ReviewerがMedium 2件を検出した。
  1. `RouteOverlayView.recomputeAndInvalidate`が`zoomBucket != cachedZoomBucket`のみを再計算のトリガーにしており、`cachedZoomBucket`は計算完了後にしか更新されない。そのためズームバケットがA→B→A（デバウンス窓内で往復）と変化すると、2回目のA復帰時点で`cachedZoomBucket`がまだAのままのため新しい`scheduleSimplify`が呼ばれず、Bを対象に計算中の古いジョブがキャンセルされないまま完了し、実際のカメラはAに戻っているのにBのepsilonで計算された結果がキャッシュへ書き込まれる。
  2. `PlaybackController`に追加した並行性制御ロジック（`rebuildGeneration`）に単体テストが一切ない。開発者はdocs/progress.mdで「`kotlinx-coroutines-test`が既存依存に無いためテストできない」と説明していたが、Reviewerが`kotlinx-coroutines-core`が既にテストクラスパスに存在すること（androidx.lifecycle経由）を実地確認し、新規依存なしで`runBlocking`ベースのテストが書けることを示した。前提自体が誤りだった。

### 決定
1. `scheduleSimplify`完了時のコミット条件（現状`route !== currentRoute`のみ）に、対象zoomBucketが依然として最新かどうかのチェックを追加する。計算完了時点で`map`から現在のズームバケットを再取得し、計算対象だったbucketと異なればキャッシュへの書き込みをスキップする（既存の`route !== currentRoute`と同じ「確定直前の二重チェック」パターンを踏襲する）。
2. `PlaybackControllerTest.kt`を新設し、新規依存を追加せず`kotlinx.coroutines.runBlocking`上で「`setRoute`実行中に`setSpeedMode`を呼ぶ／逆順」等のケースで、最終的な`state`/`timeline`が最後に呼ばれた方の入力を反映することを検証する。

### 理由
- 1はズームバケットの往復という限定的だが実際に起こりうる操作（ピンチズームのオーバーシュート等）で発生し、実際のカメラ位置と異なる簡略化粒度が使われ続ける不整合であり、AGENTS.md原則7（根本原因を直す）に沿って対応する。
- 2は、T-013で新規導入した唯一の複雑な並行性ロジックが、実機・エミュレータ不在という制約下で「人間の目視レビューのみ」に依存している状態であり、Reviewerが依存関係の実地確認で「テストできない」という前提自体を覆したため、対応しない理由が無くなった。

### 影響
- 以降、`RouteOverlayView`のキャッシュ確定ロジックを変更する場合は「計算完了時点の対象と現在の状態が一致するか」を再確認するパターンを踏襲する。
- `PlaybackController`の並行性ロジックは以降テストで保護される。

---

## D-020: T-014レビュー指摘への対応方針（RouteOverviewキャッシュの並行性テスト欠如、無効化時の未キャンセルJob、未使用メソッド）

- 日付: 2026-08-21
- 状態: 採用

### 背景
- T-014（概観点列RouteOverviewの導入）のレビューで、ReviewerがHigh 1件・Medium 1件・Low 2件を検出した。
  1. （High/CONFIRMED）`TimelineViewModel.ensureRouteOverview`/`invalidateRouteOverview`（`routeOverviewGeneration`による世代ガード）は`PlaybackController.rebuildGeneration`と同種の並行性ロジックだが、単体テストが一切ない。D-019でまさに同じ形の指摘（`PlaybackController`の並行性テスト欠如）に対応した直後の再発であり、`TimelineViewModelTest.kt`はリポジトリに1つも存在しない。
  2. （Medium/CONFIRMED）`invalidateRouteOverview`は`routeOverviewBuildJob`の参照を`null`にするだけで、実行中の`Deferred`（`RouteOverview.build(repository)`、全`days`テーブル走査＋日ごとのDP）自体をキャンセルしない。世代ガードにより結果が誤ってキャッシュされることはないが、560日規模の重い処理がインポート完了後も無駄に完走し、`commitImport`の書き込みトランザクションとSQLite接続を奪い合う。
  3. （Low/CONFIRMED）新設した`TimelineRepository.queryDayDates()`が呼び出し元皆無（未使用）。
  4. （Low/PLAUSIBLE）`_routePoints`/`_routeBounds`が別々の`StateFlow`への逐次代入のため、理論上`LaunchedEffect`が新旧混在の組み合わせで一瞬発火しうる（自己修正見込み、データ破損なし）。

### 決定
1. 上記1・2・3を修正する（T-014b）。
   - `ensureRouteOverview`が`RouteOverview.build(repository)`をハードコード呼び出しせず、差し替え可能な関数として保持するようにし、`TimelineViewModelTest.kt`を新設して「構築中に`invalidateRouteOverview`が呼ばれても古い結果がキャッシュへ書き戻されない」「並行呼び出しが同じJobを共有する」ことを`runBlocking`+`launch`（`PlaybackControllerTest.kt`と同じ手法、新規依存不要）で検証する。
   - `invalidateRouteOverview()`内で`routeOverviewBuildJob?.cancel()`を呼んでから`null`化する。
   - 未使用の`queryDayDates()`を削除する（YAGNI、AGENTS.md判定ラダー1）。
2. 4は実害が無く（次のリコンポジションで自己修正される）、確実な再現手順も無いPLAUSIBLE判定のため、今回は対応せずdocs/tasks.mdのバックログへ記録するに留める。

### 理由
- 1・2・3はAGENTS.md原則7（バグは根本原因を直す）・原則8（手を抜かない対象）に照らし、実機・エミュレータ不在という制約下で並行性ロジックを人間の目視レビューのみに委ねる状態を放置すべきではないと判断した。特にHigh指摘はD-019と全く同じ構造の再発であり、放置すると同種の並行性ロジックを追加するたびに同じ指摘を繰り返すことになる。
- 4は構造的な改善余地（`_routePoints`/`_routeBounds`を単一`StateFlow`にまとめる）はあるものの、影響が軽微かつ確証が無いため、REVIEW.mdの過剰指摘抑制方針に従いバックログ止まりとする。

### 影響
- 以降、`ViewModel`層に世代ガード等の並行性ロジックを追加する場合、DB/リポジトリ依存を注入可能にしてテスト可能な形で実装するパターンを踏襲する。
- キャッシュ無効化処理は、参照を外すだけでなく実行中の`Job`/`Deferred`を明示的にキャンセルするパターンを踏襲する。

---

## D-021: T-015レビュー指摘への対応方針（上書き確認後の進捗リセット、インポートのキャンセル不能、初回発火の早期化）

- 日付: 2026-08-21
- 状態: 採用

### 背景
- T-015（インポート進捗表示）のレビューで、ReviewerがMedium 2件・Low 2件・Nit 1件を検出した。
  1. （Medium/CONFIRMED）`confirmOverwrite()`が`_importState.value = ImportUiState.InProgress()`（既定値）で初期化するため、確認ダイアログ通過後にパース完了直前まで表示されていた点数・日付範囲が唐突に消え「インポート中…」へ戻る。DB書き込みフェーズ（`commitImport`）では`onProgress`が呼ばれないため、ここで表示が空白化したまま次のフェーズへ進む。
  2. （Medium/CONFIRMED）`ImportSource.readRawTrack`/`TimelineJsonParser.parseJson`/`parseZip`は同期ブロッキング処理でキャンセル協調的なチェック（`isActive`/`ensureActive`等）を持たない。`viewModelScope`がキャンセルされて（画面破棄等）も、`Dispatchers.IO`上のパース処理自体は止まらず、既にクリアされたViewModelの`_importState`へ進捗コールバックが書き込み続ける。この欠陥自体は`importFrom`に元々存在していた設計ギャップ（`exportVideo`が持つ`cancelExport()`に相当する仕組みが`importFrom`には無い）だが、進捗コールバックの導入によりキャンセル未対応の窓（パース全体の期間）がより露出する形になった。
  3. （Low/CONFIRMED、実測）`RawTrackBuilder.lastProgressTimeMillis`の初期値が`0L`のため、最初の`addPoint`呼び出し時点で経過時間条件が必ず真になり、`pointCount=1`という意図しない早期タイミングで`onProgress`が発火する。
  4. （Low/CONFIRMED）追加されたテスト3件はすべて`parseJson`のみが対象で、`parseZip`側の`onProgress`（複数エントリをまたいだ累積、1パス目`scanForSemanticLocationHistoryEntry`での不発火）を検証していない。
  5. （Nit）`TimelineViewModel`レベルの自動テストが今回も無い（`ImportUiState.InProgress`のdata class化・`confirmOverwrite`のリセット挙動）。

### 決定
1. 上記1・2・3・4を修正する（T-015b）。
   - `confirmOverwrite()`は、`pendingImport`が保持する直前のパース結果（点数・日付範囲）を引き継いだ状態を維持する、またはDB書き込みフェーズ専用の状態・文言に変更し、パース完了値が唐突に消えないようにする。
   - `RawTrackBuilder`（または`TimelineJsonParser.parseJson`/`parseZip`）に軽量なキャンセル可能化を入れる。新規外部依存を追加せず、例えば`onProgress`コールバックの戻り値や別途渡す`() -> Boolean`（「継続してよいか」を返す関数、`viewModelScope`が`isActive`を渡せる）で、キャンセル済みなら例外を投げてパースを打ち切れるようにする。
   - `lastProgressTimeMillis`の初期値を「未設定」を表す形にし、最初の`addPoint`時点で即座に発火しないようにする（例えば初回呼び出し時に現在時刻で初期化してから経過時間判定を行う、または点数閾値のみで初回を判定する）。
   - `parseZip`版の`onProgress`テストを最低1件追加する。
2. 5（Nit）は今回対応せず、docs/tasks.mdバックログへ記録するに留める（REVIEW.mdの過剰指摘抑制方針、実害が小さいため）。

### 理由
- 1はユーザーの元要件「読み込み完了をユーザーが明確に確認できる」の趣旨に反し、進捗表示が信頼できないものに見えてしまうため修正する。
- 2はAGENTS.md原則8（手を抜かない対象、エラーハンドリング・リソース管理）に照らし、560日規模という大きなファイルを扱う本改修の中心テーマで「止められない長時間バックグラウンド処理」を放置すべきではないと判断した。ただし新規のキャンセル基盤（`Job`管理等）を大きく作り込むのではなく、既存の`viewModelScope`のキャンセル状態を渡すだけの最小実装に留める（AGENTS.md原則2「最小実装」）。
- 3・4は軽微だが、間引きロジックという本タスクの中核部分の正確性・テスト網羅性に関わるため合わせて修正する。

### 影響
- 以降、同期ブロッキングな重い処理へ進捗コールバックを追加する場合、キャンセル伝播（呼び出し元の`CoroutineScope`の生存確認）もあわせて設計するパターンを踏襲する。
- 状態遷移をまたいで表示を維持すべき情報（今回は点数・日付範囲）は、次状態の初期化時に既定値へ戻さず引き継ぐパターンを踏襲する。

---

## D-022: T-016レビュー指摘への対応方針（RawTrackBuilder高速パスの大規模テスト欠如）

- 日付: 2026-08-21
- 状態: 採用

### 背景
- T-016（インポート時のメモリ削減）のレビューで、ReviewerがMedium 1件・Low 2件を検出した。
  1. （Medium/CONFIRMED）新設の130万点規模テスト（`TimelineRepositoryTest.buildPreparedImport_largeScale560DayTrack_...`）は`RawTrack`を直接構築しており、T-016の変更点の1つである`RawTrackBuilder.isAlreadySortedAscending()`（既ソート時のコピー省略高速パス）を経由しない。この分岐は既存の`TimelineJsonParserTest.kt`で小〜中規模（最大5万点）の間接検証はあるが、1.3M点規模での検証が抜けている。
  2. （Low/CONFIRMED、将来リスク）`PointBuffer.trim()`が内部配列を無コピーで返す前提（「使い捨て」）はコメントのみに依存し、コンパイラ・テストで強制されていない。現状の3呼び出し元は全て安全だが、将来のリファクタで`PointBuffer`が使い回されるとエイリアシングバグになりうる。
  3. （Low/PLAUSIBLE、一般知識）`android:largeHeap="true"`はOEM依存で保証幅が限定的、Low Memory Killerに狙われやすくなる可能性がある。対応不要、将来実機報告があれば見送った設計変更を再検討する旨を記録するに留める。

### 決定
1. 1（Medium）を修正する（T-016b）。Reviewer提案の(b)案を採用し、`TimelineJsonParserTest.kt`に1.3M点規模・時刻昇順の合成JSONを`TimelineJsonParser.parseJson`へ通す新規テストを追加し、`isAlreadySortedAscending()`分岐を実スケールで検証する。
2. 2・3は対応せず見送る。2は現状無害でReviewer自身も「見送りも妥当」と評価しており、将来`PointBuffer`を使い回すリファクタが実際に発生した時点で対応すれば足りる（YAGNI）。3は本開発環境で実機検証できない一般知識に基づく指摘であり、対応不要（Reviewer自身の判定）。両方ともdocs/tasks.mdバックログへ記録するに留める。

### 理由
- 1はT-016の主要な変更点（既ソート高速パス）が大規模データで未検証のままでは、560日規模という本改修全体のテーマに照らして「テスト観点を確認済み」と言い切れないため修正する。
- 2・3は実害が無い、または本環境の制約上検証不可能な事項であり、REVIEW.mdの過剰指摘抑制方針・AGENTS.md判定ラダー（そもそも必要か）に従い見送る。

### 影響
- 以降、複数の変更点を1つの大規模テストでまとめて検証しようとする場合、各変更点が実際にそのテストのコードパスを通っているかを確認するパターンを踏襲する（今回のように一部の変更が別経路のため素通りする例があるため）。

---

## D-023: T-017レビュー指摘への対応方針（ALL選択中のインポートで境界が再解決されない、起動時レース条件）

- 日付: 2026-08-21
- 状態: 採用

### 背景
- T-017（全期間の期間種別PeriodType.ALL）のレビューで、ReviewerがHigh 1件・Medium 1件・Low 2件を検出した。
  1. （High/CONFIRMED）`TimelineViewModel.commitPreparedImport`はインポート成功時`invalidateRouteOverview()`のみを呼び、`_selectedPeriod`を再解決しない。全期間（ALL）は選択時点のDB最古日〜最新日をスナップショットとして`Period`に固定する設計のため、(a)初回起動（DB空）→インポート後もフォールバック値（今日のDAY）のままで、ユーザー要件「デフォルトは全期間」を初回インポート後に満たせない、(b)ALL選択中に2回目以降のインポートで日付範囲が拡張されても、新しいデータが地図にも動画書き出しにも反映されない。
  2. （Medium/CONFIRMED）`TimelineViewModel.init`内の`resolveAllPeriod()`（`repository.queryDateRange()`のIO待ち）完了前にユーザーが期間タブを操作した場合、その選択を`init`の非同期代入が後から無条件に上書きしうる（ガード無し）。
  3. （Low/CONFIRMED、実害なし）`selectPeriod`内の`enforceSpeedModeConstraint`呼び出しは現状到達しない防御的コード（`selectPeriod`にはALLが渡らないため）。
  4. （Low/CONFIRMED、開発者自身が認識済み）起動時、DBクエリ完了までの一瞬「日/今日」表示から「全期間」へ切り替わるちらつきがある。

### 決定
1. 1（High）・2（Medium）を修正する（T-017b）。
   - `commitPreparedImport`成功時、現在`_selectedPeriod.value.type == PeriodType.ALL`ならALLの境界を再解決する（`resolveAllPeriod()`相当を再実行し`_selectedPeriod`・`loadRoute`を更新）。ユーザーがDAY/WEEK/MONTH/YEAR/CUSTOMを明示選択中の場合はその選択を尊重し上書きしない。
   - `init`内の非同期解決と`selectPeriod`/`selectAllPeriod`によるユーザー操作が競合しないよう、`init`のJobを保持し`selectPeriod`/`selectAllPeriod`側でキャンセルする、または世代カウンタで「ユーザー操作後は`init`の結果を無視する」ことを保証する。
2. 3・4は対応せず見送る。3は実害の無い到達不能コードで、削除してもコストに見合わないため現状維持。4は開発者がdocs/progress.mdで既にトレードオフとして明記済みであり、対応するには`_selectedPeriod`のnullable化等タスク範囲を超える設計変更が必要なため見送る。両方ともdocs/tasks.mdバックログへ記録するに留める。

### 理由
- 1はユーザーの元要件「デフォルトは全期間」の趣旨に直接反する（特にインポート直後という最も基本的な利用シーンで全期間が反映されない）ため、AGENTS.md原則7（バグは根本原因を直す）に照らし修正する。
- 2は発生条件がタイミング依存で頻度は低いと見込まれるが、ユーザー操作が理由なく巻き戻るというUX上の不整合であり、修正コストも小さいため合わせて対応する。
- 3・4は実害が無い、またはタスク範囲を超える設計変更が必要な事項であり、REVIEW.mdの過剰指摘抑制方針・AGENTS.md判定ラダー（そもそも必要か）に従い見送る。

### 影響
- 以降、「選択時点のDBスナップショットを保持する」設計（ALLのような動的境界を持つ状態）を導入する場合、その境界に影響しうる後続のデータ変更（インポート等）が発生した際に再解決するかどうかを明示的に設計する。
- 非同期の初期化処理とユーザー操作が競合しうる`init`パターンでは、ユーザー操作後に初期化結果を無視するガードを設けるパターンを踏襲する。

---

## D-024: T-017bレビュー指摘への対応方針（commitPreparedImport経由のALL遷移で手動モードが解除されない）

- 日付: 2026-08-22
- 状態: 採用

### 背景
- T-017b（D-023の修正）のレビューで、ReviewerがMedium 1件を検出した。`TimelineViewModel.commitPreparedImport`は`periodResolutionGate.isAllSelected`が`true`のとき`resolveAndApplyAllPeriod()`を呼ぶが戻り値を捨てており、`enforceSpeedModeConstraint`を呼ばない。一方`selectAllPeriod()`は同じ関数の戻り値を`?.let { enforceSpeedModeConstraint(it.type) }`で正しく使っている。
- DB空時のフォールバック（`resolveAllPeriod()`が`Period.of(PeriodType.DAY, LocalDate.now())`を返すケース、`isAllSelected`は`true`のまま`type`だけ`DAY`になる、D-023決定1で導入した意図的な設計）中は`isManualModeAllowed(DAY)==true`のため手動モードを選択可能。この状態で初回インポートを行うと`type`が本物の`ALL`へ遷移するが、`enforceSpeedModeConstraint`が呼ばれないため手動速度モードが解除されず、D-017決定2「全期間選択時は手動固定倍率モードを無効化する」に反するUI/再生状態の不整合が残る。

### 決定
- `commitPreparedImport`内の`resolveAndApplyAllPeriod()`呼び出しを、`selectAllPeriod()`と同じ`?.let { enforceSpeedModeConstraint(it.type) }`パターンに揃える。他の対応は不要（この1箇所の非対称性のみが原因のため）。

### 理由
- AGENTS.md原則7（バグは根本原因を直す）に照らし、同一関数の2つの呼び出し元で片方だけ後処理が欠落している非対称性そのものが根本原因であり、揃えることで解消できる。修正は1行で完結し、判定ラダー6に該当する。

### 影響
- 以降、共通化した関数（`resolveAndApplyAllPeriod`等）を複数箇所から呼ぶ場合、各呼び出し元で必要な後処理（副作用）を揃って行っているか確認するパターンを踏襲する。

---

## D-025: T-019レビュー指摘への対応方針（イベント密度テストがsaturate由来の効果と混同していた）

- 日付: 2026-08-22
- 状態: 採用

### 背景
- T-019（関心度モデルの改善）のレビューで、ReviewerがMedium 1件・Low 1件・Nit 1件を検出した。
  1. （Medium/CONFIRMED、Python再現による数値実証済み）`PlaybackTimelineTest.buildAuto_eventDensity_denserPointsWithinSameTimeAndDistanceGetMoreInterest`は、`densityWeightMillis`（密度項）を完全に無効化しても同じアサーションが成立してしまい、密度項の存在・正しさを検証できていない。原因は、テストが区間を分割することで`saturate`関数自体の凹関数性（区間を細かく分割するほど`saturate`の合計が線形値に近づき増加する）由来の効果（寄与0.054）が、密度項単体の寄与（0.0002、約270倍小さい）を完全に支配してしまうため。テスト1（滞在頭打ち）・テスト2（移動頭打ち）は同じPython再現検証で有効性が確認されている。
  2. （Low/PLAUSIBLE）`DEFAULT_MOVEMENT_SATURATION_METERS = 5_000.0`（5km）は、560日規模の実データで高速道路・新幹線等のGPSサンプリング間隔が疎になる正当な高速移動区間も、飛行機移動のような外れ値区間と同程度に圧縮してしまう可能性がある。実データでの体感検証が本環境では不可能なため確証は無い。
  3. （Nit/情報提供）密度項（γ）は入力点列が簡略化されていない生データであることを前提とする設計だが、この前提が`buildAuto`のKDocに明記されていない。現状のコードでは`Simplifier`による簡略化後の点列が`buildAuto`に渡ることは無い（確認済み）ため実害は無いが、将来の変更時の事故防止のためKDocに一言添えるとよい。

### 決定
1. 1（Medium）を修正する（T-019b）。Reviewer提案の(c)案（`densityWeightMillis=0`と既定値の2条件でのfraction差分を比較する設計、saturateの効果は両条件で共通なのでキャンセルできる）を採用し、密度項単体の効果を実際に検証できるテストへ書き換える。
2. 2（Low）は対応せず、実データでの検証ができ次第（T-022以降のカメラ制御スパイク検証や、将来ユーザーから実データでの体感フィードバックが得られた際）見直す判断材料として、docs/tasks.mdバックログへ記録するに留める。デフォルト値自体は変更しない（実データ未検証のまま推測で調整するのは根拠が弱いため）。
3. 3（Nit）はT-019bで合わせて対応する（KDocへの1行追記、コストが極めて小さいため）。

### 理由
- 1はテストが「検証している」と主張する内容と実際に検証できている内容が一致しておらず、REVIEW.mdの完了条件「テスト観点を確認済み」を満たさないため修正する。密度項自体の実装（1行の単純加算）にバグは無いと確認されているが、将来のリグレッションを検知できないテストを放置すべきではない。
- 2は実機・実データでの検証が本開発環境では構造的に不可能な既知の制約（D-017「影響」参照）に該当し、推測のみで数値を調整するのはAGENTS.md原則1（理解してから作る）に反するため見送る。
- 3は実害が無いが、対応コストがKDoc1行のためT-019bに含めるコスト対効果が見合う。

### 影響
- 以降、「AとBを比較して差を検証する」形式のテストを書く場合、比較対象間で意図した1変数以外の要因（今回のsaturateのような非線形性）が結果を支配していないか、対象パラメータを無効化した場合にテストが実際に失敗することを確認する習慣を踏襲する。

---

## D-026: T-020レビュー指摘への対応方針（Low/Nit 3件、いずれも見送り）

- 日付: 2026-08-22
- 状態: 採用

### 背景
- T-020（ポリライン分断と過去/直近3層描画、コミット`d86cb92`）のレビューで、ReviewerがCritical/High/Medium相当の問題を検出せず、Low/Nit 3件のみを検出した（Reviewer自身がいずれも「必須修正ではない」と明記）。
  1. （Low/CONFIRMED）`drawRoute`の点数不一致時フォールバックは現行の呼び出し元では到達不能。
  2. （Low/Nit/CONFIRMED、実害なし）`trimByProgress`の時刻補間で`Long×Float`により理論上の精度損失があるが、実測誤差は最大でも数ms、既存の閾値（時間単位）へ影響しない。
  3. （Low/PLAUSIBLE）`Style.pastRoutePath`が`DEFAULT_STYLE`シングルトン経由で画面再生・動画書き出し間の共有可変状態になる。既存の`routePath`/`routePaint`（D-008以来）と同種のリスクで、本コミットが新規に導入した種類の不具合ではない。

### 決定
- 3件とも今回は対応せず、docs/tasks.mdバックログへ記録するに留める。

### 理由
- REVIEW.mdの過剰指摘抑制方針・AGENTS.md判定ラダー（そもそも必要か）に照らし、Reviewer自身が必須修正でないと判断した軽微な指摘に追加の修正サイクルを回すコストは見合わない。1は到達不能コードで実害なし、2は実測で無視できる精度損失、3は既存設計の踏襲であり本タスク固有の新規リスクではない。

### 影響
- 3（`Style`の共有可変状態）は、将来「画面再生とエクスポートの同時実行」という設計変更（例: エクスポート中も画面プレビューを更新し続ける等）が入る場合に再検討が必要になる可能性がある。その際はdocs/tasks.mdバックログを参照する。

---

## D-027: T-021レビュー指摘への対応方針（期間切替直後の詳細ウィンドウが新期間の境界を誤って使う競合）

- 日付: 2026-08-22
- 状態: 採用

### 背景
- T-021（詳細ウィンドウの遅延ロード、コミット`5c37d25`）のレビューで、ReviewerがMedium 1件・Low 2件を検出した。
  1. （Medium/CONFIRMED、コルーチンでの再現テストにより実証済み）`selectPeriod`は`_selectedPeriod.value`を同期的に即時更新するが、`isLongPeriodSelected`・`_routePoints`・`loadedDetailWindow`（詳細ウィンドウ機構の状態）は`loadRoute`のIO・計算完了後まで更新されない。この間に旧期間の再生ループが`dataTimeMillis`を更新すると、`onPlaybackDataTimeChanged`は旧期間の`isLongPeriodSelected`/`loadedDetailWindow`で判定するが、内部で呼ぶ`scheduleDetailWindowLoad`は`_selectedPeriod.value`をその時点で読み直すため、既に切り替わった新期間を境界としてしまい、まだ更新されていない旧期間の`_routePoints.value`へ境界不整合な詳細データをmergeして画面描画（`_displayRoutePoints`）へ書き込む可能性がある。`loadRoute`完了時に`resetDetailWindow`が無条件で上書きするため自己修復し、データ損失・クラッシュには至らない一時的な描画不整合。
  2. （Low/CONFIRMED、実害なし）`DetailWindowTest.kt`に、base点がdetailの開始/終了時刻と完全一致する境界ケースの明示テストが無い。実装自体は追加検証で正しいことを確認済み。
  3. （Low/PLAUSIBLE）長期間再生中、`needsReload`判定が再生フレーム毎（最大60Hz）にメインスレッドで走る。処理自体は軽量で実測での性能劣化は未確認。

### 決定
1. 1（Medium）・2（Low、対応コストが極めて小さいためあわせて実施）を修正する（T-021b）。
   - `selectPeriod`内で`_selectedPeriod.value`を更新するのと同期的に、詳細ウィンドウ機構を無効化するトークン（例: 世代カウンタのインクリメント、または`isLongPeriodSelected`の即時リセット）も更新し、`scheduleDetailWindowLoad`が`_selectedPeriod.value`を後から読み直して新期間を誤って捕捉しないようにする。
   - `DetailWindowTest.kt`に、base点がdetailの開始/終了時刻と完全一致する境界ケースのテストを1件追加する。
2. 3（Low/PLAUSIBLE）は対応せず、docs/tasks.mdバックログへ記録するに留める。

### 理由
- 1は自己修復するとはいえ、再生中に一瞬でも境界不整合な軌跡が画面に表示されうる設計上のギャップであり、AGENTS.md原則7（バグは根本原因を直す）に照らし放置すべきではないと判断した。修正コストも「期間切替時に同期的にトークンを進める」という限定的な変更で済む。
- 2は実害が無いが、対応コストがテスト1件追加のみのためT-021bに含める。
- 3は実測での性能劣化が確認できておらず、本開発環境では実機検証もできないため、推測のみでの最適化はAGENTS.md原則1（理解してから作る）に反すると判断し見送る。

### 影響
- 以降、`StateFlow`の同期的な即時更新と、それに付随する非同期処理の状態（ガード条件を含む）の更新タイミングがずれる設計を導入する場合、両者を同じタイミング（同一の同期区間）で更新するパターンを踏襲する。

