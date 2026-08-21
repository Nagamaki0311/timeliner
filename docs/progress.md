# 作業履歴

作業内容、実施結果、次回開始位置を記録する。新しいエントリは先頭に追加する（新しい順）。

## 記録フォーマット

```
## YYYY-MM-DD タスクID/概要

### 実施内容
- 何を行ったか

### 結果
- 動作確認結果、テスト結果など

### 次回開始位置
- 次に着手すべき場所（ファイル/関数/タスクID）
```

## 2026-08-21 T-011b T-011レビュー指摘の修正（保護範囲の見落とし2件、例外型の絞り込み）

### 実施内容
- D-015の決定に従い`TimelineJsonParser.parseRootObject`を修正した。
  1. try/catchの範囲を`when`ブロックだけでなく`while (reader.hasNext())`の条件式評価を含むループ全体へ拡大した。
  2. `format`の代入を、対応するキー名が判明した`when`の各分岐に入った直後（配列パース呼び出しの前）へ前倒しした。これにより、配列自身の2件目以降の要素で例外が発生しても1件目までの成果を「format確定済み」として回収できるようにした。
  3. 回復可否の判定条件を`format != null`から`format != null && !builder.isEmpty()`へ変更した。`RawTrackBuilder`（`TimelineJsonParser.kt`内の非公開クラス）に`isEmpty()`（点0件かつセグメント0件を返す）を追加した。「キーは判明したが1件も読めなかった真の失敗」を誤って成功扱いしないための区別。
  4. `catch (e: Exception)`を、D-014の実測で確認された2系統（`java.io.IOException`とそのサブクラス、`com.google.gson.JsonSyntaxException`）へ限定した（Kotlinはmulti-catch構文が無いため、2つの`catch`節から共通の`recoverRootObjectOrRethrow`ヘルパーを呼ぶ形にした）。それ以外の`RuntimeException`は握りつぶさず再送出する。
- 対象ファイル: `app/src/main/java/com/nagamaki0311/timeliner/data/parser/TimelineJsonParser.kt`（`parseRootObject`・新設`recoverRootObjectOrRethrow`・`RawTrackBuilder.isEmpty()`）。`RawTrackBuilder`は当初依頼で`RawTrack.kt`にあると想定されていたが、実際には`TimelineJsonParser.kt`末尾の非公開クラスとして存在するため、そちらへ追加した。

### 結果
- `TimelineJsonParserTest.kt`にReviewer指摘の3種の境界値テストを追加した。
  1. `parseJson_unknownKeyTruncatedBeforeAnyKnownKeyAppears_rethrowsException`: 既知キーが一つも現れないまま（`format`未確定のまま）`rawSignals`配列が切り詰められた場合、例外が再送出されることを確認。
  2. `parseJson_semanticSegmentsTruncatedFromSecondElement_recoversFirstElementData`: `semanticSegments`配列自身が2件目の要素で途中切り詰めになった場合、1件目の有効データ（点1件・セグメント1件・`placeId`一致）を保持したまま例外を投げずに復旧することを確認。
  3. `parseJson_truncatedRightAfterKnownKeyAtObjectCloseBoundary_recoversParsedData`: `locations`配列を読み終えた直後、ルートオブジェクトの閉じ`}`が無いままストリームが終わる（次のキー確認`hasNext()`自体が例外を投げる）場合も、既に確定していた点1件を保持したまま復旧することを確認。
- `./gradlew testDebugUnitTest`成功（新規3件含め全件パス）。
- `./gradlew assembleDebug`成功。

### 次回開始位置
- 特になし。T-011b完了、T-011を完了へ戻す。

## 2026-08-21 T-011 実機報告対応: rawSignals読み飛ばし失敗でインポート全体が失敗する不具合を修正

### 実施内容
- ユーザーが実際の端末内Timeline(Android形式)エクスポートファイル（130万行超）をインポートしたところ「インポートに失敗しました: End of input at line 1325233 column 25 path $.rawSignals[12163]..[137610].」で失敗したと報告された。
- **原因調査**: `TimelineJsonParser.kt`が使う`com.google.gson.stream.JsonReader`（2.14.0）の実ソースをGitHubから取得して`skipValue()`の実装を確認した上、スクラッチ環境（`javac`+実際のgson jar、`/tmp`配下の一時スクリプト、docsには残さない）で以下を実測検証した。
  1. 15万要素の合成`rawSignals`配列を含む正常な（切り詰めていない）JSONに対し`skipValue()`を実行→問題なく完走（Gsonの`skipValue()`自体に大規模配列特有のバグは無い）。
  2. 同じ配列を末尾で意図的に切り詰めたJSONに対し同じ経路を実行→実際に`java.io.EOFException: End of input`が発生することを確認（ユーザーが報告した症状と同種）。
  3. `ImportSource.kt`を確認したところ、`ContentResolver.openInputStream()`の結果をバッファサイズ制限・タイムアウト・独自ラッピング無しでそのまま`InputStreamReader`→`JsonReader`へ渡しており、アプリ側のコードに人為的な打ち切り要因は無いことを確認した。
  4. 追加で、配列2要素中2要素目を意図的に途中で切り詰めた最小合成JSONを使い、`TimelineJsonParser.parseArrayElementSafely`（`semanticSegments`/`timelineObjects`/`locations`各配列の要素単位パースで共有される関数）にも同種の欠陥（`JsonParser.parseReader(reader)`自体がtry/catchの外にあり、要素消費中の構造的パース失敗が保護されず上位へ伝播する）があることを実測で確認した。
  5. さらに、EOFException等の送出後は同一`JsonReader`インスタンスへの以降の呼び出し（`hasNext()`/`endObject()`等）もすべて同じ例外を再送出する（内部状態が破損したまま復旧しない）ことを実測確認した。これは修正実装（次項）で「例外発生後はreaderへ一切触れず即座に返す」設計にした根拠。
  - 詳細・実測結果はdocs/decisions.md D-014に記録。ユーザーの実ファイル自体は機微な個人位置情報のため入手できず、ファイルが途中で切れていた具体的原因（Google側のエクスポート処理かファイル転送側か）そのものの特定はできていない。
- **修正**: `TimelineJsonParser.parseRootObject`のwhileループ本体（`when`ブロック全体、`else -> skipValue()`だけでなく`semanticSegments`/`timelineObjects`/`locations`の各分岐も含む）を`try/catch (e: Exception)`で囲んだ。例外発生時、その時点で`format`が確定していれば（主要形式のデータを読み終えている）警告ログを出力し、`reader`へは以降触れずにその`format`をそのまま返して正常終了する。`format`未確定なら回復可能なデータが無いため従来通り再送出する。`when`ブロック全体を保護範囲としたのは、`else`分岐だけだと調査4で見つけた`parseArrayElementSafely`起因の例外（既知キー処理中の構造的パース失敗）が素通りしてしまうため。`parseArrayElementSafely`自体は改修していない（reader例外後は使用不能という調査5の制約により、`parseRootObject`側1箇所で吸収する方が変更範囲が小さく責務も自然と判断、D-014参照）。

### 結果
- `TimelineJsonParserTest.kt`に、`semanticSegments`が正常な1件のVISITセグメントを含み、`rawSignals`配列（5000要素）が意図的に閉じ括弧なしで終わる（切り詰めを再現する）合成JSONを追加した（`parseJson_rawSignalsTruncatedMidArrayAfterValidSemanticSegments_returnsAlreadyParsedData`）。例外を投げずに`semanticSegments`由来の有効なデータ（点1件・セグメント1件、`placeId`・座標とも一致）が正しく返ることを確認した。
- `./gradlew testDebugUnitTest`成功（既存テスト含め全件パス）。
- `./gradlew assembleDebug`成功。

### 懸念点（既知の制約として残すもの）
- `parseZip`が複数エントリを走査中、あるエントリの`parseRoot`が`format`未確定のまま例外を投げると、それ以前に処理済みだった別エントリのデータもろとも失われる問題は未対応（稀な複合条件のため今回は見送り、YAGNI。docs/tasks.mdバックログ・D-014参照）。
- ユーザーの実ファイルそのものでの再検証はできていない（機微な個人位置情報のため入手不可という制約下、合成データでの再現・検証に留まる）。

### 次回開始位置
- 特になし。T-011完了。ユーザーが同じファイルで再度インポートを試し、実際に成功するかの実機側フィードバックを待つことが望ましい。

### コミット
- 本タスクの変更（コード・テスト・docs/tasks.md・docs/decisions.md含む）はコミット済み（コミットハッシュ`61022a6`、コミットメッセージ先頭行: `T-011: rawSignals読み飛ばし失敗でインポート全体が失敗する不具合を修正する`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

## 2026-08-21 T-010b T-010レビュー指摘の修正（ランドスケープ+レガシーナビゲーションバーでの横方向inset未対応）

### 実施内容
- D-013の決定に従い、`app/src/main/java/com/nagamaki0311/timeliner/ui/TimelineScreen.kt`の`PeriodSelector`・`PlaybackControls`の呼び出し箇所に`Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))`を追加した。両Composableとも既存の`modifier`パラメータを外側の`Column`に適用する設計だったため、呼び出し側でのpadding指定のみで内部の`fillMaxWidth()`要素（前後移動ボタン、シークバー、速度モード選択ボタン群）すべてに横方向のinsetが伝播する。
- `horizontalNavBarInsets`（`WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)`）を`TimelineScreen`関数内で1回だけ計算し、両呼び出し箇所で使い回した（D-012が確保済みの縦方向insetとの重複を避けるため`WindowInsetsSides.Horizontal`で明示的に限定）。
- `MainActivity.kt`のTabRow（ステータスバー、画面上端固定で左右のシステムバーとは接しない）・`ImportScreen.kt`（ルート`Column`に`WindowInsets.navigationBars`を全方向で適用済み）はD-013の決定通り変更していない。

### 結果
- `./gradlew testDebugUnitTest`成功。今回の変更はCompose UI（`Modifier`のみ）でAndroid API依存のためJVM単体テスト対象外（D-003と同種の制約）、新規テストは追加していない。
- `./gradlew assembleDebug`成功。

### 懸念点（既知の制約）
- 実機・エミュレータが本開発環境に無いため、ランドスケープ+2/3ボタンナビゲーションの実機で実際にボタン・シークバーが操作可能になったかの目視確認はできていない（D-003以来一貫した既知の制約）。修正の妥当性は`WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)`というAndroid公式APIの一般的な使用方法との整合性、コードレビューでの確認に留まる。

### 次回開始位置
- 特になし。T-010bの完了によりT-010（親タスク）も完了に戻る。実機・エミュレータが利用可能になった時点で、本タスク・T-010双方の修正が実際にシステムバーとの重なりを解消しているかの目視確認を行うことが望ましい。

### コミット
- 本タスクの変更（コード・docs/tasks.md含む）はコミット済み（コミットハッシュ`59f0530`、コミットメッセージ先頭行: `T-010b: ランドスケープ+レガシーナビゲーションバーでの横方向inset未対応を修正する`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

## 2026-08-21 T-010 実機フィードバック対応: edge-to-edge表示でシステムUIと画面端の要素が重なる

### 実施内容
- ユーザーが実機にAPKをインストールして動作確認したところ、画面上部の「地図」「インポート」タブ・日/週/月/年の期間選択タブがステータスバーと、画面下部の「動画として保存」ボタンがナビゲーションバーとそれぞれ重なり操作不能になっている、と実機スクリーンショット付きで報告された。
- `MainActivity.kt`・`ui/`配下を読み、原因を特定した。T-002導入時点から`enableEdgeToEdge()`が呼ばれておりウィンドウはシステムバー背後まで描画される設定になっていたが、Composeレイアウト側（`MainActivity.kt`のルート`Column`/`TabRow`、`TimelineScreen.kt`、`ImportScreen.kt`のいずれも）がシステムバー分の余白（`WindowInsets`）を一切確保していなかった。`app/build.gradle.kts`の`targetSdk = 36`はedge-to-edge強制の副次要因ではあるが、直接の原因は`enableEdgeToEdge()`導入時のinset padding実装漏れと判明した（詳細はdocs/decisions.md D-012）。
- D-012の決定に従い、システムバーに実際に隣接する要素にのみ個別に`windowInsetsPadding`を適用した。
  - `app/src/main/java/com/nagamaki0311/timeliner/MainActivity.kt`: 画面最上部の`TabRow`（「地図」「インポート」タブ）に`Modifier.windowInsetsPadding(WindowInsets.statusBars)`を追加。
  - `app/src/main/java/com/nagamaki0311/timeliner/ui/TimelineScreen.kt`: 画面最下部の「動画として保存」`Button`に`Modifier.windowInsetsPadding(WindowInsets.navigationBars)`を追加。
  - `app/src/main/java/com/nagamaki0311/timeliner/ui/ImportScreen.kt`: ルート`Column`に`Modifier.windowInsetsPadding(WindowInsets.navigationBars)`を追加（画面上部はMainActivity側のTabRowの下に位置するため上側の対応は不要）。
- `TimelineScreen.kt`の`PeriodSelector`（日/週/月/年タブ）・`MapContainer`（地図本体）・`PlaybackControls`は個別の対応をしていない。いずれもMainActivity側のTabRow（ステータスバー対応済み）とTimelineScreen側のButton（ナビゲーションバー対応済み）に常に挟まれる位置にあり、Column構成上システムバーと直接接することがないため、地図表示面積への追加の影響なしにタブ・ボタンの操作可能性を確保できると判断した。
- `ExportDialog.kt`・`ImportScreen.kt`内の上書き確認`AlertDialog`は確認のみ行い変更していない。Compose Material3の`AlertDialog`は独自の`Dialog`ウィンドウ上に表示され、既定でシステムバー背後まで描画されない（`decorFitsSystemWindows`が既定のtrueのまま）ため、ホストActivityの`enableEdgeToEdge()`設定の影響を受けないという一般的なAndroidの`Dialog`ウィンドウの仕様に基づく判断である。

### 結果
- `./gradlew testDebugUnitTest`成功。今回の変更はいずれもCompose UI（`Modifier`のみ）でAndroid API依存のためJVM単体テスト対象外（D-003と同種の制約）、新規テストは追加していない。
- `./gradlew assembleDebug`成功。

