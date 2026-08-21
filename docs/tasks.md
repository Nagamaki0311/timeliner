# タスク管理

現在のタスク、優先順位、状態を管理する。

## 状態の定義

- `未着手`: まだ着手していない
- `計画中`: plannerによる計画作成中/完了
- `調査中`: researcherによる外部情報収集中（外部調査が必要なタスクのみ）
- `実装中`: developerによる実装中
- `レビュー中`: reviewerによる確認中
- `完了`: 完了条件（AGENTS.md参照）を満たした

## タスク一覧

| ID | タスク | 優先度 | 状態 | 担当エージェント | 備考 |
|----|--------|--------|------|------------------|------|
| T-001 | 要件整理・技術選定・実装計画の作成 | 高 | 完了 | planner | D-002参照。地図=MapLibre+OpenFreeMap、動画=Media3 Transformer、JSON=JsonReaderストリーミング4形式対応、永続化=素のSQLite、minSdk 29 |
| T-002 | 環境確認＋プロジェクト雛形＋地図表示画面 | 高 | 完了 | developer | Gradleプロジェクト新設、MapLibre地図をComposeで表示するだけの最小画面。Android SDK有無をここで確認しdocs/progress.mdに記録 |
| T-003 | タイムラインJSONパース（4形式対応） | 高 | 完了 | developer | 端末内Timeline(Android/iOS)・Takeout Semantic Location History・Takeout Records。共通中間モデルへ正規化。Reviewer指摘はT-003bで対応済み（D-004参照） |
| T-003b | T-003レビュー指摘の修正（null耐性・複数データ源の統合・Gson化） | 高 | 完了 | developer | D-004参照。zip内Records.json/Semantic Location History混在時の優先順位、時刻ソート、JSON null耐性、Gson JsonReaderへの切替とパース統合テスト追加 |
| T-004 | GPSノイズ除去・ルート簡略化（着手前にT-003b再検証のMedium/Low指摘2件も修正） | 高 | 完了 | developer | 正規化→速度スパイク除去→停留ジッタ抑制→時間ガード付きDouglas-Peucker→長期間欠損の分断。加えてTimelineJsonParser.ktの要素スキップ時ログ出力（D-004決定3未実装分）とトップレベル配列nullの耐性を追加する。レビュー指摘（High: Mercator投影距離の実距離判定への誤用）はT-004bで対応済み |
| T-004b | T-004レビュー指摘の修正（TrackCleanerの実距離判定をHaversineへ） | 高 | 完了 | developer | D-005参照。Mercator投影距離は緯度に応じて実距離から乖離するため、速度スパイク除去・停留ジッタ抑制の判定をHaversineに置き換える |
| T-005 | 永続化とインポート導線 | 高 | 完了 | developer | 素のSQLite（日単位BLOB）、SAF経由のファイル/zip取り込み。Reviewer指摘はT-005bで対応済み（D-006参照） |
| T-005b | T-005レビュー指摘の修正（days上書き警告・SQLite変数上限・CancellationException） | 高 | 完了 | developer | D-006参照 |
| T-006 | 地図上のルート表示＋期間指定（日/週/月/年） | 高 | 完了 | developer | RouteFrameRenderer（画面・動画共通描画関数）の新設。Reviewer指摘はT-006bで対応済み（D-007参照） |
| T-006b | T-006レビュー指摘の修正（座標変換スケール・UIスレッドDP・Paint/Path再利用） | 高 | 完了 | developer | D-007参照 |
| T-007 | アニメーション再生と速度制御 | 高 | 完了 | developer | データ時刻↔再生時刻の単調写像、自動速度（非線形圧縮）・手動倍率。Reviewer指摘はT-007bで対応済み（D-008参照） |
| T-007b | T-007レビュー指摘の修正（再生中シークの競合、trimByProgress二重計算） | 高 | 完了 | developer | D-008参照 |
| T-008 | アニメーションの動画書き出し | 高 | 完了 | developer | Media3 Transformer + BitmapOverlay。地図帰属表示の焼き込み必須（R8）。スパイク検証結果はD-009参照。Reviewer指摘はT-008bで対応済み（D-010参照） |
| T-008b | T-008レビュー指摘の修正（MediaStoreロールバック・snapshotタイムアウト・単一点ルートのエラー文言） | 高 | 完了 | developer | D-010参照 |
| T-009 | 仕上げ（エラー処理・a11y・性能確認・README） | 中 | 完了 | developer | 大量点データでの性能確認、リリース手順の記載。`TimelineViewModel.loadRoute`のクラッシュ耐性、Sliderのcontent description、未使用の位置情報権限除外（D-011）を追加修正 |
| T-010 | 実機フィードバック対応: edge-to-edge表示でシステムUIと画面端の要素が重なる | 高 | 完了 | developer | ユーザーが実機インストールして発見。ステータスバー/ナビゲーションバー分のinsetをComposeレイアウトが確保しておらず、上部タブ・下部の再生/保存ボタンが操作不能になっていた。D-012参照。Reviewer指摘はT-010bで対応済み（D-013参照） |
| T-010b | T-010レビュー指摘の修正（ランドスケープ+レガシーナビゲーションバーでの横方向inset未対応） | 中 | 完了 | developer | D-013参照。PeriodSelector・PlaybackControlsに横方向のみのnavigationBars insetを追加 |
| T-011 | 実機報告対応: rawSignals読み飛ばし失敗でインポート全体が失敗する不具合を修正 | 高 | 完了 | developer | ユーザーが実際のTimelineエクスポートファイル（130万行超）をインポートし`End of input`で失敗。`rawSignals`等の未知キー読み飛ばし失敗が既にパース済みの有効データごと破棄する設計欠陥を修正。D-014参照。Reviewer指摘への対応はT-011bで完了（D-015参照） |
| T-011b | T-011レビュー指摘の修正（保護範囲の見落とし2件、例外型の絞り込み） | 高 | 完了 | developer | D-015参照。同種のデータ損失が主要配列自身の途中失敗・キー境界での失敗でも再発する問題を修正し、境界値テスト3件を追加。再検証でJsonIOExceptionの見落としが発覚したがT-011cで対応完了（D-016参照） |
| T-011c | T-011b再検証指摘の修正（JsonIOExceptionが例外型絞り込みの穴になっていた） | 高 | 完了 | developer | D-016参照。catch (JsonSyntaxException)をcatch (JsonParseException)へ変更する1行修正。非EOF系IOExceptionがJsonIOExceptionへラップされ2件目要素消費中に発生するケースの回復テストを追加 |
| T-012 | 560日規模の実データ対応: 計測基盤とSimplifierのANR根治（S0+S1） | 高 | 完了 | developer | D-017参照。時間ガード保護点をDP本体から分離し区切り点として扱う方式へ変更、maxPointCountをdecimateToLimitでハードキャップ化。ベンチマークで28万点・maxPointCount=3000が600秒超未完了→105msへ改善したことを確認（docs/progress.md参照）。Reviewer指摘はT-012bで修正済み（D-018参照） |
| T-012b | T-012レビュー指摘の修正（decimateToLimitが時間ガード保護点を無差別に間引く） | 高 | 完了 | developer | D-018参照。decimateToLimitに保護点情報を渡し、保護点数<=maxPointCountなら保護点を全て残す方式へ修正。保護点生存率が実測約14%→100%（保護点数がmaxPointCount以下の条件下）へ改善したことをテストで確認（docs/progress.md参照） |
| T-013 | 560日規模の実データ対応: 重い処理のUIスレッドからの排除（S2） | 高 | 完了 | developer | D-017参照。RouteOverlayViewのSimplifier呼び出しを100msデバウンス＋Dispatchers.Default化、TimelineScreen.fitBoundsをbbox計算方式へ軽量化、PlaybackController.setRoute/setSpeedMode（内部のPlaybackTimeline.buildAuto）とexportVideoのbuildAutoをDispatchers.Default化した。詳細はdocs/progress.md参照。Reviewer指摘はT-013bで対応済み（D-019参照） |
| T-013b | T-013レビュー指摘の修正（ズームバケット往復時のキャッシュ確定条件、PlaybackControllerの並行性テスト追加） | 中 | 完了 | developer | D-019参照。RouteOverlayView.scheduleSimplifyのコミット直前に現在のズームバケットを再取得し、対象zoomBucketと不一致ならスキップするよう修正。PlaybackControllerTest.ktを新設し、runBlocking+launchのみ（新規依存なし）でsetRoute/setSpeedModeの並行呼び出しの最終状態を検証 |
| T-014 | 560日規模の実データ対応: 概観点列と詳細ウィンドウの導入（S3） | 高 | 完了 | developer | D-017参照。RouteOverviewを新設し日ごとに小予算DP（既定128点/日）した点列をストリーミングで結合。TimelineRepositoryにqueryDayDates/queryDateRange/queryDaysStreamingを追加。TimelineViewModel.loadRouteを短期間（7日以下）はqueryDays、長期間はRouteOverviewからの二分探索切り出しへ分岐、fitBounds用bboxもRouteOverview.boundsForDateRangeを再利用。詳細はdocs/progress.md参照 |
| T-014b | T-014レビュー指摘の修正（RouteOverviewキャッシュの並行性テスト欠如、無効化時の未キャンセルJob、未使用メソッド） | 高 | 完了 | developer | D-020参照。世代ガード付きキャッシュロジックをTimelineViewModelからRouteOverviewCache（DB非依存）へ切り出しRouteOverviewCacheTestで検証、invalidate()がbuildJob.cancel()を呼ぶよう修正、未使用のTimelineRepository.queryDayDates()を削除。詳細はdocs/progress.md参照 |
| T-015 | 560日規模の実データ対応: インポート進捗表示（S4） | 高 | 未着手 | developer | D-017参照。要件「読み込み完了をユーザーが明確に確認できる」に対応 |
| T-016 | 560日規模の実データ対応: インポート時のメモリ削減（S5） | 中 | 未着手 | developer | D-017参照 |
| T-017 | 560日規模の実データ対応: 全期間の期間種別（S6） | 中 | 未着手 | developer | D-017参照。PeriodType.ALLの新設、D-017決定1（既定を全期間にする） |
| T-018 | 560日規模の実データ対応: 再生時間選択肢の変更（S7） | 中 | 未着手 | developer | D-017参照。30/60/120/180/300秒、既定60秒へ変更。D-017決定2（全期間選択時は手動モード無効化） |
| T-019 | 560日規模の実データ対応: 関心度モデルの改善（S8） | 中 | 未着手 | developer | D-017参照。滞在時間・長距離移動の頭打ち、イベント密度の反映 |
| T-020 | 560日規模の実データ対応: ポリライン分断と軌跡の描き分け（S9） | 中 | 未着手 | developer | D-017参照。過去/直近/現在の3層描画、6時間超ギャップでの分断表示 |
| T-021 | 560日規模の実データ対応: 詳細ウィンドウの遅延ロード（S10） | 中 | 未着手 | developer | D-017参照 |
| T-022 | 560日規模の実データ対応: カメラ制御スパイク検証（S11） | 高 | 未着手 | developer | D-017参照。MapSnapshotterの実在・契約確認。失敗時はフェーズ5全体を見直す判断ポイント |
| T-023 | 560日規模の実データ対応: CameraDirector（S12） | 中 | 未着手 | developer | D-017参照。純Kotlin、カメラ軌道設計。T-022完了後に着手 |
| T-024 | 560日規模の実データ対応: 画面再生でのカメラ追従（S13） | 中 | 未着手 | developer | D-017参照 |
| T-025 | 560日規模の実データ対応: 動画書き出しのカメラ制御（S14） | 高 | 未着手 | developer | D-017参照。地図を下地からBitmapOverlay内部へ移す方式変更（D-009更新） |
| T-026 | 560日規模の実データ対応: 全体再計測とドキュメント更新（S15） | 中 | 未着手 | developer | D-017参照。最終ステップ |

