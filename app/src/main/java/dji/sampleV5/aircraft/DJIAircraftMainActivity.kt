package dji.sampleV5.aircraft

import dji.v5.common.utils.GeoidManager
import dji.v5.ux.core.communication.DefaultGlobalPreferences
import dji.v5.ux.core.communication.GlobalPreferencesManager
import dji.v5.ux.core.util.UxSharedPreferencesUtil
import android.content.Intent
import dji.sampleV5.aircraft.settings.SettingsActivity
import dji.sampleV5.aircraft.dji.DownloadResult
import dji.sampleV5.aircraft.dji.MediaDownloadManager
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import dji.v5.ux.sample.showcase.defaultlayout.DefaultLayoutActivity
import dji.v5.ux.sample.showcase.widgetlist.WidgetsActivity
import kotlinx.coroutines.launch

/**
 * Class Description
 *
 * @author Hoker
 * @date 2022/2/14
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class DJIAircraftMainActivity : DJIMainActivity() {

    override fun prepareUxActivity() {
        UxSharedPreferencesUtil.initialize(this)
        GlobalPreferencesManager.initialize(DefaultGlobalPreferences(this))
        GeoidManager.getInstance().init(this)

        enableDefaultLayout(DefaultLayoutActivity::class.java)
        enableWidgetList(WidgetsActivity::class.java)

        binding.btnMissionControl.setOnClickListener {
            startActivity(Intent(this, MissionControlActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnTestDownload.setOnClickListener {
            lifecycleScope.launch {
                Log.d("TestDownload", "Manual download triggered")
                val result = MediaDownloadManager.downloadLastNPhotos(7)
                when (result) {
                    is DownloadResult.Success -> {
                        Log.d("TestDownload", "Success: ${result.count} photos")
                        Toast.makeText(this@DJIAircraftMainActivity,
                            "下載成功：${result.count} 張", Toast.LENGTH_SHORT).show()
                    }
                    is DownloadResult.Failure -> {
                        Log.e("TestDownload", "Failed: ${result.reason}")
                        Toast.makeText(this@DJIAircraftMainActivity,
                            "下載失敗：${result.reason}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun prepareTestingToolsActivity() {
        enableTestingTools(AircraftTestingToolsActivity::class.java)
    }
}