### 懸念点（保守的判断で進めた不明点、Auto Mode下）
- 実機・エミュレータが本開発環境に無いため、修正後の見た目（タブ・ボタンがシステムバーと重ならなくなったか、地図表示面積が不自然に縮小していないか）を目視確認できていない（D-003以来一貫した既知の制約）。修正の妥当性は、Android公式のedge-to-edge対応ドキュメント・`WindowInsets` APIの一般的な使用方法との整合性、レイアウト構成上の論理的な検証（MainActivity.ktの`Column`のweight計算上、地図の表示面積はシステムバー余白の消費箇所を変えても変わらないこと）に留まる。
- `AlertDialog`がシステムバー背後まで描画されないという判断は、Compose Material3・AndroidのDialogウィンドウの一般的な仕様に基づく調査結果であり、実機での目視確認では検証していない。

### 次回開始位置
- 特になし。T-010完了によりdocs/tasks.mdの全タスク（T-001〜T-010）が完了する。実機・エミュレータが利用可能になった時点で、本タスクの修正が実際にシステムバーとの重なりを解消しているか、地図表示面積に不自然な縮小がないかの目視確認を行うことが望ましい。

### コミット
- 本タスクの変更（コード・docs/tasks.md・docs/decisions.md・本エントリ含む）はコミット済み（コミットハッシュ`a99ea6d`、コミットメッセージ先頭行: `T-010: edge-to-edge表示でシステムUIと画面端の要素が重なる不具合を修正する`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

## 2026-08-20 Manager: v1完了判定

### 実施内容
- T-001〜T-009（および修正ループT-003b/T-004b/T-005b/T-006b/T-007b/T-008b）がすべて完了し、Reviewerによる最終レビュー（T-009対象、プロジェクト全体の完了条件も横断確認）でCritical/High/Medium指摘0件・承認を得たことを受け、Managerとして完了判定を行った。

### 結果
- ユーザー提示の完了条件9項目（タイムラインJSON読み込み／地図上へのルート表示／期間指定／自然なアニメーション／冗長にならない再生速度の選択・自動設定／動画保存／大量データでの実用速度／APKビルド・インストール／重大エラーなし）はいずれも実装済みで、コードレビュー・JVM単体テスト（129件全パス）・ビルド成果物（`app-debug.apk`/`app-release-unsigned.apk`）で裏付けられていることを最終Reviewerが確認した。
- 本開発セッションの環境にAndroid実機・エミュレータが無いため「実機での動作確認」は未実施であり、既知の制約としてREADME.md「既知の制約」節・docs/decisions.md各Dに一貫して記録済み。
- 開発全体を通じ、8ステップ（T-002〜T-009）中6ステップでReviewerが実際の不具合（High: Mercator投影距離の実距離判定への誤用、緯度依存の座標変換スケール誤り、UIスレッドでの無制限Douglas-Peucker実行、JSON nullでのパース失敗、複数データ源の時系列マージ欠如／Medium: daysテーブルの無警告上書き、SQLite変数上限超過、Paint/Pathの毎フレーム再生成、再生中シークの競合、MediaStoreロールバック欠如、snapshotタイムアウト欠如等）を検出し、都度D-004〜D-011として対応方針を決定・修正・再検証するループを回した。Low/Nit相当（拡張性のみを理由とする指摘、発生頻度が極めて低いエッジケース等）はREVIEW.mdの過剰指摘抑制ルールに従いdocs/tasks.mdのバックログへ記録するに留め、修正サイクルは回さなかった。

### 次回開始位置
- 特になし。v1の計画済みタスクはすべて完了。今後の着手候補はdocs/tasks.mdのバックログを参照（実データでの検証、rawSignals対応等、いずれも優先度未確定）。
- ユーザーが実データ（個人の位置情報エクスポートファイル）での検証、または実機/エミュレータでの動作確認を行いたい場合、結果に応じて追加のdeveloper/reviewerサイクルを回す。

---

## 2026-08-20 T-009 仕上げ（エラー処理・a11y・性能確認・README）

### 実施内容

**1. エラーハンドリング点検**
- `ImportScreen`/`TimelineScreen`/`ExportDialog`とその裏側（`TimelineViewModel`/`TimelineRepository`/`ImportSource`/`TimelineJsonParser`/`VideoExporter`/`VideoOutput`）を横断的に確認した。壊れたファイル（不正JSON・不正zip）、空データ（0点0セグメント、既知のNitとしてD-006で見送り済み）、ストレージ不足（DB書き込み・MediaStore書き込みの両方で`try/catch`によりUIへ伝播）、ファイルアクセス失敗（`SecurityException`等）はいずれも既存の`try/catch (CancellationException) → throw` / `catch (Exception) → UiState.Error`パターンで捕捉されクラッシュしないことを確認した（minSdk 29のためストレージ関連のランタイム権限リクエストは元々不要、SAFファイルピッカーのキャンセルは`uri?.let{}`で単に無視される設計）。
- **発見・修正**: `TimelineViewModel.loadRoute`（`init`時と`selectPeriod`のたびに呼ばれる、`TimelineScreen`表示のたびに実行される中核パス）に`try/catch`が一切無いことを発見した。`repository.queryDays`が`SQLiteException`（DB破損等）や`PointBlobCodec.decode`の`require`失敗（BLOBサイズ不正、理論上は自前で書いたデータのみのため通常発生しないが、DBファイルの外部改変・破損時には起こりうる）を投げると、`viewModelScope.launch`内の未捕捉例外としてアプリがクラッシュする経路だった。これは「地図」タブを開くたび・期間を切り替えるたびに通る主要パスであり、他の箇所（`importFrom`/`commitPreparedImport`/`exportVideo`）が一貫して守っている「クラッシュさせずUIへ伝える」という原則から外れていたため、`app/src/main/java/com/nagamaki0311/timeliner/ui/TimelineViewModel.kt`の`loadRoute`を`try/catch(CancellationException) → throw` / `catch(Exception) → ログ出力しnull（データ無し扱い）`で囲むよう修正した。既存の`ImportUiState`/`ExportUiState`と異なり、ルート読み込み失敗専用のUI状態は新設せず「データ無し（`null`）」という既存の正常系の表現に合流させた（該当期間にデータが無い場合と見分けはつかないが、クラッシュを防ぐことが目的であり、新規UI状態追加は過剰と判断、AGENTS.md判定ラダー1）。

**2. アクセシビリティ確認・修正**
- 全Compose画面（`ImportScreen`/`TimelineScreen`/`PlaybackControls`/`PeriodSelector`/`ExportDialog`）を確認した。`Icon`/`IconButton`は1つも使われておらず、すべてのボタン・タブがテキストラベル（`Text`）を持つため、標準的なスクリーンリーダー（TalkBack）は各要素の役割を読み上げられる。カスタムView（`RouteOverlayView`）は`onTouchEvent`を実装しておらず描画専用で、タッチ操作を提供しない（タスク指示の想定通り）。
- タッチターゲットサイズ: すべてMaterial3標準コンポーネント（`Button`/`TextButton`/`Tab`/`Slider`）を素のまま使っており、独自の小さい`Modifier.size`指定は無いため、Material3の既定最小タッチターゲット（48dp）がそのまま適用される。
- 文字サイズ: Compose側のテキストはすべて`MaterialTheme.typography.*`（既定でsp単位）を使っており、`dp`でのフォントサイズ指定は無い（システムの文字サイズ設定に追従する）。`RouteFrameRenderer`（`android.graphics.Canvas`へ地図オーバーレイ・動画フレームとして焼き込む日時テキスト等）はpx単位の`Paint.textSize`を使っているが、これは端末の地図表示・動画のピクセル座標に対して物理的に一定の大きさで焼き込む必要がある装飾要素であり、システムの動的文字サイズに追従すべき「読み上げ対象のUIテキスト」ではないため対象外と判断した（画面上の対応する情報＝現在データ日時は`PlaybackControls`の`Text`としてもComposeで別途表示済みで、そちらはsp単位で動的文字サイズに追従する）。
- **発見・修正**: `PlaybackControls`の`Slider`（シークバー）に説明が無く、TalkBackでは進捗値のみが読み上げられ「何を操作しているか」が伝わらなかったため、`Modifier.semantics { contentDescription = "再生位置" }`を追加した。

**3. 大量位置情報での性能確認**
- 一時的なベンチマークテスト（`EndToEndPerformanceBenchmark.kt`、T-004/T-005と同じ「実行して記録後に削除する」方針）を追加し、合成データ（15万点、21日分に相当するランダムウォーク＋20,000点ごとに7時間欠損、Records.json形式のJSONテキスト約12MB）に対して以下を計測した後、削除した。
  - `TimelineJsonParser.parseJson`: 約1.6秒（150,000点）
  - `TrackCleaner.clean`: 約190ms（出力108,377点、8セグメント）
  - `TimelineRepository.buildPreparedImport`（日単位分割＋上書き検出、DB無し）: 約151ms（24日分。日付境界の関係で21日の範囲が24日分の`days`行に分かれた）
  - `PointBlobCodec.encode`（全日分）: 約14ms、合計約1.65MB
  - `PointBlobCodec.decode`（全日分）: 約4ms
  - 期間結合（`TimelineViewModel.mergeDayPoints`相当、年表示等で複数日を結合する処理）: 約0ms（108,377点）
  - `Simplifier.simplify`（`RouteOverlayView`/`VideoExporter`と同じ`maxPointCount=3000`、広域表示を想定したepsilon）: 約100ms、出力1,214点
  - `PlaybackTimeline.buildAuto`（画面再生・動画書き出し共通の写像構築）: 約24ms
  - 合計（パース〜クリーニング〜日次分割〜BLOBエンコード〜期間結合〜簡略化〜再生写像構築）: 約2.1秒
  - **計測できなかった部分（既知の制約、D-003と同種）**: 実際のSQLite `INSERT`トランザクション、MapLibreのタイル描画・カメラ操作・実際の`Simplifier`呼び出し頻度（`OnCameraMoveListener`経由）、Media3 Transformerの実エンコード、`Canvas.onDraw`の実描画フレームレートは、いずれも実機/エミュレータが必要なためJVM単体テスト環境では計測不可。UIの体感速度そのものは未確認である旨をREADME「既知の制約」にも明記した。
  - 上記より、15万点・複数週にまたがる規模でも「パース〜表示直前までの前処理」が約2秒程度で完了することを確認し、T-004（10万点・113ms、クリーニング〜簡略化のみ）・T-005（10万点・1.9秒、パース〜BLOBエンコードまで）の既存計測値と整合する傾向（点数に対しほぼ線形、実用的な速度）であることを確認した。

**4. APKビルドの最終確認**
- `./gradlew assembleDebug`成功、`app/build/outputs/apk/debug/app-debug.apk`（デバッグ署名済み）を`/opt/android-sdk/build-tools/36.1.0/aapt dump badging`で検証し、`package name='com.nagamaki0311.timeliner'`・`minSdkVersion:'29'`・`targetSdkVersion:'36'`等が正しく認識できる正常なAPK形式であることを確認した。
- **発見・修正**: `aapt dump badging`の出力に、本アプリの`AndroidManifest.xml`には一切記載していない`ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`（ランタイム許可を要する「dangerous」権限）が含まれていることを発見した。調査の結果、MapLibre Native Android SDK自身のマニフェストが任意機能（現在地表示、本アプリは未使用）向けに宣言しており、マニフェストマージで自動的に統合されていたと判明した（本アプリのコードに位置情報APIの呼び出しが無いことをgrepで確認済み）。要件が明記する「位置情報データを端末内で完結させる」というプライバシー重視の設計意図と整合しないため、`AndroidManifest.xml`に`tools:node="remove"`でこの2権限を除外した（詳細・理由はdocs/decisions.md D-011）。除外後も`INTERNET`/`ACCESS_NETWORK_STATE`等の地図タイル取得に必要な権限は保持され、ビルド・`aapt dump badging`とも問題ないことを確認した。
- `./gradlew assembleRelease`を実行したところ**成功**した（失敗しなかった）。`signingConfig`未設定のため`app/build/outputs/apk/release/app-release-unsigned.apk`（R8難読化・圧縮済みだが未署名）が生成される。未署名のAPKはAndroidにインストールできないため、実機配布には別途keystoreでの署名が必要（本タスクではkeystoreを新規作成・コミットしない指示のため、署名手順のみREADMEに記載した）。

**5. README.mdの整備**
- `README.md`に「Androidアプリのビルド」（必要環境・`assembleDebug`・単体テスト）、「実機へのインストール」（`adb install`・手動転送）、「対応しているタイムラインJSON形式」（4形式の一覧表とエクスポート方法）、「リリース署名」（keystore作成・`signingConfigs`追加例・環境変数経由でのパス/パスワード注入、keystoreファイル自体はコミットしない旨を明記）、「既知の制約」（実機/エミュレータ未確認、実データ未検証、大量データの体感速度未確認）を追記した。末尾の「アプリ本体のソースコード構成」プレースホルダも、実装済みパッケージ構成（`data/parser`/`model`/`process`/`store`/`playback`/`render`/`export`/`ui`）の概要で埋めた。既存の開発方針・Agent構成に関する記述（AGENTS.md/REVIEW.md等への参照、Agent構成の説明）は変更していない。

**6. 完了条件の総点検**
- docs/tasks.mdのT-001〜T-008bはすべて「完了」であることを確認した（本エントリ末尾のタスク一覧更新でT-009も「完了」にする）。
- docs/decisions.mdに記録された既知の制約・見送り事項（D-002の実データ未検証、D-003以来の実機/エミュレータ未確認、D-006/D-007/D-008/D-010bのLow/Nit見送り項目）を再確認したところ、いずれも「機能的な破綻が無い」「発生頻度が低い」「統計表示等の副次的な誤差に留まる」という理由で意図的に見送られたものであり、ユーザーの元の要件（タイムラインJSONの読み込み、地図上への移動ルート表示、期間指定、アニメーション再生、速度制御、動画書き出し、大量位置情報での実用速度、APKビルド・実機インストール可能）を損なうものは無いと判断した。
- 実機/エミュレータでの動作確認・実データでの検証は、本開発環境（Android実機・エミュレータが利用できないサンドボックス）の制約により本タスクでも実施できていない。これはD-002・D-003以来一貫して記録されている既知の制約であり、README.md「既知の制約」に集約して明記した。

### 結果
- `./gradlew testDebugUnitTest`成功（既存129件、本タスクでのコード変更（`TimelineViewModel.loadRoute`のtry/catch追加、`PlaybackControls`のSlider contentDescription追加、`AndroidManifest.xml`の権限除外）はいずれもAndroid API依存またはCompose UI依存のためJVM単体テストの対象外（D-003と同種の制約）で新規テストは追加していない。一時追加したベンチマークテストは記録後に削除済み）。
- `./gradlew assembleDebug`成功。`aapt dump badging`でAPKの妥当性・マニフェスト内容（位置情報権限の除外含む）を確認した。
- `./gradlew assembleRelease`成功（未署名APK生成、署名は本タスクのスコープ外）。

