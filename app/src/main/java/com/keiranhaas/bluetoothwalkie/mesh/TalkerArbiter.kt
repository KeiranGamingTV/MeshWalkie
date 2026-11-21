package com.keiranhaas.bluetoothwalkie.mesh

/**
 * Result of asking for permission to transmit.
 */
sealed interface TalkRequestResult {

    /** The local device now holds the floor. */
    data object Granted : TalkRequestResult

    /** Somebody else is already talking on this channel. */
    data class Busy(
        val callsign: String
    ) : TalkRequestResult

    /** The mesh is not running (or no room key is configured). */
    data object NotRunning : TalkRequestResult
}

/**
 * Decentralised floor control for one channel.
 *
 * There is no server and no shared clock, so every device runs this same
 * deterministic logic on the packets it sees. Two devices that see the same
 * two overlapping transmissions always pick the same winner.
 *
 * Rules, in order:
 *  1. A talker that is already "established" (it has sent at least
 *     [ESTABLISHED_SEQUENCE] voice frames, about one second) beats one that is
 *     not. This stops a late joiner from stealing the floor mid-sentence.
 *  2. Otherwise the numerically higher session id wins. Session ids are random
 *     per transmission, so over time this is fair.
 *
 * A remote talker that stays silent for [STALE_TALKER_MS] is dropped, which
 * covers a lost VOICE_END packet.
 */
