# DJI Mini 4 Pro Mission Control

> **免責聲明**：本程式由 [Claude Code](https://docs.anthropic.com/en/docs/claude-code) 搭配 Mimo-V2.5-Pro 模型自動生成，僅供學習與研究用途。無人機飛行具有潛在風險，請務必在安全環境下測試，並遵守當地航空法規。使用本程式所造成之任何後果，開發者不承擔任何責任。

基於 DJI MSDK v5 開發的 Android 無人機控制應用，透過 MQTT 接收遠端指令執行自動飛行任務。

**English version: [README_EN.md](README_EN.md)**

## 目標硬體

| 項目 | 型號 |
|------|------|
| 無人機 | DJI Mini 4 Pro |
| 遙控器 | DJI RC-N2 |
| SDK | DJI MSDK v5 (5.18.0) |

## 功能概覽

### 任務模式

- **任務 A（定點錄影 + 拍照）**：飛往目標座標 → 原地 360° 旋轉錄影 → 7 張多角度拍照 → 返航
- **任務 B（環繞錄影）**：飛往目標座標 → 以指定半徑環繞目標 360° 錄影 → 返航

### 核心能力

- MQTT 遠端指令控制（起飛、返航、取消、重置、任務啟動）
- 即時狀態回報（座標、高度、電池、GPS、任務狀態）
- 雲台角度控制（錄影/拍照獨立設定）
- 安全保護（GPS 訊號檢查、電池監測、飛行邊界限制）
- 任務完成後自動下載照片

## 快速開始

### 環境需求

- Android Studio (2024.1+)
- JDK 17+
- Android SDK 35
- DJI 開發者帳號（[developer.dji.com](https://developer.dji.com)）

### 設定步驟

1. **Clone 專案**
   ```bash
   git clone <repo-url>
   ```

2. **申請 DJI API Key**
   - 前往 [DJI 開發者平台](https://developer.dji.com) 註冊
   - 建立應用程式，取得 API Key
   - Package Name: `com.example.djimission`

3. **設定 gradle.properties**
   ```bash
   cp gradle.properties.example gradle.properties
   ```
   編輯 `gradle.properties`，填入你的 API Key：
   ```properties
   AIRCRAFT_API_KEY = your_api_key_here
   ```

4. **開啟專案**
   - 用 Android Studio 開啟專案根目錄
   - 等待 Gradle sync 完成

5. **編譯執行**
   ```bash
   ./gradlew assembleDebug
   ```
   或直接在 Android Studio 點擊 Run。

## 架構

```
├── app/                    主應用模組
│   ├── config/             全域設定（AppConfig.kt）
│   ├── dji/                DJI SDK 封裝層
│   │   ├── FlightManager.kt       飛行控制 + 狀態監聽
│   │   ├── CameraManager.kt       相機操作
│   │   ├── GimbalManager.kt       雲台控制
│   │   └── MediaDownloadManager.kt   媒體下載
│   ├── mission/            任務執行
│   │   └── MissionStateMachine.kt   核心狀態機
│   ├── mqtt/               MQTT 通訊層
│   │   ├── MqttClientManager.kt     連線管理
│   │   ├── MqttMissionBridge.kt     指令橋接
│   │   ├── MqttStatusReporter.kt    狀態回報
│   │   └── MqttPayloadModels.kt     資料模型
│   ├── settings/           設定頁面
│   ├── safety/             安全事件記錄
│   └── pages/              UI 頁面
│       ├── DJIMainActivity.kt           App 入口
│       └── MissionControlActivity.kt    FPV + HUD
├── uxsdk/                  DJI UX SDK 函式庫
├── build.gradle            頂層建構設定
├── settings.gradle         模組設定
├── dependencies.gradle     依賴版本管理
└── gradle.properties       API Key / 簽章設定
```

## MQTT 通訊

| Topic | 方向 | 用途 |
|-------|------|------|
| `{prefix}/command` | 後端 → App | 飛行指令（takeoff/rtl/cancel/reset） |
| `{prefix}/mission/start` | 後端 → App | 任務啟動（A/B 模式） |
| `{prefix}/status` | App → 後端 | 飛行狀態回報 |
| `{prefix}/ack` | App → 後端 | 指令執行確認 |
| `{prefix}/heartbeat` | App → 後端 | 連線心跳 |

### Mission Start Payload

```json
{
  "msg_id": "msn_001",
  "mission": "A",
  "target": { "lat": 0.0, "lng": 0.0, "alt": 10.0 },
  "orbit": { "radius": 0, "alt": 5.0, "gimbal_pitch": -45, "photo_gimbal_pitch": -30 },
  "photo_count": 7,
  "timestamp": 1700000002000
}
```

- `mission`: `"A"`（定點錄影+拍照）或 `"B"`（環繞錄影，需 `radius > 0`）
- `orbit.radius`: 環繞半徑（公尺），0 = 原地旋轉
- `orbit.alt`: 環繞高度（公尺），null = 使用 target.alt
- `orbit.gimbal_pitch`: 錄影時雲台俯仰角（度）
- `orbit.photo_gimbal_pitch`: 拍照時雲台俯仰角（度），僅 A 模式

## 授權條款

MIT License