## バックログ（未着手・優先度未確定）

- 実データ（実際のTimelineエクスポートファイル）でのパーサ検証。ユーザーから個人情報を伏せたサンプル提供を受けられる場合に着手（D-002参照）
- rawSignalsへの対応（D-002で v1スコープ外と決定。Records.json本体はD-004によりv1スコープに含めることへ変更済み）
- TrackCleaner.removeSpeedSpikesが点列の先頭・末尾を判定対象外とする構造的な限界への対応（T-004レビューLow、実運用での発生可能性が低いため見送り）
- TimelineJsonParserでJSON文書全体が`null`一つだけの場合のクラッシュ対応（T-004レビューNit、実運用での発生可能性が極めて低いため見送り）
- 日付をまたぐ区間の移動距離がdays.distance_metersに計上されない件（T-005レビューNit、統計表示のみの誤差で点データ自体に影響しないため見送り。D-006参照）
- 点・セグメント0件のインポートが無警告で成功表示される件（T-005レビューNit、見送り。D-006参照）
- 週ラベルの年またぎ（12月最終週）を検証する単体テストの追加（T-006レビューLow、実装自体は妥当と判断、見送り。D-007参照）
- fitBoundsが実質同一地点の複数点で極端ズームになりうる可能性（T-006レビューLow、クラッシュ耐性は確認済み、見送り。D-007参照）
- 同一データ時刻・異なる位置の複数点があるとbinarySearchのタイブレークが不定でマーカーが固まりうる件（T-007レビューLow、実データでの発生頻度不明のため見送り。D-008参照）
- PlaybackTimeline.buildAutoのalpha/beta負値検証の追加（T-007レビューNit、現状到達不能なコードパスのため見送り。D-008参照）
- 再生中にシークバーを末尾(progress=1.0)までドラッグして離すと先頭から再スタートしてしまう件（T-007bレビューLow、特定操作のみに限定されクラッシュ・データ破損なし。見送り）
- MediaStoreロールバック時のdelete呼び出し自体が失敗した場合の例外伝播（T-008bレビューLow、二重の狭い条件が重なる必要があり発生頻度が低いため見送り。D-010参照）
- snapshotタイムアウト後に遅延コールバックが来た場合のBitmap未回収（T-008bレビューNit、実害なしのため見送り）
- `parseZip`が複数エントリを走査中、あるエントリの`parseRoot`が`format`未確定のまま例外を投げると、それ以前に処理済みだった別エントリのデータもろとも失われる件（T-011で発見、稀な複合条件のため見送り。D-014参照）
- `Simplifier.decimateNonProtected`内の到達不能な分岐（デッドコード、実害なし）とprotectedCount==maxPointCount境界値の専用テスト追加（T-012bレビューLow/Nit、見送り。D-018参照）

## メモ

- 新しいタスクを追加したら、必ず優先度と状態を設定すること。
- タスクの状態が変わったら都度このファイルを更新する（作業完了後にまとめて更新しない）。
- 詳細な作業内容や経緯は [progress.md](./progress.md) を参照。
- 設計上の判断が必要になった場合は [decisions.md](./decisions.md) に記録する。
- **状態列の値は必ず「状態の定義」にある6値を完全一致（前後の空白のみ許容）で使うこと**。SessionStart Hookの完了タスクフィルタ（`.claude/settings.json`）が状態列の完全一致で判定しているため、`完了(要再確認)`のような接尾辞付きの値は「未完了」として扱われる（安全側だが、フィルタが効かなくなる）。
- **タスク名・備考欄に未エスケープの`|`を含めないこと**。SessionStart Hookは`docs/tasks.md`を`awk -F'|'`で列分割しており、セル内に`|`があると以降の列がずれる。Markdownテーブルとしても不正な記法になるため、通常の運用では発生しない想定。
