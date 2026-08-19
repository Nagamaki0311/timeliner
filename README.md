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
3. Androidアプリのビルド手順は実装後に本節へ追記する。

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

アプリ本体のソースコード構成は、実装開始後に本節へ追記する。
