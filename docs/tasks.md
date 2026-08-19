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
| T-004 | GPSノイズ除去・ルート簡略化 | 高 | 未着手 | developer | 正規化→速度スパイク除去→停留ジッタ抑制→時間ガード付きDouglas-Peucker→長期間欠損の分断 |
| T-005 | 永続化とインポート導線 | 高 | 未着手 | developer | 素のSQLite（日単位BLOB）、SAF経由のファイル/zip取り込み |
| T-006 | 地図上のルート表示＋期間指定（日/週/月/年） | 高 | 未着手 | developer | RouteFrameRenderer（画面・動画共通描画関数）の新設 |
| T-007 | アニメーション再生と速度制御 | 高 | 未着手 | developer | データ時刻↔再生時刻の単調写像、自動速度（非線形圧縮）・手動倍率 |
| T-008 | アニメーションの動画書き出し | 高 | 未着手 | developer | Media3 Transformer + BitmapOverlay。地図帰属表示の焼き込み必須（R8） |
| T-009 | 仕上げ（エラー処理・a11y・性能確認・README） | 中 | 未着手 | developer | 大量点データでの性能確認、リリース手順の記載 |

## バックログ（未着手・優先度未確定）

- 実データ（実際のTimelineエクスポートファイル）でのパーサ検証。ユーザーから個人情報を伏せたサンプル提供を受けられる場合に着手（D-002参照）
- rawSignalsへの対応（D-002で v1スコープ外と決定。Records.json本体はD-004によりv1スコープに含めることへ変更済み）

## メモ

- 新しいタスクを追加したら、必ず優先度と状態を設定すること。
- タスクの状態が変わったら都度このファイルを更新する（作業完了後にまとめて更新しない）。
- 詳細な作業内容や経緯は [progress.md](./progress.md) を参照。
- 設計上の判断が必要になった場合は [decisions.md](./decisions.md) に記録する。
- **状態列の値は必ず「状態の定義」にある6値を完全一致（前後の空白のみ許容）で使うこと**。SessionStart Hookの完了タスクフィルタ（`.claude/settings.json`）が状態列の完全一致で判定しているため、`完了(要再確認)`のような接尾辞付きの値は「未完了」として扱われる（安全側だが、フィルタが効かなくなる）。
- **タスク名・備考欄に未エスケープの`|`を含めないこと**。SessionStart Hookは`docs/tasks.md`を`awk -F'|'`で列分割しており、セル内に`|`があると以降の列がずれる。Markdownテーブルとしても不正な記法になるため、通常の運用では発生しない想定。
