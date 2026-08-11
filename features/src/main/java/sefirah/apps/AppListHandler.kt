package sefirah.apps

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import sefirah.common.util.drawableToBase64Compressed
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.ApplicationInfo
import sefirah.domain.model.ApplicationList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppListHandler @Inject constructor(
    private val context: Context,
    private val networkManager: NetworkManager,
) {
    suspend fun handleRequest(deviceId: String) {
        val appList = getInstalledApps(context.packageManager)
        networkManager.sendMessageAwait(deviceId, ApplicationList(appList))
    }

    suspend fun sendInstalledApps(deviceId: String) {
        getInstalledApps(context.packageManager).forEach { app ->
            if (!networkManager.sendMessageAwait(deviceId, app)) return
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    fun getInstalledApps(packageManager: PackageManager): List<ApplicationInfo> {
        val appsList = mutableListOf<ApplicationInfo>()

        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = packageManager.queryIntentActivities(intent, 0)

        for (packageInfo in activities) {
            val appName = packageInfo.loadLabel(packageManager).toString()
            val packageName = packageInfo.activityInfo.packageName
            val appIcon = try {
                val appIconDrawable = packageInfo.loadIcon(packageManager)
                drawableToBase64Compressed(appIconDrawable, maxSize = APP_ICON_MAX_DIMENSION)
            } catch (e: Exception) {
                null
            }
            appsList.add(ApplicationInfo(packageName, appName, appIcon))
        }
        return appsList.sortedBy { it.appName }
    }

    private companion object {
        const val APP_ICON_MAX_DIMENSION = 128
    }
}
