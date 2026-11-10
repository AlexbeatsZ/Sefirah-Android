package sefirah.worker

import org.junit.Assert.*
import org.junit.Test

class ClipboardWorkerSessionTest {
    @Test fun rootRequiresAnActiveOptIn() {
        val session = ClipboardWorkerSession()
        assertNull(session.begin(session.mode))
        session.update(true, false)
        assertNull(session.begin(session.mode))
        session.update(false, true)
        assertFalse(session.mode.preferRoot)
        assertNull(session.begin(session.mode))
    }

    @Test fun disablingWhileAuthorizationIsOpenRejectsTheLateWorker() {
        val session = rootSession()
        val launch = session.begin(session.mode)!!
        session.update(false, false)
        assertTrue(launch.registered.isCompleted)
        assertFalse(session.accept(launch.token))
        session.finish(launch, false)
        assertNull(session.begin(session.mode))
    }

    @Test fun oldCompletionCannotDisableANewerLaunch() {
        val session = rootSession()
        val old = session.begin(session.mode)!!
        session.update(false, false)
        session.update(true, true)
        val next = session.begin(session.mode)!!
        session.finish(old, false)
        assertFalse(session.accept(old.token))
        assertTrue(session.accept(next.token))
        session.finish(next, true)
        assertNotNull(session.begin(session.mode))
    }

    @Test fun grantFailureFallsBackWithoutRepeatedPrompts() {
        val session = rootSession()
        val launch = session.begin(session.mode)!!
        session.finish(launch, false)
        assertNull(session.begin(session.mode))
        assertFalse(session.accept(launch.token))
        assertFalse(session.update(true, true))
        assertNull(session.begin(session.mode))
        session.update(true, false)
        session.update(true, true)
        assertNotNull(session.begin(session.mode))
    }

    @Test fun tokenIsSingleUseAndAWrongTokenDoesNotConsumeIt() {
        val session = rootSession()
        val launch = session.begin(session.mode)!!
        assertNull(session.begin(session.mode))
        assertFalse(session.accept("unrelated"))
        assertTrue(session.accept(launch.token))
        assertTrue(launch.registered.isCompleted)
        assertFalse(session.accept(launch.token))
    }

    @Test fun workerDeathAllowsOneBoundedRestart() {
        val session = rootSession()
        val first = session.begin(session.mode)!!
        assertTrue(session.accept(first.token))
        session.finish(first, true)
        val retry = session.begin(session.mode)!!
        assertTrue(session.accept(retry.token))
        session.finish(retry, true)
        assertNull(session.begin(session.mode))
    }

    private fun rootSession() = ClipboardWorkerSession().apply { update(true, true) }
}