### 懸念点（保守的判断で進めた不明点、Auto Mode下）
- `TimelineViewModel.loadRoute`の失敗時、専用のエラーUI状態を新設せず「データ無し」に合流させた設計は、DB破損等の重大な問題が発生していてもユーザーからは単に「その期間にデータが無い」ように見え、根本原因（DB破損）に気づきにくいというトレードオフがある。`android.util.Log.w`でログには残るため開発者は気づけるが、エンドユーザー向けの通知は無い。要件上、ルート表示専用のエラーバナー等の追加UIが求められる場合は将来の拡張として検討が必要（今回はクラッシュ防止を主目的とし、新規UI状態追加は過剰と判断し見送った）。
- README「リリース署名」節に示した`signingConfigs`の追加例はドキュメント上の記載のみで、`app/build.gradle.kts`への実際の反映は行っていない（本タスクの指示が「keystoreの作成方法、build.gradle.ktsへの設定方法の概要」を求めており、実際の署名鍵作成・コミットを求めていないため）。

### 次回開始位置
- T-009完了によりdocs/tasks.mdの全タスク（T-001〜T-009）が完了する。以降は実機/エミュレータでの目視確認・実データでの検証（いずれもD-002/D-003以来の既知の制約）が利用可能になった時点で追加のdeveloper/reviewerサイクルを行うことが望ましい。

### コミット
- 本タスクの変更（コード・README・docs/tasks.md・docs/decisions.md・本エントリ含む）はコミット済み（コミットハッシュ`2ebd9bd`、コミットメッセージ先頭行: `T-009: 仕上げ（エラー処理・a11y・性能確認・APK・README）を実施する`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

## 2026-08-20 T-008b T-008レビュー指摘の修正（MediaStoreロールバック・snapshotタイムアウト・単一点ルートのエラー文言）

### 実施内容
D-010の決定に従い、以下3件を修正した。

- **Medium: MediaStore書き込みのロールバック欠如**
  - `app/src/main/java/com/nagamaki0311/timeliner/export/VideoOutput.kt`の`saveToMediaStore`に`onUriCreated: (Uri) -> Unit = {}`引数を追加し、`insert`成功直後（コピー開始前）に発行済みURIを呼び出し元へ通知するようにした。`openOutputStream`〜`copyTo`〜`IS_PENDING`更新の一連の処理を`try/catch(Exception)`で囲み、失敗時は`resolver.delete(itemUri, null, null)`でロールバックしてから例外を再送出する。
  - `app/src/main/java/com/nagamaki0311/timeliner/ui/TimelineViewModel.kt`の`exportVideo`で、`saveToMediaStore`呼び出し時に`onUriCreated`ラムダで発行済みURIを`mediaStoreUri`（launch内のローカル変数）に記録し、`catch (e: CancellationException)`ブロックで非nullなら`withContext(Dispatchers.IO + NonCancellable) { appContext.contentResolver.delete(uri, null, null) }`で削除するようにした（`NonCancellable`はキャンセル済みコルーチン内でも後始末の`delete`呼び出し自体は完了させるため）。コピーが完了しMediaStore行が`IS_PENDING=0`になった後にキャンセルが伝播した場合（`withContext`から呼び出し元へ戻る際の中断点）も、この経路でロールバックされる。
- **Medium: snapshotタイムアウト欠如**
  - `app/src/main/java/com/nagamaki0311/timeliner/export/VideoExporter.kt`の`awaitSnapshot`を`withTimeout(SNAPSHOT_TIMEOUT_MILLIS)`（10秒、新設のcompanion定数）で包んだ。`withTimeout`が投げる`TimeoutCancellationException`は`kotlinx.coroutines.CancellationException`のサブクラスであり、そのまま伝播させると`TimelineViewModel`の`catch (e: CancellationException)`（ユーザーによるキャンセル扱い、`ExportUiState.Idle`へ遷移）に吸収されてしまいエラー表示されなくなるため、`awaitSnapshot`内で`catch (e: TimeoutCancellationException)`し`ExportFailedException`（通常の`Exception`）へ変換して再送出するようにした。これにより`TimelineViewModel`の`catch (e: Exception)`分岐で`ExportUiState.Error`として表示される。
- **Medium: 単一点ルートでの内部例外メッセージ露出**
  - `TimelineViewModel.exportVideo`の冒頭（`route`のnullチェック直後）で`route.latitudes.size < 2`を検出し、「この期間はデータが少なく動画を作成できません」を`ExportUiState.Error`へ設定して`return`するガードを追加した。`VideoExporter.export`内部の`require(durationMs > 0)`はそのまま残し（2点以上でも理論上durationMsが0になりえないことのアサーションとして機能させる、AGENTS.md原則6）、通常の呼び出し経路ではこのガードにより到達しない設計にした。

### 結果
- `./gradlew testDebugUnitTest`成功（既存129件、影響ファイルはいずれもAndroid API依存の関数内の変更のみで新規純Kotlinロジックは追加していないため新規テストなし）。
- `./gradlew assembleDebug`成功。
- 点数チェック（修正3）は`TimelineViewModel.exportVideo`内の1行の条件分岐であり、Android依存（`Context`/`MapLibreMap`）の関数内に埋め込まれているため独立した単体テストは追加しなかった（`route.latitudes.size < 2`というトリビアルな比較のためだけに専用の純Kotlin関数へ抽出することは、AGENTS.md判定ラダー1（YAGNI）・原則5（投機的な抽象化を追加しない）に照らし過剰と判断した）。ロールバック処理（`saveToMediaStore`のtry/catch、`TimelineViewModel`の`NonCancellable`削除）・snapshotタイムアウト（`withTimeout`）はいずれも`ContentResolver`/`MapLibreMap`/コルーチンの実時間待機に依存するため、D-003以来の制約と同様にJVM単体テストの対象外（コードレビューによる確認に留まる）。
- 実機/エミュレータでの目視確認（ロールバックが実際にMediaStoreへ反映されるか、`MapView`が`started`でない場合のタイムアウトが実際に働くか）は本セッションでは未実施（環境制約、T-002以降一貫した既知の制約）。

### 次回開始位置
- T-009（仕上げ: エラー処理・a11y・性能確認・README）に着手する。T-008/T-008bの実機確認未実施（D-009参照）を踏まえ、実機/エミュレータが利用可能になった時点で動画書き出し・MediaStoreロールバック・snapshotタイムアウトの目視確認を行うことが望ましい。

### コミット
- 本タスクの変更（コード・docs/tasks.md・本エントリ含む）はコミット済み（コミットハッシュ`12956a0`、コミットメッセージ先頭行: `T-008b: MediaStoreロールバック・snapshotタイムアウト・単一点ルートのエラー文言を修正する`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

## 2026-08-20 T-008 アニメーションの動画書き出し

### 実施内容
- **スパイク検証（実装着手前、D-002懸念事項への回答）**: 実機/エミュレータが無い環境制約（D-003以来一貫）のため、Maven Centralから`androidx.media3:media3-transformer/media3-effect/media3-common:1.11.0`のaarを取得し`javap`でAPIシグネチャを確認した上、`github.com/androidx/media`（Media3本体）の実際のソースコード（`ImageAssetLoader.java`・`OverlayShaderProgram.java`・`BitmapOverlay.java`）を直接読み、静止画入力+`BitmapOverlay`が「毎フレーム更新されるオーバーレイ」として機能することを確認した（結論・根拠の詳細はdocs/decisions.md D-009）。懸念されていた「Transformerが同一フレームを最適化・重複排除する」事象は発生しない設計であることが判明したため、D-002の方式（静止画+BitmapOverlay）をそのまま採用し、代替方式への切り替えは行わなかった。
- 依存追加: `gradle/libs.versions.toml`（`media3=1.11.0`、`androidx-media3-transformer`/`androidx-media3-effect`/`androidx-media3-common`）、`app/build.gradle.kts`（上記3つを`implementation`追加）。
- `app/src/main/java/com/nagamaki0311/timeliner/render/RouteFrameRenderer.kt`: 公開関数`progressAtDataTime(timestampsMillis, dataTimeMillis): Float`を新設。旧`RouteOverlayView.currentProgress`内にあった「時刻→点インデックス位置」の二分探索＋線形補間ロジックをそのままここへ移設し、`RouteOverlayView.currentProgress()`はこの関数へ委譲するよう変更した（画面再生と動画書き出しの両方が同じ進捗計算を使う設計、D-008の「重複実装を解消する」方針を踏襲）。`Canvas`非依存の純Kotlin関数のためJVM単体テスト可能。
- `app/src/main/java/com/nagamaki0311/timeliner/export/RouteBitmapOverlay.kt`（新規）: `androidx.media3.effect.BitmapOverlay`の実装。`getBitmap(presentationTimeUs)`で`presentationTimeUs`（マイクロ秒）→再生経過ミリ秒→`PlaybackTimeline.dataTimeAtPlaybackMillis`でデータ時刻→`RouteFrameRenderer.progressAtDataTime`で進捗、の順に変換し、`RouteFrameRenderer.draw`で透明背景のBitmap（1枚を使い回し、毎フレーム`eraseColor`でリセット）へルート線・マーカー・日時・地図帰属表示を描く。地図本体（下地）は描かず透明のまま返す設計（下地は`VideoExporter`が動画入力のMediaItemとして別途渡す地図スナップショットPNGがそのまま透けて見える。BitmapOverlayはGL側で下地の上にアルファ合成される）。
- `app/src/main/java/com/nagamaki0311/timeliner/export/VideoExporter.kt`（新規）: 地図スナップショット取得（`MapLibreMap.snapshot`、カメラ位置は要求と同時に読む）→出力解像度決定（`computeOutputResolution`、短辺1080px上限・拡大なし・偶数丸め、D-002決定6）→ルート点列の簡略化（`Simplifier.simplify`、`RouteOverlayView`と同じepsilon算出方針）・画面座標変換（`ScreenProjection`、スナップショット解像度に応じてmetersPerPixelを縮小率で補正）→スナップショットPNGの一時ファイル書き出し→`MediaItem`（`setImageDurationMs`）+`EditedMediaItem`（`setFrameRate(30)`）+`OverlayEffect([RouteBitmapOverlay])`を`Composition`に組み立て`Transformer.start`→`ProgressHolder`のポーリング（200ms間隔、`delay`でUIをブロックしない）で進捗コールバック→完了/失敗/キャンセルの3系統をハンドリングする。`Transformer`の構築・start・進捗取得・cancelは呼び出し元のディスパッチャ（Main、Looper制約）上でそのまま行い、CPU処理（簡略化・座標変換・スナップショットのスケーリング・PNG書き込み）のみ`Dispatchers.Default`へ逃がす。失敗時は`ExportFailedException`を投げる。中間ファイル（スナップショットPNG）は`export`関数の`finally`で必ず削除し、書き込み先の`outputFile`自体の削除は呼び出し元の責務とする設計にした（作成者が後始末する、という責務分担）。
- `app/src/main/java/com/nagamaki0311/timeliner/export/VideoOutput.kt`（新規）: `MediaStore.Video.Media`（`RELATIVE_PATH=Movies/timeliner`）への書き込み（`IS_PENDING`フラグで書き込み中を明示）と、共有（`ACTION_SEND`）・アプリで開く（`ACTION_VIEW`）用の`Intent`組み立て。minSdk 29前提のため`WRITE_EXTERNAL_STORAGE`権限・FileProviderは不要（タスク指示どおり）。
- `app/src/main/java/com/nagamaki0311/timeliner/ui/ExportDialog.kt`（新規、Compose）: 目標再生時間の選択（`SpeedMode.AUTO_DURATION_OPTIONS_MILLIS`を再利用、10/30/60/120秒）→進捗表示（`LinearProgressIndicator`）→完了後の「共有」「アプリで開く」ボタン、失敗時のエラー表示、を`ExportUiState`（Idle/InProgress/Success/Error）に応じて1つの`AlertDialog`内で切り替える。
- `app/src/main/java/com/nagamaki0311/timeliner/ui/TimelineViewModel.kt`: `ExportUiState`（sealed interface）と`exportState: StateFlow`を新設。`exportVideo(context, map, targetDurationMillis)`が現在選択中の期間のルート（`_routePoints.value`）から**常に自動速度モード**で新規`PlaybackTimeline`を構築し（画面再生中の速度モードとは独立、ExportDialogは目標再生時間のみを選ばせる設計のため）、`VideoExporter.export`→`VideoOutput.saveToMediaStore`まで`viewModelScope.launch`内で実行する。`cancelExport()`（`exportJob?.cancel()`）・`dismissExport()`（書き出し中は無視）も追加。中間ファイル（`outputFile`）は`finally`で必ず削除する（成功時はMediaStoreへコピー済みのため削除して問題ない、失敗・キャンセル時もこれで後始末される）。
- `app/src/main/java/com/nagamaki0311/timeliner/ui/TimelineScreen.kt`: `PlaybackControls`の下に「動画として保存」ボタン（地図・ルートが揃っている時のみ有効）を追加し、タップで`ExportDialog`を表示する導線を配線した。共有/開くボタンは`runCatching`で`Intent`起動失敗（対応アプリが無い等）を握りつぶしクラッシュしないようにした（AGENTS.md原則8）。

### 実装上の判断・懸念点（保守的判断で進めた不明点、Auto Mode下）
- `Bitmap.notifyPixelsChanged()`（当初D-002の想定どおり明示的にBitmapの変更をMedia3へ通知する想定だった）が現行SDK（`android-36`）の公開APIに存在しないことをコンパイルエラーで発見した。AOSP本体の`Bitmap.java`ソースを確認し、`getGenerationId()`のJavadoc「changes whenever the bitmap is modified」から、`Canvas`描画によるBitmap変更は自動的にgenerationIdへ反映される（明示通知は不要）ことを確認し、該当行を削除して対応した（D-009に検証根拠を記録）。
- `EditedMediaItemSequence.Builder(EditedMediaItem...)`/`Builder(List<EditedMediaItem>)`はいずれもMedia3 1.11.0で`@Deprecated`（コンパイル時警告で発覚）だったため、非推奨でない`Builder(Set<Integer> trackTypes)`+`addItem(...)`（`trackTypes=setOf(C.TRACK_TYPE_VIDEO)`）へ変更した。
- 動画書き出しは常に自動速度モード（`PlaybackTimeline.buildAuto`）を使う設計とした。タスク指示のExportDialogが「目標再生時間の選択」のみを求めており手動倍率モードの選択には触れていないため、画面再生側の現在の速度モード（手動の可能性もある）とは独立に、書き出し時は毎回新しい`PlaybackTimeline`を自動モードで構築する。手動速度での書き出しをユーザーが求める場合は将来の拡張としてバックログに追加が必要（明示の要求が無いため今回はスコープ外とした、YAGNI）。
- `Transformer`の実際の動作（動画が正しくエンコードされるか、フレームごとにオーバーレイが更新されるか、MediaStoreへ正しく書き込まれるか）は実機/エミュレータでの目視確認ができておらず未実施（環境制約、D-003以来一貫）。D-009のソースコードレベルの検証がその代替。

