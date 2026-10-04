package dev.kuass.ivlyrics

import android.media.session.MediaController
import org.junit.Assert.*
import org.junit.Test

class SpotifySessionBindingTest {
    private class Session(override val token: Any, override val packageName: String = "com.spotify.music") : SpotifySessionBinding.Session {
        override val controller: MediaController? = null
        val registered = mutableListOf<SpotifySessionBinding.Callback>()
        val unregistered = mutableListOf<SpotifySessionBinding.Callback>()
        var registerFailure: Exception? = null
        var onUnregister: (() -> Unit)? = null
        override fun register(callback: SpotifySessionBinding.Callback) {
            registered += callback
            registerFailure?.let { throw it }
        }
        override fun unregister(callback: SpotifySessionBinding.Callback) {
            unregistered += callback
            onUnregister?.invoke()
        }
    }

    private class Source : SpotifySessionBinding.Source {
        var available = emptyList<SpotifySessionBinding.Session>()
        val added = mutableListOf<(List<SpotifySessionBinding.Session>?) -> Unit>()
        val removed = mutableListOf<(List<SpotifySessionBinding.Session>?) -> Unit>()
        var addFailure: Exception? = null
        var readFailure: Exception? = null
        var removeFailure: Exception? = null
        override fun addListener(listener: (List<SpotifySessionBinding.Session>?) -> Unit) {
            added += listener
            addFailure?.let { throw it }
        }
        override fun removeListener(listener: (List<SpotifySessionBinding.Session>?) -> Unit) {
            removed += listener
            removeFailure?.let { throw it }
        }
        override fun sessions(): List<SpotifySessionBinding.Session> {
            readFailure?.let { throw it }
            return available
        }
        fun emit(vararg sessions: Session) = added.last()(sessions.toList())
    }

    private class Fixture {
        val source = Source()
        val changed = mutableListOf<SpotifySessionBinding.Session?>()
        var metadataUpdates = 0
        var playbackUpdates = 0
        val binding = SpotifySessionBinding(source, changed::add, { metadataUpdates++ }, { playbackUpdates++ })
        fun start(vararg sessions: Session) { source.available = sessions.toList(); binding.start() }
    }

    @Test fun `start subscribes once and selects the first session`() {
        val f = Fixture(); val first = Session("first"); val second = Session("second")
        f.start(Session("other", "another.player"), first, second)
        f.binding.start()
        assertEquals(listOf(first), f.changed)
        assertEquals(1, first.registered.size)
        assertTrue(second.registered.isEmpty())
        assertEquals(1, f.source.added.size)
    }

    @Test fun `same token retains the original callback and controller owner`() {
        val f = Fixture(); val first = Session(listOf("same")); val duplicate = Session(listOf("same"))
        f.start(first)
        f.source.emit(duplicate)
        assertEquals(listOf(first), f.changed)
        assertTrue(first.unregistered.isEmpty())
        assertTrue(duplicate.registered.isEmpty())
    }

    @Test fun `replacement unregisters the exact callback without an intermediate disconnect`() {
        val f = Fixture(); val first = Session("first"); val second = Session("second")
        f.start(first)
        f.source.emit(second)
        assertSame(first.registered.single(), first.unregistered.single())
        assertEquals(listOf(first, second), f.changed)
        assertEquals(1, second.registered.size)
    }

    @Test fun `only the current session can send metadata playback and destruction events`() {
        val f = Fixture(); val first = Session("first"); val second = Session("second")
        f.start(first)
        val old = first.registered.single()
        f.source.emit(second)
        old.metadataChanged(null); old.playbackStateChanged(null); old.destroyed()
        assertEquals(0, f.metadataUpdates)
        assertEquals(0, f.playbackUpdates)
        assertEquals(listOf(first, second), f.changed)
        val current = second.registered.single()
        current.metadataChanged(null); current.playbackStateChanged(null)
        assertEquals(1, f.metadataUpdates)
        assertEquals(1, f.playbackUpdates)
        current.destroyed(); current.destroyed()
        assertEquals(listOf(first, second, null), f.changed)
        assertSame(current, second.unregistered.single())
    }

    @Test fun `missing and null session lists disconnect only once`() {
        val f = Fixture(); val session = Session("spotify")
        f.start(session)
        f.source.emit()
        f.source.added.single()(null)
        assertEquals(listOf(session, null), f.changed)
        assertEquals(1, session.unregistered.size)
    }

    @Test fun `starting without a session reports missing playback to the owner`() {
        val f = Fixture()
        f.start()
        f.source.emit()
        assertEquals(listOf<SpotifySessionBinding.Session?>(null), f.changed)
        f.binding.stop()
        f.start()
        assertEquals(listOf<SpotifySessionBinding.Session?>(null, null), f.changed)
    }

    @Test fun `stop removes exact registrations and rejects late callbacks`() {
        val f = Fixture(); val session = Session("spotify")
        f.start(session)
        val callback = session.registered.single()
        f.binding.stop(); f.binding.stop()
        callback.metadataChanged(null); callback.playbackStateChanged(null); callback.destroyed()
        f.source.emit(Session("late"))
        assertSame(f.source.added.single(), f.source.removed.single())
        assertSame(callback, session.unregistered.single())
        assertEquals(listOf(session, null), f.changed)
        assertEquals(0, f.metadataUpdates)
        assertEquals(0, f.playbackUpdates)
    }

