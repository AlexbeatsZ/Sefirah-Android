package sefirah

import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.model.DevicePreferences

class FeatureConcurrencyTest {
    private val manager = Proxy.newProxyInstance(
        DeviceManager::class.java.classLoader, arrayOf(DeviceManager::class.java),
    ) { _, method, _ -> error("Unexpected DeviceManager call: ${method.name}") } as DeviceManager

    @Test
    fun `notification iteration survives a device disconnect`() = runBlocking {
        val feature = object : Feature(manager) {
            override fun isPrefEnabled(prefs: DevicePreferences) = true
            fun recipients() = enabledDevices.iterator()
        }
        feature.enable("phone")
        feature.enable("desktop")
        val iterator = feature.recipients()
        assertEquals("phone", iterator.next())
        feature.disable("desktop")
        // A callback that started before disconnect sees its consistent original snapshot.
        assertEquals("desktop", iterator.next())
        assertEquals(setOf("phone"), feature.activeDeviceIds)
    }

    @Test
    fun `captured recipients do not change during asynchronous encoding`() = runBlocking {
        val feature = object : Feature(manager) {
            override fun isPrefEnabled(prefs: DevicePreferences) = true
        }
        feature.enable("phone")
        val snapshot = feature.activeDeviceIds
        feature.disable("phone")
        feature.enable("other")
        assertEquals(setOf("phone"), snapshot)
    }

    @Test
    fun `concurrent enables and disables start and stop shared resources once`() = runBlocking {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val feature = object : BoundFeature(manager) {
            override fun isPrefEnabled(prefs: DevicePreferences) = true
            override suspend fun onStart() { delay(10); starts.incrementAndGet() }
            override suspend fun onStop() { delay(10); stops.incrementAndGet() }
        }
        (1..32).map { async(Dispatchers.Default) { feature.enable("phone") } }.awaitAll()
        assertEquals(1, starts.get())
        (1..32).map { async(Dispatchers.Default) { feature.disable("phone") } }.awaitAll()
        assertEquals(1, stops.get())
        assertEquals(emptySet<String>(), feature.activeDeviceIds)
    }
}