### 結果
- `./gradlew testDebugUnitTest`が成功（既存118件+新規`RouteFrameRendererTest`6件+`VideoExporterTest`5件の計129件すべてパス）。
- `./gradlew assembleDebug`が成功（Media3依存追加後も問題なし）。
- テストの制約（D-003と同種）: `VideoExporter.export`本体・`RouteBitmapOverlay`・`VideoOutput`・`ExportDialog`はいずれも`MapLibreMap`/`Transformer`/`MediaStore`/Compose UIといった実機依存APIに直接依存するためJVM単体テスト不可。Android API非依存の純Kotlinロジック（`RouteFrameRenderer.progressAtDataTime`、`VideoExporter.computeOutputResolution`）のみ単体テストを追加した（タスク指示どおり）。

### 次回開始位置
- T-009（仕上げ: エラー処理・a11y・性能確認・README）に着手する。T-008の実機確認未実施（D-009参照）を踏まえ、実機/エミュレータが利用可能になった時点で動画書き出しの目視確認（フレームごとのオーバーレイ更新、地図帰属表示の焼き込み、MediaStore登録後の再生・共有）を行うことが望ましい。

### コミット
- 本タスクの変更（コード・テスト・docs/tasks.md・本エントリ含む）はコミット済み（コミットハッシュ`f62a026`、コミットメッセージ先頭行: `T-008: アニメーションの動画書き出しを実装する`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

## 2026-08-20 T-007b T-007レビュー指摘の修正（再生中シークの競合、trimByProgress二重計算）

### 実施内容
- D-008決定1（Medium）: `app/src/main/java/com/nagamaki0311/timeliner/playback/PlaybackController.kt`の`seekTo()`冒頭で`pause()`を呼ぶよう変更。再生中にシークバーをドラッグしても再生ループ（`playbackJob`）が停止するため、ユーザーのシーク値と16msごとの自動更新値が`elapsedPlaybackMillis`を奪い合わなくなる。
  - シーク後に再生を継続したい場合は呼び出し元が明示的に`play()`を呼ぶ設計（決定どおり）。`app/src/main/java/com/nagamaki0311/timeliner/ui/PlaybackControls.kt`に`onSeekFinished: () -> Unit`パラメータを追加し、`Slider`の`onValueChangeFinished`に接続。`app/src/main/java/com/nagamaki0311/timeliner/ui/TimelineScreen.kt`側でドラッグ開始時点の`playbackState.isPlaying`を`resumePlaybackAfterSeek`として記憶し（`isSeeking`フラグでドラッグ中の複数回の`onSeek`呼び出しから最初の1回だけを判定）、`onSeekFinished`で真なら`viewModel.play()`を呼んで再開する。一般的な動画プレーヤーのシークバーUX（ドラッグ中は再生停止、離すと元の再生状態に戻る）に合わせた。
- D-008決定2（Low）: `app/src/main/java/com/nagamaki0311/timeliner/render/RouteFrameRenderer.kt`の`draw()`が`trimByProgress`を1回だけ呼び、その結果（`FloatArray`）をルート線描画（`drawRoute`）と現在位置マーカー（新設のprivate `markerPosition()`、旧`currentPositionAtProgress`のロジックを移設）の両方で使い回すようリファクタ。`draw()`の引数から`currentPositionScreen: ScreenPoint?`を削除し（マーカーは常に`trimmed`の末尾点から導出するため呼び出し元が別途渡す必要がなくなった）、公開関数だった`currentPositionAtProgress`も削除。呼び出し元は`RouteOverlayView.onDraw`のみで、T-008未着手のため他に影響なし。`app/src/main/java/com/nagamaki0311/timeliner/render/RouteOverlayView.kt`の`onDraw`を新シグネチャに合わせて簡略化。

### 結果
- `./gradlew testDebugUnitTest`成功（既存`PlaybackTimelineTest`含め全件パス、影響ファイルはいずれもAndroid API依存でJVM単体テスト対象外のため新規テストなし）。
- `./gradlew assembleDebug`成功。
- 決定1（`seekTo`のシーク中再生停止・再開）はAndroidの`CoroutineScope`/Compose `Slider`ジェスチャーに依存するため、タスク指示どおりJVM単体テストでの検証は行わず、`pause()`を再利用する形（ロジックの共有）でレビュー時にコードから正しさを確認できるようにした。実機/エミュレータでの目視確認は本セッションでは未実施（環境制約、既知の制約を継続）。

### 次回開始位置
- T-008（アニメーションの動画書き出し）に着手する。`RouteFrameRenderer.draw`のAPIが`currentPositionScreen`引数無しに変わった点を踏まえて実装すること。

### コミット
- 本タスクの変更（コード・docs/tasks.md・本エントリ含む）はコミット済み（コミットハッシュ`23ddcb4`、コミットメッセージ先頭行: `T-007b: 再生中シークの競合とtrimByProgressの二重計算を修正する`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

## 2026-08-20 T-007 アニメーション再生と速度制御

### 実施内容
- `app/src/main/java/com/nagamaki0311/timeliner/playback/PlaybackTimeline.kt`（純Kotlin、Android API非依存）: データ時刻↔再生時刻の単調写像。内部表現は「区切り点の配列（`dataMillis[]`/`playbackMillis[]`、共に非減少）＋二分探索＋線形補間」（`LongArray.binarySearch`を使い、両配列を1つの`interpolate`関数で共用）。コンストラクタで両配列の非減少性を`require`で検証する（AGENTS.md原則6、二分探索の前提が壊れたら即座に失敗させる）。
  - `buildAuto(timestampsMillis, latitudes, longitudes, targetDurationMillis, alpha=1.0, beta=1000.0)`: 関心度=α×経過ミリ秒+β×移動距離メートル（`Mercator.haversineDistanceMeters`、D-005の使い分けに従い実距離を使用）の累積を`targetDurationMillis`に正規化する。丸め誤差は最終点のみ強制的に一致させて吸収する（この補正は多点かつ関心度が正の場合のみ適用し、点数1の退化ケースには適用しない誤りを実装中に発見・修正済み。後述「懸念点」参照）。
  - `buildManual(timestampsMillis, speedMultiplier)`: 先頭点からの経過データ時間を`speedMultiplier`で割った一定倍率の写像。
  - `dataTimeAtPlaybackMillis`/`playbackMillisAtDataTime`の両方向を実装（タスク指示が明示的に両方向を要求していたため、公開API例には後者が無かったが追加した）。
- `app/src/test/java/com/nagamaki0311/timeliner/playback/PlaybackTimelineTest.kt`: 単調性（137ms刻みで再生時刻を走査しデータ時刻が後退しないこと）、端点、自動モードの総再生時間正規化、自動モードでの滞在圧縮/移動区間の速度比較、手動モードの倍率、点数1・2・同一時刻2点の境界値、不正入力（速度0以下・目標時間0以下・空配列）を検証する14件を追加。
- `app/src/main/java/com/nagamaki0311/timeliner/playback/PlaybackController.kt`: `PlaybackTimeline`を保持し再生・一時停止・シークを管理する。再生ループは`viewModelScope`（呼び出し元が渡す`CoroutineScope`）内で`delay(16ms)`のコルーチンループとして実装した（タスク指示「実装しやすい方でよい」に従い、Compose側の`withFrameNanos`は使わずこの方式のみを採用）。`SpeedMode`（`Auto(targetDurationMillis)`/`Manual(speedMultiplier)`）を`setSpeedMode`で切り替えると`PlaybackTimeline`を再構築する。総再生時間0（点数1等）では`play()`を無視するガードを追加。同ファイルに`PlaybackTimeFormat`（データ時刻のepochミリ秒→`yyyy/MM/dd HH:mm:ss`表示用フォーマット）を新設し、UI（`PlaybackControls`）と地図オーバーレイ（`RouteOverlayView`経由）の両方から共用する（新規ファイルを増やさず既存ファイルへ同居させた）。
- `app/src/main/java/com/nagamaki0311/timeliner/render/RouteFrameRenderer.kt`: 既存の`trimByProgress`（private）を呼び出す公開関数`currentPositionAtProgress(screenCoordinates, progress)`を追加。ルート線の終端（`trimByProgress`の末尾点）と現在位置マーカーの座標を必ず一致させるための共通化（呼び出し元が別々に計算すると進捗の丸め方によってズレうる）。
- `app/src/main/java/com/nagamaki0311/timeliner/render/RouteOverlayView.kt`: `setPlaybackDataTimeMillis(dataTimeMillis: Long?)`を追加。簡略化済み点列の時刻配列（`cachedSimplifiedTimestamps`、Douglas-Peucker後の点に対応する時刻をズームバケット変化時にのみキャッシュ、既存の`cachedSimplifiedWorldXs/Ys`と同じタイミングで更新）に対し、現在データ時刻を二分探索＋線形補間で「進捗（0.0〜1.0）」へ変換する`currentProgress()`を実装。`onDraw`は`progress=1f`固定をやめ、この`currentProgress()`と`RouteFrameRenderer.currentPositionAtProgress`を使うよう変更した。`playbackDataTimeMillis`未設定（null）時はT-006までと同じ「全区間表示」（進捗1.0）にフォールバックする。
- `app/src/main/java/com/nagamaki0311/timeliner/ui/PlaybackControls.kt`（新規）: 再生/一時停止ボタン、シークバー（`Slider`、`state.progress`と双方向）、速度モード切り替え（自動/手動の2択＋各モードの候補値、いずれもテキストボタンで選択中のものを太字強調。アイコンフォント等の新規依存は追加しない）、現在のデータ日時表示。現在地の地名表示は行わない（`TimelineSegment.placeId`はDB上にあるが`routePoints`（点列のみ）に紐付いておらず追加の突合ロジックが必要なため、タスク指示の「無ければ省略してよい」に従い今回は見送り、既知の制約としてコード内コメントに明記）。
- `app/src/main/java/com/nagamaki0311/timeliner/ui/TimelineViewModel.kt`: `PlaybackController(viewModelScope)`を保持し`playbackState: StateFlow<PlaybackController.State>`を公開。`loadRoute`が期間切替のたびに`playbackController.setRoute(...)`（データ無しの期間は空配列）を呼び`PlaybackTimeline`を再構築する。`play`/`pause`/`seekTo`/`setSpeedMode`をそのまま委譲する薄いラッパーを追加。
- `app/src/main/java/com/nagamaki0311/timeliner/ui/TimelineScreen.kt`: `PlaybackControls`を地図の下に配置。`playbackState.dataTimeMillis`を`RouteOverlayView.setPlaybackDataTimeMillis`へ、地図下部の日時テキストも「再生中のデータ時刻（`PlaybackTimeFormat`）優先、未設定時は期間ラベル」に変更した（T-008の動画書き出しでも同じ`RouteFrameRenderer`が焼き込みに使う想定のため、画面表示を先に animation-aware にしておく設計判断）。

### 自動モードの挙動（設計意図の記録、タスク指示の要件）
自動モードは「関心度」（`α×経過ミリ秒 + β×移動距離メートル`、既定値`α=1.0, β=1000.0`）の累積に対して再生時刻を等速に進める。既定値は「1mの移動 ≈ 1000msの滞在」と等価に扱う設定であり、GPS点間隔が数秒〜数十秒程度の一般的なトラッキング頻度において、数時間の滞在（関心度への寄与はほぼ`α×dt`のみ）は数百ミリ秒〜数秒の再生時間に圧縮される一方、実際に移動している区間（`β×distance`が加算される）は相対的に間延びしにくく、結果として「滞在・夜間は早送り、移動中は自然な速度に近い」という要件どおりの体感になる。総再生時間は常に`targetDurationMillis`（既定30秒、選択肢10/30/60/120秒）に正規化されるため、点数・移動距離の総量に関わらず動画の長さは一定になる。

### 結果
- `./gradlew testDebugUnitTest`が成功（既存104件+新規`PlaybackTimelineTest`14件の計118件すべてパス）。
- `./gradlew assembleDebug`が成功。
- テストの制約（D-003/D-004/D-007と同種）: `PlaybackController.kt`はコルーチンの実時間ループ（`delay`/`System.nanoTime`）に依存し、`kotlinx-coroutines-test`（`runTest`等）が本プロジェクトに未導入のため単体テストを追加していない（新規テスト依存追加はAGENTS.md判定ラダー5「インストール済みの依存関係で解決できるか」に反するため見送った）。`RouteOverlayView.kt`/`RouteFrameRenderer.kt`は引き続き`android.view.View`/`android.graphics.Canvas`依存でJVM単体テスト対象外。タスク指示で明示された単体テスト要件（`PlaybackTimelineTest.kt`）は純Kotlinのため予定通り実装・全件パス済み。
- 実機/エミュレータでの目視確認（再生ボタン・シークバー操作、アニメーションの滑らかさ、速度モード切替の見た目）は本セッションでは未実施（環境制約、T-002以降一貫した既知の制約）。

### 懸念点（保守的判断で進めた不明点、Auto Mode下）
- `PlaybackTimeline.buildAuto`の実装中、丸め誤差補正（最終点を`targetDurationMillis`へ強制的に一致させる行）を`totalInterest<=0`の退化分岐にも無条件で適用してしまうと、点数1のケースで`totalPlaybackMillis()`が`targetDurationMillis`（本来は0であるべき）になり、かつ`dataTimeAtPlaybackMillis`が二分探索の範囲外アクセスで`IllegalArgumentException`を投げるバグを実装直後の自己レビューで発見し、`else`節（`totalInterest>0`、必ず2点以上）内のみに補正を限定する形で修正済み（テスト`buildAuto_singlePoint_hasZeroDurationAndReturnsThatPointsTimestamp`で担保）。
- 関心度の重み`α=1.0, β=1000.0`はタスク指示が「適当な既定値でよい」としていたため、実データでのチューニングは行っていない。極端に低頻度（点間隔が数分〜数十分）なGPSログでは、移動区間でも`α×dt`が支配的になり「移動中でも早送りされすぎる」体感になりうるが、これはGPS点間隔自体の粗さに起因するものであり、係数調整だけでは根本解決しない。実データでの検証（D-002項目2で既知の制約として記録済み）が可能になった時点で見直すのが妥当と判断し、今回は既定値のまま進めた。
- 地図上の日時テキスト（`RouteOverlayView.setDateTimeText`）を「未再生時は期間ラベル、再生中はデータ時刻」に変更したのはタスク指示に明記が無い拡張（元の指示は`TimelineScreen`の進捗・現在位置マーカーの反映のみを求めていた）だが、T-008で同じ`RouteFrameRenderer`が動画フレームへ同種のテキストを焼き込む設計（D-002）と整合させる目的で行った。挙動として不自然ではないと判断したが、Reviewerの確認を求める。

