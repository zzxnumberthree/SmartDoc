# AI Smart Document Management System (AI-SDMS)

##  概要 (Project Overview)
**「ドキュメント管理を、もっとスマートに。」**
Spring Boot 3とGoogle Gemini APIを活用し、 単なるファイルの保存・削除にとどまらず、アップロードされた技術文書の内容を AI が自動的に解析し、要約を生成することで、情報の検索性と管理効率を劇的に向上させます。 「2025年の崖」と呼ばれるDX（デジタルトランスフォーメーション）の課題解決を意識して開発しました。


日本のIT現場における「ドキュメント整理の繁雑さ」という課題を解決するために開発しました。

> **開発期間**: 約20日間
> **担当**: バックエンド設計・開発、API連携、テスト実装（個人開発）

---

## 主な機能 (Key Features)
### 1. インテリジェントな文書解析 (AI Summarization)
- アップロードされた以下の形式のファイルを自動解析し、要約を生成します。

- 対応フォーマット: .txt, .pdf, .md ...

- 特長: Spring AI を活用した堅牢なプロンプトエンジニアリングと Gemini 2.5-flash による高速な推論。

### 2. 安全で堅牢なデータ操作 (Robust CRUD & Logical Deletion)
- ドキュメントの参照、更新、削除の基本機能。

- 論理削除 (Logical Deletion): データの安全性と監査（監査証跡）を考慮し、データベースからの物理削除ではなく論理削除を実装。

- 一貫したエラーハンドリング: 独自の GlobalExceptionHandler により、予期せぬエラーやバリデーション違反も適切な形式で返却します。

##  技術スタック (Tech Stack)
最新のLTSバージョンを採用し、保守性とパフォーマンスを意識した選定を行いました。

- **Language**: Java 21
- **Framework**: Spring Boot 3.4.1
- **AI Integration**: Google Gemini 2.5-flash (Spring AI)
- **Build Tool**: Maven
- **Database**: MySQL 8.0
- **Version Control**: Git / GitHub

---

##  アーキテクチャ (Architecture)
保守性を高めるため、責任の分離（Separation of Concerns）を意識したレイヤードアーキテクチャを採用しています。

```mermaid
graph TD
    Client[Client / API Tester] -->|HTTP Request| Controller
    subgraph "Application Layer"
        Controller -->|DTO| Service
        Service -->|Validation/Logic| Repository
        Service -->|Prompt Engineering| GeminiService[Gemini AI Service]
    end
    subgraph "Infrastructure"
        Repository -->|JPA| Database[(Database)]
        GeminiService -->|REST| GoogleAI[Google Gemini API]
    end
    %% AOP Logic Visualization
    AOP[AOP Logging & Exception Handler] -.->|Cross-Cutting| Controller
    AOP -.->|Cross-Cutting| Service
```

---

##  工夫した点・苦労した点 (Points of Ingenuity & Challenges)

### 1. 運用を意識したログ設計 (AOP Implementation)
開発当初、ログ出力が散在しデバッグが困難でした。これを解決するために **Spring AOP (Aspect Oriented Programming)** を導入しました。

* コントローラー層やサービス層のメソッド実行前後で、自動的にリクエスト情報や処理時間をログ出力する仕組みを構築。
* これにより、ビジネスロジックを汚すことなく、**トレーサビリティ（追跡可能性）**を大幅に向上させました。

### 2. 外部API連携と依存関係の管理 (API Integration & Dependency Management)
**Gemini API** の統合において、レスポンスの遅延や形式の不一致に直面しました。

* タイムアウト設定の最適化や、レスポンス解析処理の堅牢化を行うことで、安定したAI機能の呼び出しを実現しました。
* また、**Java 21** と **Spring Boot 3.4.1** の組み合わせにおける依存関係（Dependencies）の競合を解消し、モダンな開発環境を整えました。

### 3. 設計思想へのこだわり (Architectural Design)
単に動くコードを書くのではなく、将来の拡張性を考慮し、機能ごとにクラスの責務を明確に分離しました。

---

## 環境構築 (Setup)
本プロジェクトは Docker Compose を使用して簡単にデプロイできます。実行する前に、Google Gemini API キーを設定する必要があります。

リポジトリをクローン: 

      git clone https://github.com/zzxnumberthree/SmartDoc.git

1. API キーの取得
[Google AI Studio](https://aistudio.google.com/) にアクセスし、無料の API キーを取得してください。

2. アプリケーションの起動
   設定が完了したら、以下のコマンドを実行してコンテナを起動します。

データベースを起動:

      docker-compose build app

      docker-compose up -d
3. 環境変数の設定
```
 export GOOGLE_API_KEY=ここにあなたのAPIキーを貼り付けてください
```
---

##  今後の展望 (Future Roadmap)

-  ドキュメント内容に基づくベクトル検索 (RAG) の実装
-  ユーザー認証機能 (Spring Security) の追加
-  フロントエンド (React/Vue) の実装

---

## Connect With Me
email:  zzxnumberthree@gmail.com

