/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.pluskey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import com.android.internal.os.DeviceKeyHandler
import java.util.concurrent.Executors

class KeyHandler(private val context: Context) : DeviceKeyHandler {
    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private val cameraManager = context.getSystemService(CameraManager::class.java)!!
    private val vibrator = context.getSystemService(Vibrator::class.java)!!

    private val packageContext =
        context.createPackageContext(KeyHandler::class.java.getPackage()!!.name, 0)
    private val sharedPreferences
        get() =
            packageContext.getSharedPreferences(
                packageContext.packageName + "_preferences",
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS,
            )

    private val executorService = Executors.newSingleThreadExecutor()

    // Only touched from executorService
    private var torchCameraId: String? = null
    private var torchCallbackRegistered = false
    private var torchEnabled = false

    private val torchCallback =
        object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == torchCameraId) {
                    torchEnabled = enabled
                }
            }
        }

    private val broadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    executorService.submit { ensureTorchCallback() }
                }
            }
        }

    init {
        context.registerReceiver(broadcastReceiver, IntentFilter(Intent.ACTION_BOOT_COMPLETED))
    }

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        if (event.scanCode != PLUS_KEY_SCANCODE || event.device?.name != PLUS_KEY_DEVICE) {
            return event
        }

        val action = sharedPreferences.getString(PLUS_KEY_ACTION_KEY, "0")!!.toInt()
        if (action == ACTION_DEFAULT) {
            return event
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            executorService.submit { handleAction(action) }
        }

        return null
    }

    private fun handleAction(action: Int) {
        when (action) {
            ACTION_TORCH -> toggleTorch()
            ACTION_RINGER -> cycleRingerMode()
            else -> return
        }
        vibrator.vibrate(CLICK_EFFECT, HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES)
    }

    private fun ensureTorchCallback() {
        if (torchCallbackRegistered) {
            return
        }
        try {
            torchCameraId =
                cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager
                        .getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            cameraManager.registerTorchCallback(executorService, torchCallback)
            torchCallbackRegistered = true
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to query torch camera", e)
        }
    }

    private fun toggleTorch() {
        ensureTorchCallback()
        val cameraId = torchCameraId ?: return
        try {
            cameraManager.setTorchMode(cameraId, !torchEnabled)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to toggle torch", e)
        }
    }

    private fun cycleRingerMode() {
        audioManager.ringerModeInternal =
            when (audioManager.ringerModeInternal) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
    }

    companion object {
        private const val TAG = "PlusKeyHandler"

        // Must match configs/keylayout/gpio-keys.kl
        private const val PLUS_KEY_DEVICE = "gpio-keys"
        private const val PLUS_KEY_SCANCODE = 735

        // Preference keys
        private const val PLUS_KEY_ACTION_KEY = "config_plus_key_action"

        // Must match res/values/arrays.xml
        private const val ACTION_DEFAULT = 0
        private const val ACTION_TORCH = 1
        private const val ACTION_RINGER = 2

        private val CLICK_EFFECT = VibrationEffect.get(VibrationEffect.EFFECT_CLICK)
        private val HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
    }
}