### 次回開始位置
- T-008（アニメーションの動画書き出し）に着手する。`PlaybackTimeline.dataTimeAtPlaybackMillis`は既にフレームタイムスタンプ→データ時刻の変換にそのまま使える設計（画面再生と同一の写像を共有、D-002）。`RouteFrameRenderer.draw`/`currentPositionAtProgress`も画面・動画共通で使う想定のまま流用できる。

### コミット
- 本タスクの変更（コード・テスト・docs/tasks.md・本エントリ含む）はコミット済み（コミットハッシュ`dde7393`、コミットメッセージ先頭行: `T-007: アニメーション再生と速度制御を実装する`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

## 2026-08-20 T-006b T-006レビュー指摘の修正（座標変換スケール・UIスレッドDP・Paint/Path再利用）

### 実施内容
D-007の決定に従い、以下を修正した（対象: `app/src/main/java/com/nagamaki0311/timeliner/render/`）。

- **High: 座標変換のスケール誤り**
  - `RouteOverlayView.recomputeAndInvalidate`の`metersPerPixel`取得を`currentMap.projection.getMetersPerPixelAtLatitude(target.latitude)`（緯度依存の実世界距離基準）から`getMetersPerPixelAtLatitude(0.0)`（緯度0固定）へ変更した。`Mercator.longitudeToX/latitudeToY`が返すワールド座標は緯度に依存しない一定スケールの投影座標であり、緯度0でのメートル/ピクセルがこの投影座標のスケールと一致するため（D-007決定1）。
- **High: UIスレッドでのDouglas-Peucker無制限同期実行**
  - `RouteOverlayView`の`Simplifier.simplify`呼び出しに`maxPointCount = SIMPLIFY_MAX_POINT_COUNT`（3000、companion定数として新設）を渡すようにした。DP自体の非同期化（`Dispatchers.Default`）はD-007決定2で必須とされていないため、今回は行っていない（`OnCameraMoveListener`のコールバック内で同期実行のままだが、入力点数の上限が保証されるため無制限実行によるANRリスクは解消される）。
- **Medium: Paint/Pathの毎フレーム再生成**
  - `RouteFrameRenderer.Style`データクラスに`routePaint`/`markerFillPaint`/`markerStrokePaint`/`dateTimeTextPaint`/`attributionPaint`（いずれも`Paint`）と`routePath`（`Path`）を`by lazy`で追加し、`drawRoute`/`drawMarker`/`drawDateTimeText`/`drawAttribution`はこれらを使い回すよう変更した（`Path`は`reset()`してから再構築）。`draw`関数のデフォルト引数`style: Style = Style()`は、呼び出しのたび新規インスタンスを生成すると`by lazy`のキャッシュ効果が無効化される（`RouteOverlayView.onDraw`は毎フレーム`style`を明示指定せず呼んでいるため）ため、`style: Style = DEFAULT_STYLE`（object内のシングルトンインスタンス）へ変更した。

### 結果
- `./gradlew testDebugUnitTest`が成功（既存の全テストに加え、下記の新規テストを含め全件パス）。
- `./gradlew assembleDebug`が成功。
- 座標変換の修正（1番）について、`ScreenProjection`自体は変更していない（バグはワールド座標→画面座標のアフィン変換ロジックではなく、その入力である`metersPerPixel`をどの緯度で取得するかという`RouteOverlayView`側の呼び出し箇所にあったため）。代わりに、修正の理論的根拠を検証する単体テストを`MercatorTest.kt`に1件追加した（`distanceMeters_matchesHaversineOnlyAtEquator_confirmingLatitudeZeroIsTheCorrectMetersPerPixelReference`）: 緯度0では投影距離(`distanceMeters`)と実距離(`haversineDistanceMeters`)の比が1に近い（残差はEPSG:3857の赤道半径6378137mとHaversineの地球平均半径6371000mという定数の違いによるものであり、0.2%の許容誤差で確認した）一方、東京（緯度約35.68度）では比が約1.23倍に乖離することを確認し、`getMetersPerPixelAtLatitude(0.0)`を使う根拠を裏付けた。`MapLibreMap.projection`自体への依存があるため`RouteOverlayView.recomputeAndInvalidate`本体は引き続きJVM単体テストの対象外（D-003と同種の制約）。
- `RouteFrameRenderer.kt`は引き続き`android.graphics.Canvas`/`Paint`/`Path`に依存するためJVM単体テスト対象外（Paint/Pathキャッシュの効果自体はコードレビューによる確認に留まる）。
- 実機/エミュレータでの目視確認（座標ズレが解消されたか、ANRが解消されたか）は本セッションでは未実施（環境制約、T-002以降一貫した既知の制約）。

### 次回開始位置
- T-007（アニメーション再生と速度制御）に着手する。`RouteFrameRenderer.draw`の`progress`引数・`RouteOverlayView`は既に導線があるため、データ時刻↔再生時刻の写像と、`progress`を実際に変化させる再生ループの実装が中心になる想定。T-007以降で`RouteFrameRenderer.draw`を高頻度に呼ぶ場合、呼び出し元は同一の`Style`インスタンス（`DEFAULT_STYLE`、またはカスタムStyleを保持する場合はそのインスタンス自身）を使い回すこと（毎回`Style()`を新規生成するとPaint/Pathキャッシュが無効化される）。

### コミット
- 本タスクの変更（コード・テスト・docs/tasks.md・本エントリ含む）はコミット済み（コミットハッシュ`4aa9993`、コミットメッセージ先頭行: `T-006b: 座標変換スケール・UIスレッドDP・Paint/Path再利用を修正する`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

## 2026-08-20 T-006 地図上のルート表示＋期間指定（日/週/月/年）

### 実施内容
- `app/src/main/java/com/nagamaki0311/timeliner/model/Period.kt`（純Kotlin）: `PeriodType`（DAY/WEEK/MONTH/YEAR/CUSTOM）と`Period`（`startDate`/`endDate`両端含む、`init`で`endDate < startDate`を`require`で拒否）。`Period.of(type, referenceDate)`で基準日から期間を計算（WEEKは`TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)`で月曜起点固定、MONTHは`withDayOfMonth(1)`起点、YEARは`withDayOfYear(1)`起点）。`next()`/`previous()`はDAY/WEEK/MONTH/YEARそれぞれ`plusDays`/`plusWeeks`/`plusMonths`/`plusYears`、CUSTOMは現在の期間の日数分だけ`startDate`/`endDate`双方をシフトする。`label()`が「2026年8月20日」「8月17日〜23日」（月またぎの場合は「8月31日〜9月6日」）「2026年8月」「2026年」形式の表示用文字列を返す。
  - 保守的判断（懸念点）: 週の起点（月曜/日曜）はタスク指示に明示が無かったため、`java.time`の標準（ISO週、月曜起点）に決め打ちした。日本の一般的なカレンダーUIでは日曜起点も広く使われるため、ユーザーが日曜起点を希望する場合は`Period.of`のWEEK分岐のみを変更すればよい設計にしてある。
- `app/src/main/java/com/nagamaki0311/timeliner/ui/PeriodSelector.kt`: 日/週/月/年の`TabRow`（`Period.of(type, period.startDate)`で種別切替）＋前後移動の`TextButton`（「前の期間」「次の期間」、アイコンフォント新規依存を避けテキストラベルにした）＋`period.label()`表示。
- `app/src/main/java/com/nagamaki0311/timeliner/render/ScreenProjection.kt`（純Kotlin、Android API非依存）: ワールド座標（メートル、`Mercator`）→画面座標（ピクセル）の平行移動＋等方スケール変換を切り出した。`RouteOverlayView`の座標変換ロジックをJVM単体テスト可能にするための分離（タスク指示のテスト要件）。
- `app/src/main/java/com/nagamaki0311/timeliner/render/RouteFrameRenderer.kt`: `android.graphics.Canvas`にルート線（`Path`＋`drawPath`）・現在位置マーカー（`drawCircle`）・日時テキスト・地図帰属表示（`MapConfig.ATTRIBUTION_TEXT`、新設）を描画する。`progress`（0.0〜1.0）引数を実際に処理する`trimByProgress`（先頭から切り詰め、区間途中は前後点を線形補間）を実装済みだが、T-006では常に`progress=1f`で呼ぶため実質的に全区間描画になる（T-007で活用する前提の設計）。D-003と同種の制約（`Canvas`依存でJVM単体テスト不可）としてここに明記。
- `app/src/main/java/com/nagamaki0311/timeliner/render/RouteOverlayView.kt`: `MapLibreMap`をカメラに連動させるカスタム`View`。`attachMap(map)`で`OnCameraMoveListener`を登録し、カメラ変化（パン・ズーム）のたびに`recomputeAndInvalidate()`を呼ぶ。ズームレベルを整数へ丸めた「ズームバケット」が変化した時だけ`Simplifier.simplify`を再実行し（epsilonMeters=`metersPerPixel（Projection.getMetersPerPixelAtLatitude）× 2`）、それ以外のカメラ変化（パン・同一バケット内の微小ズーム）は`ScreenProjection`によるアフィン変換の再計算のみで済ませる（タスク指示の「再計算頻度を下げる工夫」に対応）。
- `app/src/main/java/com/nagamaki0311/timeliner/ui/TimelineScreen.kt`: `PeriodSelector`＋`MapContainer`（地図）＋`RouteOverlayView`（`AndroidView`でラップ）を`Column`/`Box`で重ねて配置。`TimelineViewModel.routePoints`（選択期間の点列）を`RouteOverlayView.setRoute`へ、`selectedPeriod.label()`を`setDateTimeText`へ反映する。ルートが変わるたびに全体が収まるよう`LatLngBounds.Builder`＋`CameraUpdateFactory.newLatLngBounds`で`easeCamera`する（1点のみの期間は`newLatLngZoom`で固定ズーム表示）。
- `app/src/main/java/com/nagamaki0311/timeliner/ui/TimelineViewModel.kt`: `selectedPeriod`（`StateFlow<Period>`、初期値は当日のDAY期間）と`routePoints`（`StateFlow<PointBlobCodec.DecodedPoints?>`、期間内にデータが無ければ`null`）を追加。`selectPeriod(period)`が`repository.queryDays(start, end)`（`Dispatchers.IO`）を呼び、日付昇順（＝時刻昇順）に結合した点列を発行する。読み込み中に別の期間へ切り替わっていた場合は古い結果で上書きしない（連打対策、`_selectedPeriod.value == period`の一致確認）。ルートの結合ロジックは既存の`PointBlobCodec.DecodedPoints`（`store`パッケージに既存、新規データクラスを作らず再利用）をそのまま使う。
- `app/src/main/java/com/nagamaki0311/timeliner/ui/MapContainer.kt`: `onMapReady: (MapLibreMap) -> Unit`引数を追加し、`RouteOverlayView`のカメラ連動に地図インスタンスを渡せるようにした。あわせて`MapConfig.ATTRIBUTION_TEXT`定数を追加。**根本原因の修正**: 地図初期化（`getMapAsync`/`setStyle`）を`AndroidView`の`update`ブロックから`factory`ブロックへ移動した。`update`は呼び出し元（`TimelineScreen`）が期間切替等で再コンポジションするたびに再実行されるため、`update`のままだと期間を切り替えるたびに地図スタイルが無駄に再読み込みされるバグになる（`factory`はAndroidViewの生成時に一度だけ呼ばれるため、この問題が起きない）。
- `MainActivity.kt`: 地図タブの表示を`MapContainer`単体から`TimelineScreen`（`viewModel = timelineViewModel`）へ差し替え。インポートタブは変更なし。

### 結果
- `./gradlew testDebugUnitTest`が成功（既存76件+新規`PeriodTest`23件+`ScreenProjectionTest`4件の計103件すべてパス）。
- `./gradlew assembleDebug`が成功。
- テストの制約（D-003/D-004と同種）: `RouteFrameRenderer.kt`は`android.graphics.Canvas`に、`RouteOverlayView.kt`は`android.view.View`/`org.maplibre.android.maps.MapLibreMap`にそれぞれ依存するためJVM単体テスト不可。座標変換ロジック（ワールド座標→画面座標のアフィン変換）のみ`ScreenProjection.kt`として切り出しJVM単体テストで検証した（タスク指示通り）。`MapLibreMap`の実際のAPIシグネチャ（`CameraPosition.target`が`LatLng?`でnullable等）は、Maven Centralから取得した`android-sdk-13.5.0.aar`を`javap`で逆コンパイルして確認した上で実装した（T-002と同じ手法、WebFetch不可のため）。
- 実機/エミュレータでの目視確認（地図上へのルート描画・期間切替・fitBoundsの見た目）は本セッションでは未実施（環境制約、T-002以降一貫した既知の制約）。

### 懸念点（保守的判断で進めた不明点、Auto Mode下）
- 週の起点（月曜/日曜）: 上記「実施内容」参照。
- `TimelineViewModel.selectedPeriod`の初期値は当日の`DAY`期間とした。インポートされるデータは通常過去の日付のため、初期表示時にルートが空（地図が初期位置のまま）になるケースが多いと想定されるが、要件に既定表示に関する明示的な指定が無く、保守的な選択として最も直感的な「今日」を既定にした。将来的に「データがある最新の期間」等を既定にしたい場合は`TimelineViewModel`の初期化ロジックの変更で対応可能。
- `TimelineScreen.fitBounds`は選択期間の未簡略化・全点（`routePoints`、日をまたぐ場合は複数日分を結合した配列）を使って`LatLngBounds.Builder`へ`include`する。年単位等で点数が非常に多い場合、境界計算自体のコストは検討の余地があるが、期間切替時にのみ実行されるため（毎フレームではない）今回は対応を見送った（T-009の性能確認で問題があれば見直す）。

### 次回開始位置
- T-007（アニメーション再生と速度制御）に着手する。`RouteFrameRenderer.draw`の`progress`引数・`RouteOverlayView`は既に導線があるため、データ時刻↔再生時刻の写像と、`progress`を実際に変化させる再生ループの実装が中心になる想定。

