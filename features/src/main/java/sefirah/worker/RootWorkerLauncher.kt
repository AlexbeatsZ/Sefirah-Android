package sefirah.worker

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.util.concurrent.TimeUnit

/** Root is used only for launching the bundled starter, which drops to shell before loading ART. */
internal object RootWorkerLauncher {
    @RequiresApi(Build.VERSION_CODES.Q)
    fun launch(context: Context, token: String): Boolean {
        val starter = WorkerStarter.starterFile(context)
        if (!starter.isFile) return false
        val command = listOf(
            starter.absolutePath,
            "--apk=${context.applicationInfo.sourceDir}",
            "--root-token=$token",
        ).joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        val process = try {
            ProcessBuilder("su", "-c", "exec $command")
                .redirectErrorStream(true)
                .redirectOutput(File("/dev/null"))
                .start()
        } catch (e: Exception) {
            Log.i(TAG, "Root launcher unavailable", e)
            return false
        }
        return try {
            process.outputStream.close()
            if (!process.waitFor(25, TimeUnit.SECONDS)) {
                Log.w(TAG, "Root launcher timed out")
                false
            } else {
                val code = process.exitValue()
                if (code != 0) Log.w(TAG, "Root launcher exited with code $code")
                code == 0
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private const val TAG = "RootWorkerLauncher"
}
