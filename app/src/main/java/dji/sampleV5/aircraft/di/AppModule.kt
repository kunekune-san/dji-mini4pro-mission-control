package dji.sampleV5.aircraft.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dji.sampleV5.aircraft.dji.CameraManager
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.dji.GimbalManager
import dji.sampleV5.aircraft.dji.MediaDownloadManager
import dji.sampleV5.aircraft.mission.MissionStateMachine
import dji.sampleV5.aircraft.mqtt.MqttClientManager
import dji.sampleV5.aircraft.mqtt.MqttMissionBridge
import dji.sampleV5.aircraft.mqtt.MqttStatusReporter
import javax.inject.Singleton

/**
 * Hilt DI 模組 — 將現有 object singleton 橋接到 Hilt 依賴圖。
 *
 * 遷移策略：先用 @Provides 包裝現有 singleton，逐步將 call site
 * 改為 constructor injection，最後再將 object 改為 @Singleton class。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ── dji/ 硬體抽象層 ────────────────────────────

    @Provides
    @Singleton
    fun provideFlightManager(): FlightManager = FlightManager

    @Provides
    @Singleton
    fun provideCameraManager(): CameraManager = CameraManager

    @Provides
    @Singleton
    fun provideGimbalManager(): GimbalManager = GimbalManager

    @Provides
    @Singleton
    fun provideMediaDownloadManager(): MediaDownloadManager = MediaDownloadManager

    // ── mission/ 任務控制層 ─────────────────────────

    @Provides
    @Singleton
    fun provideMissionStateMachine(): MissionStateMachine = MissionStateMachine

    // ── mqtt/ 通訊層 ───────────────────────────────

    @Provides
    @Singleton
    fun provideMqttClientManager(): MqttClientManager = MqttClientManager

    @Provides
    @Singleton
    fun provideMqttMissionBridge(): MqttMissionBridge = MqttMissionBridge

    @Provides
    @Singleton
    fun provideMqttStatusReporter(): MqttStatusReporter = MqttStatusReporter
}