### コミット
- 本タスクの変更（コード・テスト・docs/tasks.md・本エントリ含む）はコミット済み（コミットハッシュ`c111db0`、コミットメッセージ先頭行: `T-006: 地図上のルート表示＋期間指定（日/週/月/年）を実装する`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

## 2026-08-20 T-005b T-005レビュー指摘の修正（days上書き警告・SQLite変数上限・CancellationException）

### 実施内容
D-006の決定に従い、`app/src/main/java/com/nagamaki0311/timeliner/store/TimelineRepository.kt`・`ui/TimelineViewModel.kt`・`ui/ImportScreen.kt`を修正した。

- **Medium: daysテーブルの無警告上書き**
  - `TimelineRepository.importTrack`を`prepareImport`（クリーニング→日付分割→上書き対象日数の検出、DB書き込みなし）と`commitImport`（実際の`days`/`segments`書き込み）に分割した。`prepareImport`の戻り値`PreparedImport`は`overwriteDayCount`（書き込み対象日付のうち既存`days`行を持つ日数）を保持する。
  - `TimelineViewModel.importFrom`は`prepareImport`の結果、`overwriteDayCount > 0`なら`pendingImport`にPreparedImportを保持したまま`ImportUiState.ConfirmOverwrite(overwriteDayCount)`を発行し、`0`ならそのまま`commitImport`まで実行する。新設した`confirmOverwrite()`/`cancelImport()`をUIから呼べるようにした。
  - `ImportScreen`は`ImportUiState.ConfirmOverwrite`表示時に標準の`AlertDialog`（「N日分の既存データを置き換えます。よろしいですか？」、続行/キャンセルの2ボタン）を表示する。続行で`confirmOverwrite()`、キャンセル・ダイアログ外タップいずれも`cancelImport()`（`Idle`へ戻る、書き込みは行わない）を呼ぶ。
- **Low: SQLite変数上限（999）超過**
  - `segments`削除の`IN`句生成（`commitImport`）と、新設した既存日付検出クエリ（`existingDates`）の両方で、対象日付集合を`SQLITE_IN_CLAUSE_CHUNK_SIZE=900`件ずつ`chunked()`し、複数回`delete`/`query`を発行する形にした（`SQLITE_MAX_VARIABLE_NUMBER=999`に対し安全マージンを見た900）。
- **Low: CancellationExceptionの握りつぶし**
  - `TimelineViewModel`の`runCatching { withContext(Dispatchers.IO) { ... } }`パターンを`try { ... } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ... }`に置き換えた（`importFrom`本体・新設`commitPreparedImport`の両方）。`CancellationException`は再送出し、それ以外の例外のみ`ImportUiState.Error`へ変換する。
- **設計上の変更点（テスト容易性のため）**: `TimelineRepository`のクリーニング・日付分割ロジック（旧`groupPointsByLocalDate`/`localDateOf`）と、上書き日数算出ロジックを`internal fun buildPreparedImport(track, options, existingDatesLookup: (List<String>) -> Set<String>)`としてcompanion objectへ切り出した。既存日付の検出手段（DB問い合わせ）をラムダとして注入できるようにしたことで、DBに依存しない部分（＝D-006決定1の中核ロジック）を`TimelineRepository`のインスタンス化すら不要な形でJVM単体テストから直接検証できるようにした。

### 結果
- `./gradlew testDebugUnitTest`が成功（既存72件+新規`TimelineRepositoryTest`4件の計76件すべてパス）。
- `./gradlew assembleDebug`が成功。
- テストの制約: `TimelineRepository`本体は`TimelineDb`（`android.database.sqlite.SQLiteOpenHelper`のサブクラス）に依存し、そのコンストラクタ自体がAndroid APIを呼ぶため、JVM単体テストからは`TimelineDb`/`TimelineRepository`のインスタンスを一切生成できない（D-003・T-005レビューと同種の制約）。そのため`prepareImport`/`commitImport`の実DB書き込み経路（`writeDayRow`/`writeSegmentRow`/`existingDates`が実際にSQLiteへアクセスする部分）自体はコードレビューによる確認に留まり、自動テストは追加できていない。上書き確認ダイアログ（`ImportScreen`の`AlertDialog`表示・ボタン動作）も同様に実機・エミュレータでの目視確認ができておらず未実施（既存のJVM単体テストの枠組みではComposeのUIレンダリングを検証できない）。
- 新設した`TimelineRepositoryTest.kt`（4件）は、`TimelineRepository.buildPreparedImport`（companion objectのinternal関数、既存日付検出をラムダ注入で差し替え可能）を通じて、実際の`TrackCleaner.clean`→日付分割→上書き日数算出という本番コードパスをDBなしで検証した（上書きなし→0件、対象日の一部が既存→その日数分、全対象日が既存→全日数分、範囲外の日付が「既存」扱いに含まれていても無視されること、の4パターン）。

### 次回開始位置
- T-006（地図上のルート表示＋期間指定）に着手する。`TimelineRepository.queryDays`/`querySegments`の出力を使って地図描画・期間指定UIを実装する想定（変更なし、T-005時点の次回開始位置を踏襲）。

### コミット
- 本タスクの変更（コード・テスト・docs/tasks.md・本エントリ含む）はコミット済み（コミットハッシュ`bba01cd`、コミットメッセージ先頭行: `T-005b: days上書き警告・SQLite変数上限・CancellationExceptionを修正する`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

## 2026-08-20 T-005 永続化とインポート導線

### 実施内容
- D-002の永続化方針（素の`SQLiteOpenHelper`、日単位BLOB格納）に従い、`app/src/main/java/com/nagamaki0311/timeliner/store/`を新設した。
  - `TimelineDb.kt`: `SQLiteOpenHelper`（`DATABASE_VERSION=1`）。`onCreate`でタスク指示通りの`days`（PK: `date`）・`segments`（`INTEGER PRIMARY KEY`のautoincrement `id`）を作成し、期間クエリ（`WHERE date BETWEEN ?`）用に`segments.date`へインデックスを追加した。`onUpgrade`はバージョン1のみのため両テーブルを再作成する最小実装。
  - `PointBlobCodec.kt`: 点列⇄16バイト/点BLOB（lat_e7 Int32・lon_e7 Int32・timeMillis Int64、`ByteBuffer`でビッグエンディアンにパック）の相互変換。純Kotlinで実装。`|lat|<=90`・`|lon|<=180`のE7値（最大18億）がInt32（最大約21.4億）に収まることを確認済み。
  - `TimelineRepository.kt`: `importTrack(RawTrack)`が`TrackCleaner.clean`→日付分割→`days`/`segments`書き込みまでを1トランザクション（`beginTransaction`/`setTransactionSuccessful`/`endTransaction`）で行う。日付の決定はタスク指示通り端末タイムゾーン（`ZoneId.systemDefault()`）に常時フォールバックする（4形式パーサ側にタイムゾーンオフセット情報を持つフィールドが無いため、D-002決定時点でこのフォールバックのみが選択肢）。1つの連続点列が日付をまたぐ場合は`CleanedTrack`の時刻昇順性を利用して連続する同一日付の区間ごとに分割し、複数の`days`行へ書き込む。各日の`distance_meters`は日内の連続点間のHaversine距離（`Mercator.haversineDistanceMeters`）の合計だが、`TrackCleaner`が検出した長時間欠損の分断点（`segmentStartIndices`）をまたぐ点同士は実際に移動していないため距離に含めない。再インポート時の冪等性として、`days`は`date`のPRIMARY KEY制約により`INSERT OR REPLACE`で上書き、`segments`はインポート対象の日付集合に該当する既存行を書き込み前に削除してから挿入する（`segments`にはPKで一意化できる自然キーが無いため）。読み出し用に`queryDays`/`querySegments`（いずれも日付範囲指定、`BETWEEN`）も実装した。
    - 既知の制約（`ponytail`コメントとして`TimelineRepository.kt`内に明記）: `segments.place_name`列は常にNULLになる。現状の4形式パーサ（`TimelineJsonParser`）が滞在地点の表示名を一切パースしていない（`placeId`のみ）ため。表示名が必要になった時点でパーサ側の対応が別途必要（本タスクのスコープ外）。
  - `data/ImportSource.kt`: `Uri`から`ContentResolver.openInputStream`で`InputStream`を開き、zip判定（拡張子`.zip`、または内容先頭2バイトのzipマジックナンバー`PK`）で`TimelineJsonParser.parseZip`/`parseJson`のどちらを呼ぶか振り分ける。zip判定用に`parseZip`が要求する`() -> InputStream`（同一Uriを指す新しいストリームを返す関数）を素直に組み立てられる設計にした。
  - `ui/TimelineViewModel.kt`: `TimelineRepository`を保持し、`importFrom(context, uri)`が`viewModelScope.launch { withContext(Dispatchers.IO) { ... } }`でUIスレッドをブロックせずにパース→クリーニング→DB書き込みを実行、結果を`StateFlow<ImportUiState>`（`Idle`/`InProgress`/`Success`/`Error`）で公開する。DIライブラリを導入しない方針（D-002）に従い、`TimelineViewModel.factory(context)`という`ViewModelProvider.Factory`を返す簡易ファクトリメソッドで`TimelineRepository`/`TimelineDb`を組み立てる。
  - `ui/ImportScreen.kt`: `ActivityResultContracts.OpenDocument()`でファイル選択（MIMEタイプはプロバイダ依存で信頼できないため`*/*`、内容判定は`ImportSource`側で行う）、`ImportUiState`に応じて進捗（`CircularProgressIndicator`）・完了サマリ（点数・セグメント数・期間・日数）・エラーメッセージを表示する。
  - `MainActivity.kt`: Navigationライブラリを導入せず、Compose標準の`TabRow`+`remember { mutableIntStateOf }`による状態切り替えで「地図」「インポート」の2画面を切り替える構成にした。`TimelineViewModel`は`by viewModels { TimelineViewModel.factory(this) }`でActivityスコープに保持する。
- 依存追加（`gradle/libs.versions.toml`・`app/build.gradle.kts`）: `androidx.activity:activity-ktx`（`by viewModels()`委譲用。`activity-compose`は`androidx.activity:activity`のみを推移的依存に持ち`activity-ktx`は含まないため明示追加が必要と判明）、`androidx.lifecycle:lifecycle-viewmodel-ktx`（`ViewModel`基底クラス・`viewModelScope`拡張用）。いずれも既存の`activityCompose`/`lifecycleRuntimeKtx`と同じバージョン系列（1.12.4 / 2.10.0）でGradleキャッシュに存在することを事前に確認してから追加した（新規ダウンロードなしでビルド成功）。
- テスト: `app/src/test/java/com/nagamaki0311/timeliner/store/PointBlobCodecTest.kt`を新設（6件: ラウンドトリップ、1点16バイトの検証、空配列、境界値[±90/±180度、`Long.MIN_VALUE`/`Long.MAX_VALUE`]、不正サイズBLOBでの例外、配列長不一致での例外）。`TimelineDb`/`TimelineRepository`/`ImportSource`は`android.database.sqlite.SQLiteDatabase`/`android.content.ContentResolver`等のAndroid API実体に依存するため、D-003と同種の制約（JVM単体テストでは`android.*`呼び出しがモック化され実際のDBが動かない）によりJVM単体テストの対象外とした（コードレビューによる確認に留まる。実機/エミュレータでのandroidTest追加が将来望ましい点もD-003と同様）。

### 結果
- `./gradlew testDebugUnitTest`が成功（既存66件+新規`PointBlobCodecTest`6件の計72件すべてパス）。
- `./gradlew assembleDebug`が成功（新規依存追加後も問題なし）。
- 性能計測（一時的なベンチマークテストを追加して実行し、記録後に削除した。T-004と同じ合成データ生成方針: 緯度経度をランダムウォークさせ、20,000点ごとに7時間の欠損を混入した10万点、今回はRecords.json形式のJSONテキスト[約7MB]として実際に構築し`TimelineJsonParser.parseJson`へ通した）:
  - `parseJson`（JSON文字列→`RawTrack`）: 約1610ms、100,000点。
  - `TrackCleaner.clean`: 約141ms、出力72,402点・6セグメント。
  - `Simplifier.simplify`（epsilon=10m, maxPointCount=5000、全セグメントに適用): 約116ms、出力12,862点。
  - `PointBlobCodec.encode`（クリーニング済み・未簡略化の72,402点をBLOB化、実際に`days`へ格納する対象）: 約24ms、1,158,432バイト。
  - 合計（パース+クリーニング+簡略化+エンコード）: 約1892ms。
  - **注記（計測できなかった部分）**: `android.database.sqlite.SQLiteDatabase`はJVM単体テスト環境では動作しない（D-003と同種の制約、`isReturnDefaultValues=true`は未モック呼び出しを例外にせず既定値で通すだけで実際のDBは動かない）ため、実際の`INSERT`トランザクション（`TimelineRepository.importTrack`の`days`/`segments`書き込み）の所要時間は本セッションでは測定できていない。SQLiteは一般にディスクI/Oが主要コストであり、`beginTransaction`/`setTransactionSuccessful`/`endTransaction`で1トランザクションにまとめている設計により少数回のfsyncで済む想定だが、実測値ではない。実機/エミュレータが利用可能になった時点でandroidTestとして計測することが望ましい。
  - 上記より、DB書き込みを除いた「パース→クリーニング→簡略化→BLOBエンコード」の合計約1.9秒は、10万点規模のインポートでも実用的な速度であることを確認した。

### 懸念点（保守的判断で進めた不明点、Auto Mode下）
- `segments.place_name`は現状常にNULL（上記「実施内容」参照）。将来パーサに表示名フィールドを追加する場合は本テーブルへの書き込み（`TimelineRepository.writeSegmentRow`）も合わせて更新が必要。
- 日付決定は常に端末タイムゾーンにフォールバックする（4形式のいずれもタイムゾーンオフセット情報を共通中間モデルに保持していないため、実質的に「タイムゾーンオフセットがあれば使う」という条件分岐は発生しない）。将来`RawTrack`/`TimelineSegment`にタイムゾーン情報を追加する場合は本タスクの日付分割ロジック（`TimelineRepository.groupPointsByLocalDate`・`localDateOf`）の見直しが必要。
- `segments`テーブルの再インポート時の冪等性は「インポート対象の日付集合に該当する既存行を削除してから挿入」という設計。同一日にまたがる複数回の部分インポート（例: 同じ日を含む別々のエクスポートファイルを続けて取り込む）では、後からインポートした側がその日の`segments`を完全に上書きする（マージはしない）。要件に明示的な仕様が無いため保守的な選択として実装したが、将来「複数ソースを同一日でマージしたい」要件が出た場合は見直しが必要。
- SQLite書き込み自体の実測性能は上記の通り未計測（環境制約）。

