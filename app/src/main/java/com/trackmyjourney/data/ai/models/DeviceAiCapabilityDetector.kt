package com.trackmyjourney.data.ai.models

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceAiCapabilityDetector @Inject constructor(
    private val context: Context
) {
    fun detect(): DeviceCapabilityResult {
        val totalRamMb = getTotalRamMb()
        val availableStorageMb = getAvailableStorageMb()
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        val aiCoreAvailable = isAiCoreAvailable()
        val aiCoreVersion = if (aiCoreAvailable) getAiCoreVersion() else null
        val mlKitGenAiAvailable = isMlKitGenAiAvailable()
        val ramTier = determineRamTier(totalRamMb)
        val performanceClass = determinePerformanceClass(totalRamMb, Build.VERSION.SDK_INT)
        val recommendedMode = recommendMode(aiCoreAvailable, ramTier, availableStorageMb)

        return DeviceCapabilityResult(
            androidVersion = Build.VERSION.SDK_INT,
            sdkInt = Build.VERSION.SDK_INT,
            aiCoreAvailable = aiCoreAvailable,
            aiCoreVersion = aiCoreVersion,
            mlKitGenAiAvailable = mlKitGenAiAvailable,
            ramTier = ramTier,
            totalRamMb = totalRamMb,
            availableStorageMb = availableStorageMb,
            performanceClass = performanceClass,
            supportedAbis = supportedAbis,
            recommendedMode = recommendedMode
        )
    }

    private fun getTotalRamMb(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem / (1024 * 1024)
    }

    private fun getAvailableStorageMb(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            availableBytes / (1024 * 1024)
        } catch (e: Exception) {
            0L
        }
    }

    private fun isAiCoreAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.google.android.aicore", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getAiCoreVersion(): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo("com.google.android.aicore", 0)
            packageInfo.versionName
        } catch (e: Exception) {
            null
        }
    }

    private fun isMlKitGenAiAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.google.android.gms", 0)
            Build.VERSION.SDK_INT >= 24
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun determineRamTier(totalRamMb: Long): RamTier {
        return when {
            totalRamMb >= RamTier.VERY_HIGH.minMb -> RamTier.VERY_HIGH
            totalRamMb >= RamTier.HIGH.minMb -> RamTier.HIGH
            totalRamMb >= RamTier.MEDIUM.minMb -> RamTier.MEDIUM
            else -> RamTier.LOW
        }
    }

    private fun determinePerformanceClass(totalRamMb: Long, sdkInt: Int): DevicePerformanceClass {
        return when {
            totalRamMb >= 8192 && sdkInt >= 34 -> DevicePerformanceClass.FLAGSHIP
            totalRamMb >= 6144 && sdkInt >= 31 -> DevicePerformanceClass.HIGH
            totalRamMb >= 4096 -> DevicePerformanceClass.MODERATE
            else -> DevicePerformanceClass.BASELINE
        }
    }

    private fun recommendMode(
        aiCoreAvailable: Boolean,
        ramTier: RamTier,
        availableStorageMb: Long
    ): AiExecutionMode {
        return when {
            aiCoreAvailable -> AiExecutionMode.SYSTEM_LOCAL
            ramTier >= RamTier.HIGH && availableStorageMb >= 2048 -> AiExecutionMode.CUSTOM_LOCAL
            ramTier >= RamTier.MEDIUM && availableStorageMb >= 500 -> AiExecutionMode.CUSTOM_LOCAL
            else -> AiExecutionMode.CLOUD
        }
    }
}