class TalkerArbiter(
    private val clockMs: () -> Long = {
        System.nanoTime() / 1_000_000L
    }
) {

    data class RemoteTalker(
        val sessionId: Long,
        val originId: Long,
        val callsign: String,
        val highestSequence: Int,
        val lastActivityMs: Long
    )

    /**
     * A change of who is being played.
     *
     * [ended] stopped (or lost the floor), [started] took it.
     * [startSequence] is the first sequence number the audio buffer should
     * expect from [started].
     */
    data class FloorUpdate(
        val ended: RemoteTalker? = null,
        val started: RemoteTalker? = null,
        val startSequence: Int = 1
    )

    data class PacketDecision(
        /** True if this packet's audio should be played. */
        val deliver: Boolean,
        val update: FloorUpdate = FloorUpdate(),
        /** Non-null if the local transmission lost arbitration to this talker. */
        val preemptedLocal: RemoteTalker? = null
    )

    private val lock = Any()

    private val remote = LinkedHashMap<Long, RemoteTalker>()

    private var floorSessionId = 0L
    private var localSessionId = 0L
    private var localSequence = 0

    /** Callsign and info of whoever currently holds the floor, if anyone. */
    fun currentTalker(): RemoteTalker? =
        synchronized(lock) {
            val now = clockMs()
            remote[floorSessionId]?.takeIf { isLive(it, now) }
        }

    /**
     * Tries to take the floor for a local transmission.
     * Returns [TalkRequestResult.Granted] or [TalkRequestResult.Busy].
     */
    fun tryAcquireLocal(sessionId: Long): TalkRequestResult =
        synchronized(lock) {

            val now = clockMs()

            dropStale(now)

            val busy = bestLive(now)

            if (busy != null) {
                return@synchronized TalkRequestResult.Busy(
                    busy.callsign
                )
            }

            floorSessionId = 0L
            localSessionId = sessionId
            localSequence = 0

            TalkRequestResult.Granted
        }

    fun noteLocalSequence(sessionId: Long, sequence: Int) {
        synchronized(lock) {
            if (localSessionId == sessionId) {
                localSequence = sequence
            }
        }
    }

    fun releaseLocal(sessionId: Long) {
        synchronized(lock) {
            if (localSessionId == sessionId) {
                localSessionId = 0L
                localSequence = 0
            }
        }
    }

    /**
     * Call for every VOICE_START / VOICE_DATA packet from another device.
     * [callsign] may be null for VOICE_DATA when the START was never seen.
     */
    fun onRemotePacket(
        sessionId: Long,
        originId: Long,
        callsign: String?,
        sequence: Int
    ): PacketDecision =
        synchronized(lock) {

            val now = clockMs()

            val previous = remote[sessionId]

            val talker = RemoteTalker(
                sessionId = sessionId,
                originId = originId,
                callsign = callsign
                    ?: previous?.callsign
                    ?: UNKNOWN_CALLSIGN,
                highestSequence = maxOf(
                    sequence,
                    previous?.highestSequence ?: 0
                ),
                lastActivityMs = now
            )

            remote[sessionId] = talker

            if (remote.size > MAX_TRACKED_TALKERS) {
                dropStale(now)
            }

            var preempted: RemoteTalker? = null

            if (localSessionId != 0L) {

                if (
                    beats(
                        talker.sessionId,
                        talker.highestSequence,
                        localSessionId,
                        localSequence
                    )
                ) {
                    localSessionId = 0L
                    localSequence = 0
                    preempted = talker
                } else {
                    // We hold the floor. This device is the intruder.
                    return@synchronized PacketDecision(
                        deliver = false
                    )
                }
            }

            var update = FloorUpdate()

            if (floorSessionId != sessionId) {

                val oldFloor = remote[floorSessionId]

                val oldStale =
                    oldFloor != null && !isLive(oldFloor, now)

                val takesFloor =
                    oldFloor == null ||
                        oldStale ||
                        beats(
                            talker.sessionId,
                            talker.highestSequence,
                            oldFloor.sessionId,
                            oldFloor.highestSequence
                        )

                if (takesFloor) {

                    if (oldFloor != null && oldStale) {
                        remote.remove(oldFloor.sessionId)
                    }

                    floorSessionId = sessionId

                    update = FloorUpdate(
                        ended = oldFloor,
                        started = talker,
                        startSequence = sequence.coerceAtLeast(1)
                    )
                }
            }

            PacketDecision(
                deliver = floorSessionId == sessionId,
                update = update,
                preemptedLocal = preempted
            )
        }

    /**
     * Call for VOICE_END from another device.
     * Returns who stopped and, if someone else is still talking, who is
     * promoted to hold the floor.
     */
    fun onRemoteEnd(sessionId: Long): FloorUpdate =
        synchronized(lock) {

            val ended = remote.remove(sessionId)
                ?: return@synchronized FloorUpdate()

            if (floorSessionId != sessionId) {
                return@synchronized FloorUpdate()
            }

            promoteNext(ended, clockMs())
        }

    /**
     * Call periodically. Drops talkers that went silent without a VOICE_END.
     */
    fun expireStale(): FloorUpdate =
        synchronized(lock) {

            val now = clockMs()

            val floor = remote[floorSessionId]

            dropStale(now)

            if (floor != null && remote[floorSessionId] == null) {
                promoteNext(floor, now)
            } else {
                FloorUpdate()
            }
        }

    fun clear() {
        synchronized(lock) {
            remote.clear()
            floorSessionId = 0L
            localSessionId = 0L
            localSequence = 0
        }
    }

    fun clearRemote() {
        synchronized(lock) {
            remote.clear()
            floorSessionId = 0L
        }
    }

    private fun promoteNext(
        ended: RemoteTalker,
        now: Long
    ): FloorUpdate {

        val next = bestLive(now)

        floorSessionId = next?.sessionId ?: 0L

        return FloorUpdate(
            ended = ended,
            started = next,
            startSequence =
                ((next?.highestSequence ?: 0) + 1).coerceAtLeast(1)
        )
    }

    private fun dropStale(now: Long) {
        val iterator = remote.values.iterator()

        while (iterator.hasNext()) {
            if (!isLive(iterator.next(), now)) {
                iterator.remove()
            }
        }
    }

    private fun bestLive(now: Long): RemoteTalker? {

        var best: RemoteTalker? = null

        for (talker in remote.values) {

            if (!isLive(talker, now)) {
                continue
            }

            if (
                best == null ||
                beats(
                    talker.sessionId,
                    talker.highestSequence,
                    best.sessionId,
                    best.highestSequence
                )
            ) {
                best = talker
            }
        }

        return best
    }

    private fun isLive(
        talker: RemoteTalker,
        now: Long
    ): Boolean =
        now - talker.lastActivityMs <= STALE_TALKER_MS

    private fun beats(
        aSession: Long,
        aSequence: Int,
        bSession: Long,
        bSequence: Int
    ): Boolean {

        val aEstablished = aSequence >= ESTABLISHED_SEQUENCE
        val bEstablished = bSequence >= ESTABLISHED_SEQUENCE

        if (aEstablished != bEstablished) {
            return aEstablished
        }

        return aSession > bSession
    }

    companion object {

        /** 25 frames x 40 ms = about one second of speech. */
        const val ESTABLISHED_SEQUENCE = 25

        /** Matches the audio engine's receive idle timeout. */
        const val STALE_TALKER_MS = 2_000L

        private const val MAX_TRACKED_TALKERS = 16

        private const val UNKNOWN_CALLSIGN = "Unknown"
    }
}