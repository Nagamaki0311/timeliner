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
