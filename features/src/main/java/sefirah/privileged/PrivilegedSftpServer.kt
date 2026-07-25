package sefirah.privileged

import android.os.Build
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.subsystem.SubsystemFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyPairGenerator

internal object PrivilegedStoragePolicy {
    private const val PRIMARY_STORAGE_ROOT = "/storage/emulated/0"

    fun requireAllowedRoot(rawPath: String): Path {
        require(rawPath == PRIMARY_STORAGE_ROOT) {
            "Privileged storage is restricted to the primary shared-storage volume"
        }
        return Paths.get(rawPath)
    }
}

/**
 * SFTP server hosted inside Shizuku's shell-owned UserService.
 *
 * The virtual file system is mandatory: shell can read more than shared storage,
 * so exposing the native root here would turn a remote-storage feature into a
 * general shell filesystem endpoint.
 */
internal class PrivilegedSftpServer {
    private var server: SshServer? = null
    private var activeConfiguration: Configuration? = null

    @Synchronized
    fun start(username: String, password: String, rootPath: String): Int {
        require(username.isNotBlank()) { "Username must not be blank" }
        require(password.isNotBlank()) { "Password must not be blank" }

        val root = PrivilegedStoragePolicy.requireAllowedRoot(rootPath)
        require(root.toFile().isDirectory) { "Storage root is unavailable" }

        val requestedConfiguration = Configuration(username, password, root)
        if (requestedConfiguration == activeConfiguration && server?.isOpen == true) {
            return server!!.port
        }

        stop()

        val candidate = ServerBuilder.builder()
            .fileSystemFactory(VirtualFileSystemFactory(root))
            .build()
            .apply {
                keyPairProvider = KeyPairProvider.wrap(
                    KeyPairGenerator.getInstance("RSA").apply { initialize(HOST_KEY_BITS) }.generateKeyPair()
                )
                publickeyAuthenticator = PublickeyAuthenticator { _, _, _ -> false }
                passwordAuthenticator = PasswordAuthenticator { user, suppliedPassword, _ ->
                    user == username && suppliedPassword == password
                }
                subsystemFactories = listOf<SubsystemFactory>(
                    SftpSubsystemFactory.Builder().build()
                )
            }

        var lastError: Exception? = null
        for (port in PORT_RANGE) {
            try {
                candidate.port = port
                candidate.start()
                server = candidate
                activeConfiguration = requestedConfiguration
                return port
            } catch (error: Exception) {
                lastError = error
            }
        }

        runCatching { candidate.stop(true) }
        throw IllegalStateException("No SFTP port is available", lastError)
    }

    @Synchronized
    fun stop() {
        runCatching { server?.stop(true) }
        server = null
        activeConfiguration = null
    }

    private data class Configuration(
        val username: String,
        val password: String,
        val root: Path,
    )

    companion object {
        private const val HOST_KEY_BITS = 2048
        private val PORT_RANGE = 5151..5169

        init {
            System.setProperty(SecurityUtils.SECURITY_PROVIDER_REGISTRARS, "")
            System.setProperty(
                "org.apache.sshd.common.io.IoServiceFactoryFactory",
                "org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory"
            )
            PathUtils.setUserHomeFolderResolver {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Path.of("/")
                } else {
                    Paths.get("/")
                }
            }
        }
    }
}
