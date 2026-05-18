package com.soundboard

import org.json.JSONObject

object FocusTimeManager {

    enum class State {
        DEFAULT,
        FOCUS_VOTING
    }

    enum class PlayPolicy {
        NORMAL,
        BLOCKED,
        UNLIMITED,
        FOCUS_LIMITED
    }

    data class VoteStatus(
        val state: State,
        val votesFor: Int,
        val votesAgainst: Int,
        val remainingSeconds: Long,
        val playPolicy: PlayPolicy,
        val focusPlaysUsed: Int,
        val focusPlaysLimit: Int
    )

    data class PlayCheckResult(
        val allowed: Boolean,
        val reason: String,
        val policy: PlayPolicy
    )

    private const val VOTE_WINDOW_MS = 10 * 60 * 1000L
    private const val FOCUS_PLAY_LIMIT = 5

    private val lock = Any()
    private val listeners = mutableListOf<() -> Unit>()

    private val votes = HashMap<String, Boolean>()
    private var votingStartedAt: Long = 0L
    private val focusPlaysPerIp = HashMap<String, Int>()

    fun getState(): State {
        synchronized(lock) {
            if (isExpired()) resetInternal()
            return if (votes.isEmpty()) State.DEFAULT else State.FOCUS_VOTING
        }
    }

    fun vote(ip: String, voteFor: Boolean): VoteStatus {
        synchronized(lock) {
            if (isExpired()) resetInternal()

            // Can't vote against in DEFAULT state
            if (votes.isEmpty() && !voteFor) {
                return buildStatus()
            }

            // First "vote for" starts the timer
            if (votes.isEmpty() && voteFor) {
                votingStartedAt = System.currentTimeMillis()
                focusPlaysPerIp.clear()
            }

            votes[ip] = voteFor
        }
        notifyListeners()
        return getStatus()
    }

    fun checkPlay(clientIp: String): PlayCheckResult {
        synchronized(lock) {
            if (isExpired()) resetInternal()

            if (votes.isEmpty()) {
                return PlayCheckResult(true, "No focus vote active", PlayPolicy.NORMAL)
            }

            return when (currentPlayPolicy()) {
                PlayPolicy.BLOCKED -> PlayCheckResult(
                    false, "Focus time active - sounds are blocked", PlayPolicy.BLOCKED
                )
                PlayPolicy.UNLIMITED -> PlayCheckResult(
                    true, "Majority voted against focus - no limits", PlayPolicy.UNLIMITED
                )
                PlayPolicy.FOCUS_LIMITED -> {
                    val used = focusPlaysPerIp.getOrDefault(clientIp, 0)
                    if (used >= FOCUS_PLAY_LIMIT) {
                        PlayCheckResult(
                            false,
                            "Tie vote - limit of $FOCUS_PLAY_LIMIT sounds reached",
                            PlayPolicy.FOCUS_LIMITED
                        )
                    } else {
                        focusPlaysPerIp[clientIp] = used + 1
                        PlayCheckResult(
                            true,
                            "Tie vote - ${used + 1}/$FOCUS_PLAY_LIMIT sounds used",
                            PlayPolicy.FOCUS_LIMITED
                        )
                    }
                }
                PlayPolicy.NORMAL -> PlayCheckResult(true, "Normal", PlayPolicy.NORMAL)
            }
        }
    }

    fun getStatus(): VoteStatus {
        synchronized(lock) {
            if (isExpired()) resetInternal()
            return buildStatus()
        }
    }

    fun toJson(): String {
        val status = getStatus()
        return JSONObject().apply {
            put("state", status.state.name)
            put("votesFor", status.votesFor)
            put("votesAgainst", status.votesAgainst)
            put("remainingSeconds", status.remainingSeconds)
            put("playPolicy", status.playPolicy.name)
            put("focusPlaysUsed", status.focusPlaysUsed)
            put("focusPlaysLimit", status.focusPlaysLimit)
        }.toString()
    }

    fun addChangeListener(listener: () -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun removeChangeListener(listener: () -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }

    private fun isExpired(): Boolean {
        if (votingStartedAt == 0L) return false
        return System.currentTimeMillis() - votingStartedAt >= VOTE_WINDOW_MS
    }

    private fun resetInternal() {
        votes.clear()
        votingStartedAt = 0L
        focusPlaysPerIp.clear()
    }

    private fun currentPlayPolicy(): PlayPolicy {
        if (votes.isEmpty()) return PlayPolicy.NORMAL
        val forCount = votes.values.count { it }
        val againstCount = votes.values.count { !it }
        return when {
            forCount > againstCount -> PlayPolicy.BLOCKED
            againstCount > forCount -> PlayPolicy.UNLIMITED
            else -> PlayPolicy.FOCUS_LIMITED
        }
    }

    private fun buildStatus(): VoteStatus {
        val forCount = votes.values.count { it }
        val againstCount = votes.values.count { !it }
        val remaining = if (votingStartedAt > 0L) {
            val elapsed = System.currentTimeMillis() - votingStartedAt
            ((VOTE_WINDOW_MS - elapsed) / 1000).coerceAtLeast(0)
        } else {
            0L
        }
        return VoteStatus(
            state = if (votes.isEmpty()) State.DEFAULT else State.FOCUS_VOTING,
            votesFor = forCount,
            votesAgainst = againstCount,
            remainingSeconds = remaining,
            playPolicy = currentPlayPolicy(),
            focusPlaysUsed = focusPlaysPerIp.values.sum(),
            focusPlaysLimit = FOCUS_PLAY_LIMIT
        )
    }

    private fun notifyListeners() {
        val copy: List<() -> Unit>
        synchronized(lock) { copy = listeners.toList() }
        copy.forEach { it() }
    }
}
