package dev.kuass.ivlyrics

import android.content.Context

object ExtrasProgress {
    enum class State { HIDDEN, CHOICE, SETUP, RUNNING, READY, FAILED }
    data class Channel(val state: State, val done: Int = 0, val total: Int = 0)
    data class Status(val translation: Channel, val phonetic: Channel) {
        val canRetry get() = translation.state == State.FAILED || phonetic.state == State.FAILED
        fun label(ctx: Context): String = listOf(R.string.language_translation to translation, R.string.language_phonetic to phonetic)
            .joinToString(" · ") { (name, channel) ->
                val state = ctx.getString(when (channel.state) {
                    State.HIDDEN -> R.string.extras_hidden
                    State.CHOICE -> R.string.extras_choice
                    State.SETUP -> R.string.extras_setup
                    State.RUNNING -> R.string.extras_running
                    State.READY -> R.string.extras_ready
                    State.FAILED -> R.string.extras_failed
                })
                ctx.getString(R.string.extras_progress_format, ctx.getString(name), state, channel.done, channel.total)
            }
    }
    fun channel(allowed: List<Boolean>, values: List<String>?, edits: Set<Int>, configured: Boolean, finished: Boolean, needsChoice: Boolean): Channel {
        val indices = allowed.indices.filter { allowed[it] }
        val done = indices.count { it in edits || !values?.getOrNull(it).isNullOrBlank() }
        val state = when {
            indices.isEmpty() -> if (needsChoice) State.CHOICE else State.HIDDEN
            done == indices.size -> State.READY
            !configured -> State.SETUP
            finished -> State.FAILED
            else -> State.RUNNING
        }
        return Channel(state, done, indices.size)
    }
}
