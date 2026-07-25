package sefirah.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Paths

class PrivilegedStoragePolicyTest {
    @Test
    fun acceptsOnlyPrimarySharedStorageRoot() {
        assertEquals(
            Paths.get("/storage/emulated/0"),
            PrivilegedStoragePolicy.requireAllowedRoot("/storage/emulated/0"),
        )
    }

    @Test
    fun rejectsSystemAndTraversalRoots() {
        listOf(
            "/",
            "/data",
            "/storage",
            "/storage/emulated/0/..",
            "/storage/emulated/1",
            "storage/emulated/0",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                PrivilegedStoragePolicy.requireAllowedRoot(path)
            }
        }
    }
}