### コミット
- 本タスクの変更（コード・テスト・docs/tasks.md・本エントリ含む）はコミット済み（コミットハッシュ`c1b395e`、コミットメッセージ先頭行: `T-005: 永続化とインポート導線を実装する`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

### 次回開始位置
- T-006（地図上のルート表示＋期間指定）に着手する。`TimelineRepository.queryDays`/`querySegments`の出力（日単位の点列・セグメント）を使って地図描画・期間指定UIを実装する想定。`CleanedTrack.segmentRange`と同様、`days`テーブルの点列は「クリーニング済み・未簡略化」のため、描画前に`Simplifier.simplify`を呼ぶ配線が必要（T-004完了時の懸念点と同じく、呼び出し側の責務として残っている）。

## 2026-08-19 T-004b T-004レビュー指摘の修正（TrackCleanerの実距離判定をHaversineへ）

### 実施内容
- D-005の決定に従い、以下を修正した（対象: `app/src/main/java/com/nagamaki0311/timeliner/process/`）。
  - `Mercator.kt`: 2点の緯度経度（度単位）から大圏距離をHaversine公式で計算する`haversineDistanceMeters`を追加（地球平均半径6371000mを使用）。既存の`distanceMeters`（メルカトル投影平面上のユークリッド距離）には「投影空間内の距離であり実世界の距離ではない、[Simplifier]の幾何学的な簡略化にのみ使うこと」を明示するコメントを追加した。ファイル冒頭のコメントにあった、実際にはdocs/tasks.mdに存在しない文言を引用符付きで「docs/tasks.md T-004: 「Webメルカトル投影後のメートル空間で実行する」」と記載していた誤記（投影方式の選択はDeveloper自身の設計判断）を、出典表記を外した記述へ修正した（レビュー指摘のLowで名指しされたファイルはSimplifier.ktだったが、grepで実際の該当箇所を特定した結果この文言はMercator.kt側にのみ存在したため、根本原因の実位置であるMercator.ktを修正した）。
  - `TrackCleaner.kt`: `removeSpeedSpikes`が内部で呼ぶ`speedMetersPerSecond`、および`suppressStationaryJitter`が使う距離計算を、いずれも`Mercator.distanceMeters`から`Mercator.haversineDistanceMeters`へ置き換えた。
  - `Simplifier.kt`: Douglas-Peuckerの垂線距離判定は幾何学的な簡略化が目的（地図上の見た目のズレを測る指標であり、MapLibreの描画自体もWebメルカトルベース）のため変更不要と判断し、変更していない。
- テスト:
  - `MercatorTest.kt`に`haversineDistanceMeters`用のテストを5件追加（同一点0、対称性、赤道上1度の既知値[地球平均半径ベースで約111.19km]、東京駅↔新宿駅の既知の実距離[約6083m、許容誤差50m]、赤道以外では`distanceMeters`[投影距離]より`haversineDistanceMeters`[実距離]の方が小さいことの確認）。
  - `TrackCleanerTest.kt`の既存テスト（速度スパイク除去2件・停留ジッタ抑制3件）は、いずれも距離判定の閾値に対して十分な余裕（最大速度閾値300km/hに対し実測30〜40km/h程度、距離閾値15mに対し実測3〜5m or 1.1km程度）を持たせた設計だったため、Mercator投影距離からHaversineへの置き換え後も期待値の変更なしにそのまま成立することを確認した。あわせて「高緯度でも判定基準が一定であること」を検証する新規テスト`suppressStationaryJitter_thresholdIsConsistentAtHighLatitude`を1件追加（赤道付近と北緯60度で実距離がほぼ同じ[約11.1m]になるよう経度差を`1/cos(60°)`倍に調整した2点を用意し、どちらも同じ停留ジッタ閾値[15m]で抑制されることを確認。旧Mercator投影距離のままだと北緯60度側の経度差が約2倍に過大評価され抑制されなくなるため、この修正の効果を検証するテストになっている）。

### 結果
- `./gradlew testDebugUnitTest`が成功（`MercatorTest`12件・`TrackCleanerTest`13件を含め全件パス）。
- `./gradlew assembleDebug`が成功。

### 次回開始位置
- T-005（永続化とインポート導線）に着手する。

### コミット
- 本タスクの変更（コード・テスト・docs/tasks.md・本エントリ含む）はコミット済み（コミットハッシュ`41c1a9c`、コミットメッセージ先頭行: `T-004b: TrackCleanerの実距離判定をHaversineへ置き換える`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

## 2026-08-19 T-004 GPSノイズ除去・ルート簡略化（+T-003b再検証指摘2件の修正）

### 実施内容
- **T-003b再検証指摘の修正**（`app/src/main/java/com/nagamaki0311/timeliner/data/parser/TimelineJsonParser.kt`）
  - Medium: `parseArrayElementSafely`のcatchブロックにコメントのみでログ出力が無かった問題を修正。`android.util.Log.w("TimelineJsonParser", ...)`でスキップ理由（例外メッセージ）を出力するようにした（D-004決定3準拠）。
  - Low: `parseRootObject`の`"semanticSegments"`/`"timelineObjects"`/`"locations"`各分岐が呼び出す`parseDeviceTimelineArray`/`parseTimelineObjectsArray`/`parseRecordsArray`の3関数それぞれの先頭に`reader.peek() == JsonToken.NULL`判定を追加し、値が明示的に`null`の場合は`nextNull()`でスキップして空配列として扱うようにした（`{"locations": null}`等で`IllegalStateException`によりファイル全体のパースが失敗していた問題を解消）。3関数を直接null耐性化したことで、`parseRoot`のトップレベル配列ケース（形式Bの`parseDeviceTimelineArray`呼び出し）も同じ実装を共有する。
  - `android.util.Log`はJVM単体テストでは既定で「not mocked」例外を投げる（D-003と同種の制約）ため、`app/build.gradle.kts`の`android { testOptions { unitTests { isReturnDefaultValues = true } } }`を追加し、未モック化のandroid.*呼び出しを例外にせず既定値で通すようにした（Robolectricは導入しない）。
  - `TimelineJsonParserTest.kt`に2件追加: 型不一致で例外が発生する要素があっても他の要素は正常にパースされること（ログ出力経路を実際に通す）、`"locations": null`が0点0セグメントで正常にパースされること。
- **T-004本体**: `app/src/main/java/com/nagamaki0311/timeliner/process/`にパイプラインを新設した（すべて純Kotlin、Android API非依存）。
  - `Mercator.kt`: 緯度経度↔Webメルカトル（EPSG:3857相当）ワールド座標変換、2点間のメートル距離計算。
  - `TrackCleaner.kt`: `RawTrack`を入力に、正規化（時刻昇順ソート・同一時刻重複除去・範囲外座標`|lat|>90`/`|lon|>180`と`(0,0)`の破棄。`RawTrack`に`accuracy`が無いため精度フィルタは実装しない）→速度スパイク除去（連続点間速度が閾値[既定300km/h]超、かつ直前採用点→次点でスキップした場合の速度が閾値内に収まる「1点だけ飛んで戻る」パターンのみ除去。航空機区間等の持続的高速移動は前後・スキップいずれの速度も閾値超のため誤って除去しない）→停留ジッタ抑制（直前採用点から距離15m未満かつ経過時間60秒未満の点を破棄。距離・時間いずれかが閾値以上なら残す）→長時間欠損（既定6時間超）での分断判定（`segmentStartIndices`として返す）の4段パイプライン。`CleanedTrack`（`DoubleArray`/`LongArray`＋`segmentStartIndices`、`segmentRange(i)`ヘルパー付き）を出力する。各段の入出力は内部の`PointSeries`（DoubleArray/LongArrayベース、`internal`公開でテストから直接呼べる）。大量点でのGC負荷を避けるため、`TimelineJsonParser`内の`RawTrackBuilder`と同じ倍々拡張バッファのパターンを再利用した。
  - `Simplifier.kt`: Douglas-Peuckerによる簡略化。**再帰を一切使わず**、処理範囲`[start,end]`をヒープ上のLongArray（start/endを1個のLongへビットパック）で管理する明示スタック（`RangeStack`）で実装したため、数十万点規模でも`StackOverflowError`が構造的に発生しない（呼び出しスタックを一切消費しない設計）。時間ガード: 隣接点間の経過時間が閾値（既定5分）を超える箇所の両端点は、DP走査時に実効距離を`Double.MAX_VALUE`とすることで必ず分割点として選ばれ（＝必ず残る）、幾何的な偏差に関わらず間引かれない。点数上限オプション（`maxPointCount`）: DP後もこの点数を超える場合epsilonを倍々にして再実行する。当初「1回のepsilon倍化で点数が変化しなければ打ち切る」という早期終了ヒューリスティックを実装したが、epsilonが初期値近辺（実データの偏差スケールよりはるかに小さい値）にある間は点数が全く変化しない区間が続くことがあり、これを「収束した」と誤判定して早期に打ち切ってしまうバグがテストで発覚したため、単純な反復回数上限（既定60回）のみで打ち切る方式に修正した（epsilonは指数的に成長するため60回で天文学的な値に達し、時間ガードで保護された点だけが残る理論上の下限に確実に到達する）。

### 結果
- `./gradlew testDebugUnitTest`が成功（新規: `MercatorTest`7件、`TrackCleanerTest`12件[正規化3・速度スパイク除去2・停留ジッタ抑制3・分断判定3・パイプライン統合1]、`SimplifierTest`6件[矩形・直線+外れ値・時間ガードあり/なし・点数上限・大規模データ]、`TimelineJsonParserTest`に2件追加で計19件。既存の`CoordinateParsingTest`9件・`TimestampParsingTest`7件も引き続きパス）。
- `./gradlew assembleDebug`が成功。
- **性能計測**（一時的なベンチマークテストを追加して実行し、記録後に削除した）: 合成データ10万点（緯度経度をランダムウォークさせ、20,000点ごとに7時間の欠損を意図的に混入）に対し、`TrackCleaner.clean`が74ms（出力27,064点、5セグメント）、続けて全セグメントに`Simplifier.simplify`（epsilon=10m、maxPointCount=5000）を適用して39ms（出力17,013点）。合計約113msで完了しており、「大量の位置情報を扱っても極端に動作が重くならない」という要件を満たす実用的な速度であることを確認した。
- `simplify_straightLineWithOneOutlier_keepsEndpointsAndOutlier`テストの作成過程で、DPの再帰分割は「外れ値1点を挟んだ直線」であっても、外れ値を分割点として選んだ後の2つの部分区間それぞれのchord（直線と外れ値を結ぶ斜めの線）に対して残りの直線上の点が非ゼロの偏差を持つため、epsilonが小さいと想定より多くの点が残ることを実測で確認した（数学的には正しいDP挙動）。テストのepsilonをこの副次的な偏差[約89m]より大きく設定して意図通りの結果[両端＋外れ値のみ]を検証した。

### 次回開始位置
- T-005（永続化とインポート導線）に着手する。`TrackCleaner.clean`→`Simplifier.simplify`（セグメントごと）の出力をSQLite（日単位BLOB）へ保存する設計を想定。
- 懸念点（将来的な見直し候補）: `RawTrack`に`accuracy`情報が無いため、正規化段階での精度フィルタ（accuracy>100m破棄）は未実装のまま。将来`RawTrack`にaccuracyを追加する場合はT-003側のパーサ・本タスクの`TrackCleaner.normalize`の両方に手を入れる必要がある。
- 懸念点: `Simplifier.simplify`は1セグメント分の点列を渡す前提の関数として実装した（`TrackCleaner`の`segmentStartIndices`で分割済みの各区間を呼び出し側がスライスして渡す）。T-006（地図表示）でこの呼び出し側の配線（`CleanedTrack.segmentRange`を使ったスライス処理）を実装すること。

### コミット
- 本タスクの変更（コード・テスト・docs/tasks.md・本エントリ含む）はコミット済み（コミットハッシュ`b26c747`、コミットメッセージ先頭行: `T-004: GPSノイズ除去・ルート簡略化パイプラインを実装する`）。本行の追記自体はStop Hook（subagent-doc-check）が未コミット差分の有無で記録漏れを検知する仕様のため意図的に未コミットのまま残す。内容に変更はなく、Manager確認後にコミットして問題ない。

## 2026-08-19 T-003b T-003レビュー指摘の修正（null耐性・複数データ源の統合・Gson化）

### 実施内容
- D-004の決定に従い`TimelineJsonParser.kt`を修正した（対象: `app/src/main/java/com/nagamaki0311/timeliner/data/parser/TimelineJsonParser.kt`）。
  - Gson化: importを`android.util.JsonReader`/`android.util.JsonToken`から`com.google.gson.stream.JsonReader`/`com.google.gson.stream.JsonToken`へ切替。`gradle/libs.versions.toml`・`app/build.gradle.kts`に`com.google.code.gson:gson:2.14.0`（Maven Central `maven-metadata.xml`で確認した最新安定版。groupIdは`com.google.code.gson`であり`com.google.gson`ではないことに注意）を`implementation`として追加した。
  - null耐性: `readNullableString`/`readNullableLong`/`readNullableDouble`（`reader.peek() == JsonToken.NULL`を判定し`nextNull()`でスキップ）を追加し、全フィールド読み取り箇所を置き換えた。既存の`readFlexibleDouble`（string/number両対応のdouble読み取り）は`readNullableDouble`に統合した。
  - 要素単位のスキップ耐性: `parseArrayElementSafely`ヘルパーを追加。配列の各要素を`com.google.gson.JsonParser.parseReader(reader)`で一度`JsonElement`ツリーとして丸ごと消費し、その`toString()`を新しい`JsonReader(StringReader(...))`で読み直して解釈する。想定外の型不一致等の例外は解釈側だけで発生するため、元の`reader`（配列全体を走査する側）の読み取り位置は常に正しい状態を保ち、その要素だけをスキップして後続要素の処理を継続できる。これを`parseDeviceTimelineArray`（形式A/B）・`parseTimelineObjectsArray`（形式C、`parseTimelineObject`に分離）・`parseRecordsArray`（形式D、`parseRecord`に分離）の3箇所に適用した（D-004決定3で名指しされた4関数すべてを網羅）。単純に呼び出しを`try/catch`で囲むだけでは、例外発生時に`beginObject()`済みで`endObject()`未実行の状態が残り後続要素の走査位置がずれるため、この設計を採用した。
  - zip内優先順位付け: `isTargetZipEntry`を`isSemanticLocationHistoryEntry`/`isRecordsJsonEntry`に分割し、`shouldParseZipEntry`で「`Semantic Location History/`が1件でも存在する場合は`Records.json`を除外」を判定する。`ZipInputStream`は巻き戻せず、除外可否の判定には全エントリを事前に把握する必要があるため、`parseZip`のシグネチャを`InputStream`から`() -> InputStream`（同一内容を指す新しいストリームを返す関数）に変更し、1回目のパスでエントリ種別のみをスキャン（内容は読み捨て、メモリに保持しない）、2回目のパスで実際にパースする2パス方式にした。現時点で`parseZip`の呼び出し元はテストのみ（インポート導線はT-005で実装予定）のため、シグネチャ変更の影響範囲はない。将来のSAF/Uri経由の実装（`ContentResolver.openInputStream(uri)`を複数回呼ぶ）とも自然に整合する設計とした。
  - 時刻ソート: `RawTrackBuilder.build()`で全点を時刻昇順に安定ソートしてから`RawTrack`を返すよう変更（インデックス配列を`sortedBy`（Kotlin内部はマージソート相当で安定）でソートし、3つのプリミティブ配列を並べ替える）。単一エントリの場合も含め常に適用する。
  - Nit対応: `parseVisit`の`"placeLocation"`キー照合を`ignoreCase = true`から`==`（大文字小文字を区別）に戻した（`placeId`/`placeID`のみ文書化された差異のため）。
