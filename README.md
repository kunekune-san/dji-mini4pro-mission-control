> [English](README_EN.md) | 繁體中文

# DJI Mini 4 Pro 無人機控制 App

基於 DJI MSDK v5 開發的 Android 無人機控制應用，透過 MQTT 接收遠端指令執行自動飛行任務。

## 快速開始

### 1. 取得 DJI App Key（必要）
DJI MSDK 需要開發者 App Key 才能連線無人機：
1. 修改 `app/build.gradle` 的 `applicationId`（目前 `com.example.n8ntest`）為**你自己的包名**——DJI App Key 綁定包名，同一個包名只能綁定一個 DJI App
2. 前往 [DJI Developer Portal](https://developer.dji.com) 註冊並建立 App，**綁定你的 applicationId**
3. 取得 App Key，填入 `gradle.properties`：
   ```
   DJI_API_KEY = 你的 App Key
   ```
4. （可選）地圖功能需另填 `GMAP_API_KEY`（Google Maps）與 `MAPLIBRE_TOKEN`

### 2. 建置與執行
- 用 Android Studio 開啟本專案 → 執行 `assembleDebug`（已改用預設 debug 簽章，無需個人 keystore）
- MQTT broker（預設公開 `broker.emqx.io`）與 topic 前綴可在 APP 的「設定」頁修改

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
- **任務 C（螺旋上升錄影）**：飛往目標座標 → 以固定半徑螺旋上升 N 圈錄影（Z_min → Z_max），雲台動態鎖定中心 → 返航
- **任務 D（擴展螺旋錄影）**：飛往目標座標 → 半徑從 rStart 線性增長到 rEnd 的螺旋上升 N 圈錄影，雲台動態鎖定中心 → 返航
- **多站任務（Multi-Stop）**：依序執行多個站點的 A/B/C/D 任務，站間不降落，最後返航

### 核心能力

- MQTT 遠端指令控制（起飛、返航、取消、重置、任務啟動、多站任務啟動）
- 即時狀態回報（座標、高度、電池、GPS、任務狀態，飛行中每 10 秒）
- 雲台角度控制（錄影/拍照獨立設定）
- 安全保護（GPS 訊號檢查、電池監測、飛行邊界限制）
- 任務完成後自動下載照片

## 架構

```
app/
├── config/          全域設定
│   └── AppConfig.kt             編譯期常數 + SharedPreferences
├── dji/             DJI SDK 封裝層
│   ├── FlightManager.kt         飛行控制 + 狀態監聽 + 安全參數
│   ├── CameraManager.kt         相機操作（拍照/錄影/對焦）
│   ├── GimbalManager.kt         雲台角度控制
│   └── MediaDownloadManager.kt  媒體下載（串流寫入）
├── mission/         任務執行
│   ├── MissionStateMachine.kt   核心狀態機
│   ├── FlightPhaseExecutor.kt   飛行階段（起飛/爬升/飛往目標）
│   ├── OrbitController.kt       環繞飛行（A 原地旋轉、B 環繞、C 螺旋、D 擴展螺旋）
│   ├── VisualYawAssist.kt       視覺輔助 Yaw 追蹤（SAD 模板匹配）
│   ├── PhotoSequenceController.kt  拍照序列
│   ├── VirtualStickController.kt   VS 啟用/停用/監聽
│   └── VirtualStickParams.kt    VS 參數工廠
├── mqtt/            MQTT 通訊層
│   ├── MqttClientManager.kt     連線管理
│   ├── MqttMissionBridge.kt     指令橋接
│   ├── MqttStatusReporter.kt    狀態回報
│   └── MqttPayloadModels.kt     資料模型
├── safety/          安全事件
│   ├── SafetyEventLogger.kt     CSV 異常記錄
│   ├── SafetyAnomalyType.kt     異常類型列舉
│   └── SafetyAnomalyRecord.kt   異常記錄資料
├── util/            工具類
│   ├── GeoMath.kt               GPS 距離/方位角計算
│   ├── AppLog.kt                Log wrapper（release 靜默）
│   └── ...                      其他 UI 工具
├── settings/        設定頁面
│   └── SettingsActivity.kt      MQTT/飛行限制/RC 模式設定
└── pages/           UI 頁面
    ├── DJIMainActivity.kt       App 入口
    └── MissionControlActivity.kt  FPV + HUD
```

## MQTT 通訊

| Topic | 方向 | 用途 |
|-------|------|------|
| `{prefix}/command` | 後端 → App | 飛行指令（takeoff/rtl/cancel/reset） |
| `{prefix}/mission/start` | 後端 → App | 任務啟動（A/B/C/D 模式） |
| `{prefix}/mission/multi` | 後端 → App | 多站任務啟動（至少 2 站） |
| `{prefix}/status` | App → 後端 | 飛行狀態回報 |
| `{prefix}/ack` | App → 後端 | 指令執行確認 |
| `{prefix}/heartbeat` | App → 後端 | 連線心跳 |

詳細 MQTT 協定請參考 [MQTT API 合約](docs/mqtt-api-contract.html)

## 開發環境

- Android Studio
- Kotlin / Java
- DJI MSDK v5（**不可使用 v4 語法**）

## 任務流程

### 任務 A
1. 自動起飛 → 爬升至目標高度
2. 飛往目標座標
3. （若有 orbit.alt）降至錄影高度
4. 原地 370° 旋轉錄影
5. 拍照（6 張 + 1 張俯視，共 7 張）
6. 自動返航降落

### 任務 B
1. 自動起飛 → 爬升至目標高度
2. 飛往目標座標
3. 降至 orbit.alt
4. 飛至環繞起點，穩定朝向目標中心
5. 以指定半徑順時針環繞 360° 錄影
6. 停止錄影 → 自動返航降落

### 任務 C（螺旋上升）
1. 自動起飛 → 爬升至目標高度
2. 飛往目標座標
3. 降至 orbit.alt（Z_min）
4. 飛至環繞起點（目標北方 orbit.radius 處），視覺預選取鎖定中心
5. 啟動錄影 → 以 orbit.radius 順時針螺旋上升（Z_min → Z_max，N 圈）
6. 雲台隨高度動態調整，持續鎖定中心點
7. 漸進減速 → GIMBAL_PULL_UP（雲台 6 秒平滑拉平，錄影中）→ 定格 → 停止錄影
8. 爬升至 target.alt → 自動返航降落

### 任務 D（擴展螺旋上升）
1. 自動起飛 → 爬升至目標高度
2. 飛往目標座標
3. 降至 orbit.alt（Z_min）
4. 飛至環繞起點（目標北方 orbit.radius 處）
5. 視覺預選取（外移 5m → 雲台 -65° → 選取目標 → Yaw 居中）
6. 啟動錄影 → 以 orbit.radius 順時針擴展螺旋上升（Z_min → Z_max，N 圈）
7. 半徑從 radius 線性增長到 radius_end，雲台隨高度動態調整
8. 漸進減速 →（可選 final_heading：繞行至此方位角）→ GIMBAL_PULL_UP（雲台 6 秒平滑拉平，錄影中）→ 定格 → 停止錄影
9. 爬升至 target.alt → 自動返航降落

### 飛行速度參數

| 參數 | 值 | 說明 |
|------|-----|------|
| `VIRTUAL_STICK_MAX_SPEED` | 5.0 m/s | 水平最大飛行速度 |
| `CLIMB_THROTTLE` | 5.0 m/s | 垂直爬升/下降速度（DJI Mini 4 Pro 上限） |
| `YAW_SPEED_DEG_PER_SEC` | 45 °/s | 一般 yaw 旋轉速度 |
| `RECORDING_YAW_SPEED_DEG_PER_SEC` | 12 °/s | 錄影時 yaw 旋轉速度（穩定） |
| `SPEED_PROPORTIONAL_GAIN` | 0.5 | 速度 = min(distance × gain, max_speed) |
| `YAW_ALIGN_PROPORTIONAL_GAIN` | 0.8 | Yaw 對齊比例增益 |
| `POSITION_HOLD_MAX_SPEED` | 1.0 m/s | 位置保持最大速度 |
| VirtualStick 座標系 | GROUND | pitch(+)=East, roll(+)=North, yaw=角速度, verticalThrottle=垂直速度 |
| 指令頻率 | 20 Hz | 每 50ms 發送一次控制指令 |

### 多站任務（Multi-Stop）
1. 自動起飛 → 飛往站點 1
2. 執行站點 1 任務（A、B、C 或 D 模式）
3. 不降落，直接飛往站點 2（狀態：FLYING_TO_NEXT）
4. 執行站點 2 任務
5. 重複直到所有站點完成
6. 自動返航降落 → 下載所有照片

## 文件索引

| 文件 | 內容 |
|------|------|
| [架構說明](docs/architecture.html) | 系統架構與模組設計 |
| [程式碼參考](docs/code-reference.html) | 核心類別與流程速查 |
| [MQTT API 合約](docs/mqtt-api-contract.html) | 完整 MQTT 通訊協定 |
| [操作手冊](docs/operation-manual.html) | 完整操作說明 |

## 授權

本專案以 **MIT License** 釋出，全文詳見 [LICENSE](LICENSE)。

- `uxsdk/` 與 `app/` 下的 `dji.sampleV5.*` 衍生自 [DJI Sample Code V5](https://github.com/dji-sdk/Mobile-SDK-Android-V5)，Copyright (c) 2022 DJI，MIT License。
- 其餘自製模組（`mission/`、`mqtt/`、`safety/` 等）Copyright (c) 2026 kune，同為 MIT License。
- DJI Mobile SDK for Android 本體**未隨本專案散布**，為建置期 Maven 相依，適用 [DJI EULA](http://developer.dji.com/policies/eula/)。