    @Test fun `restart rejects prior listeners and callbacks even for the same session token`() {
        val f = Fixture(); val session = Session("spotify")
        f.start(session)
        val oldListener = f.source.added.single(); val oldCallback = session.registered.single()
        f.binding.stop()
        f.start(session)
        oldListener(emptyList())
        oldCallback.metadataChanged(null); oldCallback.playbackStateChanged(null); oldCallback.destroyed()
        assertEquals(listOf(session, null, session), f.changed)
        assertEquals(2, session.registered.size)
        assertNotSame(session.registered[0], session.registered[1])
        assertEquals(1, session.unregistered.size)
        assertEquals(0, f.metadataUpdates)
        assertEquals(0, f.playbackUpdates)
    }

    @Test fun `callbacks are already invalid while unregistering`() {
        val f = Fixture(); val session = Session("spotify")
        f.start(session)
        val callback = session.registered.single()
        session.onUnregister = { callback.metadataChanged(null); callback.playbackStateChanged(null); callback.destroyed() }
        f.binding.stop()
        assertEquals(listOf(session, null), f.changed)
        assertEquals(0, f.metadataUpdates)
        assertEquals(0, f.playbackUpdates)
    }

    @Test fun `silent pause retains owner display but resume still reports a missing session`() {
        val f = Fixture(); val session = Session("spotify")
        f.start(session)
        val callback = session.registered.single()
        f.binding.stop(notifyDisconnected = false)
        assertEquals(listOf(session), f.changed)
        callback.metadataChanged(null); callback.playbackStateChanged(null); callback.destroyed()
        assertEquals(0, f.metadataUpdates)
        assertEquals(0, f.playbackUpdates)
        assertEquals(1, session.unregistered.size)
        assertSame(f.source.added.single(), f.source.removed.single())
        f.start(session)
        assertEquals(listOf(session, session), f.changed)
        assertEquals(2, session.registered.size)
        assertNotSame(callback, session.registered.last())
        f.binding.stop(notifyDisconnected = false)
        f.start()
        assertEquals(listOf(session, session, null), f.changed)
    }

    @Test fun `failed session query releases the listener and permits retry`() {
        val f = Fixture(); val failure = SecurityException("access revoked")
        f.source.readFailure = failure
        assertSame(failure, runCatching { f.start() }.exceptionOrNull())
        assertSame(f.source.added.single(), f.source.removed.single())
        f.source.readFailure = null
        val session = Session("retry"); f.start(session)
        assertEquals(listOf(session), f.changed)
        assertEquals(2, f.source.added.size)
    }

    @Test fun `partially added listener is removed if registration fails`() {
        val f = Fixture(); val failure = SecurityException("access revoked")
        f.source.addFailure = failure
        assertSame(failure, runCatching { f.start() }.exceptionOrNull())
        assertSame(f.source.added.single(), f.source.removed.single())
        f.source.emit(Session("late"))
        assertTrue(f.changed.isEmpty())
    }

    @Test fun `failed callback registration is released and cannot send later events`() {
        val f = Fixture(); val session = Session("spotify")
        val failure = IllegalStateException("registration failed"); session.registerFailure = failure
        assertSame(failure, runCatching { f.start(session) }.exceptionOrNull())
        val callback = session.registered.single()
        assertSame(callback, session.unregistered.single())
        callback.metadataChanged(null); callback.playbackStateChanged(null); callback.destroyed()
        assertEquals(listOf<SpotifySessionBinding.Session?>(null), f.changed)
        assertEquals(0, f.metadataUpdates)
        assertEquals(0, f.playbackUpdates)
        assertEquals(1, f.source.removed.size)
    }

    @Test fun `failed replacement registration leaves no callback that can revive the old session`() {
        val f = Fixture(); val first = Session("first"); val second = Session("second")
        f.start(first)
        val failure = IllegalStateException("registration failed"); second.registerFailure = failure
        assertSame(failure, runCatching { f.source.emit(second) }.exceptionOrNull())
        first.registered.single().destroyed()
        second.registered.single().metadataChanged(null)
        assertEquals(listOf(first, null), f.changed)
        assertEquals(0, f.metadataUpdates)
        assertSame(second.registered.single(), second.unregistered.single())
        second.registerFailure = null
        f.source.emit(second)
        assertEquals(listOf(first, null, second), f.changed)
    }

    @Test fun `callback removal failure still releases the listener and permits restart`() {
        val f = Fixture(); val session = Session("spotify")
        f.start(session)
        val failure = IllegalStateException("callback removal failed")
        session.onUnregister = { throw failure }
        assertSame(failure, runCatching { f.binding.stop() }.exceptionOrNull())
        assertSame(f.source.added.single(), f.source.removed.single())
        session.registered.single().metadataChanged(null)
        session.registered.single().destroyed()
        assertEquals(0, f.metadataUpdates)
        assertEquals(listOf(session, null), f.changed)
        session.onUnregister = null
        f.start(session)
        assertEquals(listOf(session, null, session), f.changed)
        assertEquals(2, session.registered.size)
    }

    @Test fun `listener removal failure does not leave an active session or accept stale events`() {
        val f = Fixture(); val session = Session("spotify")
        f.start(session)
        val failure = IllegalStateException("listener removal failed"); f.source.removeFailure = failure
        assertSame(failure, runCatching { f.binding.stop() }.exceptionOrNull())
        f.source.emit(Session("late"))
        session.registered.single().destroyed()
        assertEquals(listOf(session, null), f.changed)
        assertEquals(1, session.unregistered.size)
        f.source.removeFailure = null
        f.start(session)
        assertEquals(listOf(session, null, session), f.changed)
    }
}