- `TimelineJsonParserTest.kt`にD-004決定5に沿った統合テストを追加した。Gson化によりJVM単体テストから`parseJson`/`parseZip`を実際に実行できるようになったため、形式A〜Dそれぞれの合成JSONを実際にパースして点数・座標・時刻・セグメント種別を検証するテストを新設（4件）。加えてnull耐性（`placeId`/`activityType`が`null`でも要素全体は正常にパースされること、Records.jsonの必須フィールド`latitudeE7`が`null`の要素だけがスキップされ他は正常にパースされること、3件）、zip内優先順位付け（`Records.json`と`Semantic Location History`が同一zipにある場合は前者が除外されること、`Records.json`単体では読み込まれること、2件）、時刻ソート（zip格納順が時系列と逆でも最終的な点列が時刻昇順になること、1件）を追加した。`isTargetZipEntry`の既存7件はそのまま維持した（テスト総数17件）。

### 結果
- `./gradlew testDebugUnitTest`が成功した（`TimelineJsonParserTest`17件すべてパス、`CoordinateParsingTest`9件・`TimestampParsingTest`7件も引き続きパス）。
- `./gradlew assembleDebug`が成功した（gson追加後もAPKビルドに問題なし）。
- Reviewerが指摘した5件（High×2・Medium×1・Gson化・Nit×1）すべてに対応した。Medium（Records.jsonの位置づけのドキュメント不整合）はD-004で解決済みのためコード修正のみで完了。

### 次回開始位置
- T-003・T-003bを完了とし、T-004（GPSノイズ除去・ルート簡略化）へ進む。`RawTrack`（プリミティブ配列、時刻昇順ソート済み）を入力・出力とするパイプライン関数群を想定。
- 懸念点（将来的な見直し候補）: `parseZip`の2パス方式は、1パス目でzip全体のエントリ内容を読み捨てるため、非常に大きなzip（数GB級）ではCPU時間が単純に倍近くなる。T-005でSAF経由の実インポート導線を実装する際、実際のファイルサイズ感（Takeout標準エクスポートの典型サイズ）を踏まえて許容範囲か確認すること。

### コミット
- 本タスクの変更（コード・テスト・docs/tasks.md・本エントリ含む）はコミット済み（コミットハッシュ`090f113`、コミットメッセージ先頭行: `T-003b: レビュー指摘（null耐性・複数データ源統合・Gson化）を修正する`）。

## 2026-08-19 T-003 タイムラインJSONパース（4形式対応）

### 実施内容
- 共通中間モデルを`app/src/main/java/com/nagamaki0311/timeliner/model/`に新設: `TimelinePoint.kt`（緯度経度＋時刻）、`TimelineSegment.kt`（`TimelineSegmentType`enum: VISIT/ACTIVITY/PATH_ONLY、開始終了時刻、placeId、代表座標、移動手段、距離）、`RawTrack.kt`（点列をDoubleArray/LongArrayのプリミティブ配列で保持、`init`でサイズ整合の`require`、`point(index)`で単一点アクセス）。
- `app/src/main/java/com/nagamaki0311/timeliner/data/parser/`に純Kotlin（Android API非依存）のパースロジックを新設: `CoordinateParsing.kt`（度記号文字列/`geo:`URI/E7整数、E7は符号なしオーバーフロー補正込み）、`TimestampParsing.kt`（ISO-8601オフセット付き/epoch ms文字列、`java.time`はminSdk 29で追加依存不要）。
- `TimelineFormat.kt`（4形式のenum）、`TimelineJsonParser.kt`（`android.util.JsonReader`によるストリーミング走査本体）を新設。ルートを1トークン先読みして`BEGIN_ARRAY`→iOS(B)、`BEGIN_OBJECT`→最初に現れる既知キー（`semanticSegments`/`timelineObjects`/`locations`）でA/C/Dを判定、未知キーは`skipValue()`。内部の`RawTrackBuilder`（倍々拡張するDoubleArray/LongArray、非公開）で点列を蓄積しボクシングを避けた。
  - 形式A/B: `visit.topCandidate`の`placeId`/`placeID`キー名の大文字小文字差を`equals(ignoreCase=true)`で吸収。`placeLocation`/`activity.start`/`activity.end`はAndroidのネスト(`{"latLng":"..."}`)とiOSの文字列直置きを`parseLatLngField`で統一的に処理。`activity.distanceMeters`はAndroid(number)/iOS(string)両対応の`readFlexibleDouble`で吸収。visitセグメントは代表地点をtrackの点としても追加し、activityセグメントは`timelinePath`が無い場合のみstart/endの2点をフォールバック追加（`timelinePath`がある場合は重複させない）。
  - 形式C: `duration.startTimestamp`(ISO)/`startTimestampMs`(epoch ms文字列、旧形式)の両キー名に対応。ルート点は`simplifiedRawPath.points`（時刻あり）を優先し、無ければ`waypointPath.waypoints`（時刻なし）を`duration`の範囲で等時間割り付け(`addEvenlySpacedPoints`)するフォールバックを実装。
  - 形式D: `locations[]`の`latitudeE7`/`longitudeE7`/`timestamp`のみをストリーミングで読み点列化（`accuracy`等はD-002によりv1スコープ外）。
  - zip対応: `parseZip`で`ZipInputStream`を走査し、`isTargetZipEntry`（`Semantic Location History/`配下または`Records.json`、大文字小文字・`\`パス区切り吸収、純Kotlin）で対象エントリを判定。エントリ単位で`JsonReader`を生成するが、closeするとzip全体のストリームが閉じてしまうため意図的にcloseしない設計にした。
- JVM単体テストを`app/src/test/java/com/nagamaki0311/timeliner/data/parser/`に追加: `CoordinateParsingTest.kt`（9件）、`TimestampParsingTest.kt`（7件）、`TimelineJsonParserTest.kt`（7件、後述の制約により`isTargetZipEntry`のみ検証）。`app/build.gradle.kts`・`gradle/libs.versions.toml`に`testImplementation(libs.junit)`（JUnit4 4.13.2）を追加した（本タスクで初めてJVM単体テストを追加するため、テスト用依存自体がこれまで存在しなかった）。

### 重要な発見（実装前の実測確認）と対応
- 実装着手前に、タスク指示にあった「`android.util.JsonReader`はJVM単体テストから直接使える」という前提を、最小の再現テストで実測確認したところ誤りだった。`./gradlew testDebugUnitTest`で`java.lang.RuntimeException: Method beginObject in android.util.JsonReader not mocked.`が発生した（AGPがローカル単体テスト用に生成する「モック化android.jar」は、対象クラスの実装が純粋なJavaコードかどうかに関わらずandroid.*のAPI呼び出しを一律例外送出に置き換えるため、Robolectric無しには回避不可）。
- タスク指示には同時に「`JsonReader`を使う走査部分はAndroid API依存のためJVM単体テストの対象外でよい（Robolectric等の追加依存は導入しない）」という、こちらは技術的に正しい記述も併記されていたため、この既存の許容条件に従うことにした。詳細な経緯・判断根拠はdocs/decisions.md D-003に記録した。
- 結果として、`TimelineJsonParser.kt`本体（4形式のルート判別・セグメント分岐ロジック）はJVM単体テストの対象外とし、`isTargetZipEntry`（純Kotlin部分）のみ実テストで検証、`TimelineJsonParserTest.kt`冒頭のコメントにこの制約と理由を明記した。**4形式（A〜D）それぞれの合成フィクスチャを実際にパーサへ通した自動テストは今回追加できていない**（コードレビューによる確認と、後述の手動トレースに留まる）。これはD-002で既知のリスクとされていた「実データ未検証」とは別の、テスト自動化に関する新たな既知の制約である。

### 結果
- `./gradlew clean testDebugUnitTest assembleDebug`が成功した。追加した単体テスト23件（`CoordinateParsingTest`9件、`TimestampParsingTest`7件、`TimelineJsonParserTest`7件）はすべてパスし、コンパイル警告もない。
- 形式A〜Dのパース分岐ロジック自体は、4形式それぞれの合成JSON構造（度記号文字列/`geo:`URI/E7整数＋オーバーフロー補正/ISO及びepoch ms時刻、`placeId`/`placeID`大文字小文字差、`latLng`ネスト有無、`simplifiedRawPath`優先とフォールバック等）を実装しながら手動でコードトレースして確認したが、自動テストでの実行確認はできていない（上記「重要な発見」参照）。
- 実際のGoogleエクスポートの実サンプルは入手できていない（D-002の既定方針通り、公開仕様の記述に基づく実装）。
- 本タスクの変更（コード・docs/decisions.md・docs/tasks.md含む）はコミット済み（コミットメッセージ先頭行: `T-003: タイムラインJSON4形式パースを実装`）。

### 次回開始位置
- T-004（GPSノイズ除去・ルート簡略化）に着手する。`RawTrack`（プリミティブ配列）を入力・出力とするパイプライン関数群を想定。
- 懸念点（将来的な見直し候補）: 実機/エミュレータが利用可能になった時点で、`TimelineJsonParser`の4形式パースをandroidTest（instrumented test）として追加検証することが望ましい（docs/decisions.md D-003参照）。またRobolectric導入の是非は、テスト自動化の重要性が増した時点でユーザーに確認の上再検討する。

## 2026-08-19 T-002 環境確認＋プロジェクト雛形＋地図表示画面

### 実施内容
- Step 0（環境確認）: `ANDROID_HOME`/`ANDROID_SDK_ROOT`/`sdkmanager`はいずれも未設定・未導入だったが、プロキシ経由で`https://dl.google.com/android/repository/`へのアクセスが可能だったため、cmdline-tools（11076708）を`/opt/android-sdk`に導入し、ライセンス承認・`platform-tools`・`platforms;android-36`・`build-tools;36.1.0`をインストールしてSDKを用意できた。Gradle 8.14.3（`/opt/gradle`）を使い`gradle wrapper --gradle-version 8.14.3`でwrapper一式（`gradlew`/`gradlew.bat`/`gradle/wrapper/`）を生成した。
- Step 1: ルート（`settings.gradle.kts`/`build.gradle.kts`/`gradle.properties`/`gradle/libs.versions.toml`/`.gitignore`）と`app`モジュール（`build.gradle.kts`/`proguard-rules.pro`/`AndroidManifest.xml`/`MainActivity.kt`/`ui/MapContainer.kt`/リソース一式/アダプティブアイコン）を新規作成した。`MapContainer.kt`はAndroidViewでMapLibreの`MapView`をラップし、`MapConfig.STYLE_URL`（1箇所）にOpenFreeMapのスタイルURL(`https://tiles.openfreemap.org/styles/liberty`)を定義、回転・チルトジェスチャーを無効化した。MapViewのライフサイクルはComposeの`LocalLifecycleOwner`と`DisposableEffect`で`onCreate`〜`onDestroy`を連動させる標準パターンを実装した。

### 結果
- `./gradlew assembleDebug`が成功することを実機のAndroid SDK環境で確認した（`app/build/outputs/apk/debug/app-debug.apk`生成済み、`clean assembleDebug`でも成功、警告なし）。
- 依存バージョンの選定過程で判明した制約: `androidx.compose:compose-bom`の2026年時点の最新（2026.08.00）や`androidx.core:core-ktx`1.19.0はcompileSdk 37以上・AGP 9.1.0以上を要求する（実測でビルドエラーとして確認）。AGP 9.x系とcompileSdk 37（Android 17系、SDKでは`android-37.0`/`android-37.1`として提供）の組み合わせはまだ実績が薄く、タスク指定のGradle 8.14.3・「環境で利用可能な最新安定版」の趣旨を踏まえ、今回は compileSdk/targetSdk=36（Android 16、AGPが正式サポートする上限）、AGP 8.13.2（8.x系最新）、Kotlin 2.2.21、compose-bom 2026.01.01、core-ktx 1.18.0、lifecycle 2.10.0、activity-compose 1.12.4、MapLibre 13.5.0（決定事項の想定13.4.x系より新しい実際の最新パッチ）を採用した。この組み合わせで実ビルドが成功することを確認済み。
- MapLibreの実際のAPI（`org.maplibre.android.maps.MapView`/`MapLibreMap`/`Style.Builder`/`UiSettings.setRotateGesturesEnabled`/`setTiltGesturesEnabled`等）は、Maven Centralから取得した`android-sdk-13.5.0.aar`を`javap`で逆コンパイルして実クラスシグネチャを確認した上で実装した（WebFetch不可のため）。
- 実機/エミュレータでの目視確認（地図タイルが実際に描画されるか）は今回のサンドボックス環境ではエミュレータ・実機が使えないため未実施。APKのビルド成功とAPI呼び出しの型整合性（コンパイル成功）までを確認範囲とした。

### 次回開始位置
- T-003（タイムラインJSONパース、4形式対応）に着手する。`app/src/main/java/com/nagamaki0311/timeliner/`配下に新規パッケージ（例: `data`）を追加する想定。
- 懸念点（将来的な見直し候補）: compileSdk 37 / AGP 9.x系への追従は、AGP 9.xの実績が十分に積まれた時点で改めて検討する（現時点ではT-002のスコープ外、バックログ化はしない=先送りの明示的判断としてここに記録するのみ）。
