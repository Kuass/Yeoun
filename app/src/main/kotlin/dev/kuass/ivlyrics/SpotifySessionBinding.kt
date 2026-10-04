package dev.kuass.ivlyrics

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler

/** Main-thread session subscription. Owners decide when to start/stop and how to handle a missing session. */
internal class SpotifySessionBinding internal constructor(
    private val source: Source,
    private val onSessionChanged: (Session?) -> Unit,
    private val onMetadataChanged: (MediaMetadata?) -> Unit,
    private val onPlaybackStateChanged: (PlaybackState?) -> Unit,
) {
    internal interface Callback {
        fun metadataChanged(metadata: MediaMetadata?)
        fun playbackStateChanged(state: PlaybackState?)
        fun destroyed()
    }

    internal interface Session {
        val token: Any
        val packageName: String
        val controller: MediaController?
        fun register(callback: Callback)
        fun unregister(callback: Callback)
    }

    internal interface Source {
        fun addListener(listener: (List<Session>?) -> Unit)
        fun removeListener(listener: (List<Session>?) -> Unit)
        fun sessions(): List<Session>
    }

    constructor(
        context: Context,
        handler: Handler,
        onSessionChanged: (MediaController?) -> Unit,
        onMetadataChanged: (MediaMetadata?) -> Unit,
        onPlaybackStateChanged: (PlaybackState?) -> Unit,
    ) : this(AndroidSource(context, handler), { onSessionChanged(it?.controller) }, onMetadataChanged, onPlaybackStateChanged)

    private var listener: ((List<Session>?) -> Unit)? = null
    private var current: Session? = null
    private var callback: Callback? = null
    private var initialized = false
    val controller: MediaController? get() = current?.controller

    fun start() {
        if (listener != null) return
        val generation = Any()
        activeGeneration = generation
        val next: (List<Session>?) -> Unit = { sessions ->
            if (activeGeneration === generation) attach(sessions?.firstOrNull { it.packageName == "com.spotify.music" })
        }
        listener = next
        try {
            source.addListener(next)
            next(source.sessions())
        } catch (error: Exception) {
            runCatching { stop() }
            throw error
        }
    }

    private var activeGeneration: Any? = null

    /** A paused Activity can retain its display; service shutdown still publishes a disconnect. */
    fun stop(notifyDisconnected: Boolean = true) {
        val oldListener = listener ?: return
        listener = null
        activeGeneration = null // Invalidate queued session-list changes before removing the platform listener.
        try {
            detach(notify = notifyDisconnected)
        } finally {
            initialized = false
            source.removeListener(oldListener)
        }
    }

    private fun attach(session: Session?) {
        if (initialized && session?.token == current?.token) return
        // Replacing a session must not publish an intermediate disconnect to the owner's UI.
        detach(notify = false)
        initialized = true
        current = session
        if (session == null) {
            onSessionChanged(null)
            return
        }
        val next = object : Callback {
            private fun isCurrent() = callback === this
            override fun metadataChanged(metadata: MediaMetadata?) { if (isCurrent()) onMetadataChanged(metadata) }
            override fun playbackStateChanged(state: PlaybackState?) { if (isCurrent()) onPlaybackStateChanged(state) }
            override fun destroyed() { if (isCurrent()) attach(null) }
        }
        callback = next
        try {
            session.register(next)
        } catch (error: Exception) {
            runCatching { detach() }
            throw error
        }
        if (callback === next) onSessionChanged(session)
    }

    private fun detach(notify: Boolean = true) {
        val old = current
        val oldCallback = callback
        current = null
        callback = null // An old callback cannot update or destroy the replacement binding.
        try {
            if (old != null && oldCallback != null) old.unregister(oldCallback)
        } finally {
            if (notify && old != null) onSessionChanged(null)
        }
    }

    private class AndroidSource(context: Context, private val handler: Handler) : Source {
        private val manager = context.getSystemService(MediaSessionManager::class.java)
        private val component = ComponentName(context, SpotifyListener::class.java)
        private val listeners = mutableMapOf<(List<Session>?) -> Unit, MediaSessionManager.OnActiveSessionsChangedListener>()
        private fun wrap(controllers: List<MediaController>?) = controllers?.map { AndroidSession(it, handler) }

        override fun addListener(listener: (List<Session>?) -> Unit) {
            val platform = MediaSessionManager.OnActiveSessionsChangedListener { listener(wrap(it)) }
            listeners[listener] = platform
            manager.addOnActiveSessionsChangedListener(platform, component, handler)
        }
        override fun removeListener(listener: (List<Session>?) -> Unit) {
            listeners.remove(listener)?.let { manager.removeOnActiveSessionsChangedListener(it) }
        }
        override fun sessions(): List<Session> = wrap(manager.getActiveSessions(component)).orEmpty()
    }

    private class AndroidSession(override val controller: MediaController, private val handler: Handler) : Session {
        override val packageName: String get() = controller.packageName
        override val token: Any get() = controller.sessionToken
        private val callbacks = mutableMapOf<Callback, MediaController.Callback>()
        override fun register(callback: Callback) {
            val platform = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = callback.metadataChanged(metadata)
                override fun onPlaybackStateChanged(state: PlaybackState?) = callback.playbackStateChanged(state)
                override fun onSessionDestroyed() = callback.destroyed()
            }
            callbacks[callback] = platform
            controller.registerCallback(platform, handler)
        }
        override fun unregister(callback: Callback) {
            callbacks.remove(callback)?.let { controller.unregisterCallback(it) }
        }
    }
}
