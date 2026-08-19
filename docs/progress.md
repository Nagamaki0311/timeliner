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
