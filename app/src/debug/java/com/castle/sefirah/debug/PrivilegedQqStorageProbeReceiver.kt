package com.castle.sefirah.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import sefirah.privileged.PrivilegedBridgeManager
import java.security.Security
import java.util.UUID
import java.util.concurrent.TimeUnit

class PrivilegedQqStorageProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROBE) return
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
        PathUtils.setUserHomeFolderResolver { context.filesDir.toPath() }
        val bridgeManager = PrivilegedBridgeManager(context.applicationContext)
        bridgeManager.start()
        bridgeManager.requestPermission()

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                probe(bridgeManager)
            }.onSuccess { size ->
                Log.i(TAG, "PASS: QQ receive directory is readable; sampledFileBytes=$size; jailRoot=/")
            }.onFailure { error ->
                Log.e(TAG, "FAIL: ${error.javaClass.simpleName}: ${error.message}", error)
            }
            pendingResult.finish()
        }
    }

    private suspend fun probe(bridgeManager: PrivilegedBridgeManager): Long {
        check(bridgeManager.ensureReady(30_000)) { "Shizuku bridge did not become ready" }

        val username = "sefirah-storage-probe"
        val password = UUID.randomUUID().toString()
        val port = checkNotNull(
            bridgeManager.startSftpServer(username, password, PRIMARY_STORAGE_ROOT)
        ) { "Privileged SFTP server did not start" }

        try {
            SshClient.setUpDefaultClient().use { ssh ->
                ssh.serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
                ssh.keyExchangeFactories =
                    listOf(ClientBuilder.DH2KEX.apply(BuiltinDHFactories.ecdhp256))
                ssh.start()
                ssh.connect(username, "127.0.0.1", port)
                    .verify(10, TimeUnit.SECONDS)
                    .session
                    .use { session ->
                        session.addPasswordIdentity(password)
                        session.auth().verify(10, TimeUnit.SECONDS)

                        SftpClientFactory.instance()
                            .createSftpClient(session)
                            .use { sftp ->
                                check(sftp.stat(QQ_RECEIVE_DIRECTORY).isDirectory) {
                                    "QQ receive directory is unavailable"
                                }
                                val receivedFile = checkNotNull(
                                    findNonEmptyFile(sftp, QQ_RECEIVE_DIRECTORY, MAX_SEARCH_DEPTH)
                                ) { "QQ receive directory contains no readable non-empty file" }
                                sftp.read(receivedFile.first).use { stream ->
                                    check(stream.read() >= 0) { "Could not read QQ received file content" }
                                }
                                check(sftp.canonicalPath("/../../") == "/") {
                                    "SFTP virtual root confinement failed"
                                }
                                return receivedFile.second
                            }
                    }
            }
        } finally {
            bridgeManager.stopSftpServer()
        }
    }

    private fun findNonEmptyFile(
        sftp: SftpClient,
        directory: String,
        depth: Int,
    ): Pair<String, Long>? {
        val entries = runCatching { sftp.readDir(directory).toList() }.getOrNull() ?: return null
        for (entry in entries) {
            if (entry.filename == "." || entry.filename == "..") continue
            val path = "$directory/${entry.filename}"
            if (entry.attributes.isRegularFile && entry.attributes.size > 0) {
                return path to entry.attributes.size
            }
            if (entry.attributes.isDirectory && depth > 0) {
                findNonEmptyFile(sftp, path, depth - 1)?.let { return it }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "SefirahQqProbe"
        private const val ACTION_PROBE = "com.castle.sefirah.debug.PROBE_QQ_STORAGE"
        private const val PRIMARY_STORAGE_ROOT = "/storage/emulated/0"
        private const val QQ_RECEIVE_DIRECTORY =
            "/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv"
        private const val MAX_SEARCH_DEPTH = 4
    }
}
