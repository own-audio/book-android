package audio.own.book.library

import kotlin.math.abs

/**
 * Where to start a book when this phone and the server disagree: the server's position is
 * another device's and well ahead of (or behind) what this phone last knew. Then the listener is
 * asked, because either place may be the right one (the car went on without them, or they
 * rewound on purpose here).
 */
sealed interface ResumeChoice {
    /** Start at [positionMs], no question. */
    data class Start(val positionMs: Long) : ResumeChoice

    /** Ask: this phone's position, and the other device's with its name. */
    data class Ask(val localMs: Long, val otherMs: Long, val otherDevice: String?) : ResumeChoice

    companion object {
        const val TOLERANCE_MS = 30_000L

        /**
         * [local] is what this phone last saved (null when it never played the book), [server]
         * the server's answer. Positions closer than [toleranceMs] are the same place. A server
         * without device entries (Audiobookshelf) never makes this ask.
         */
        fun decide(local: ListeningProgress?, server: ListeningProgress?, toleranceMs: Long = TOLERANCE_MS): ResumeChoice {
            if (server == null) return Start(if (local?.finished == true) 0 else local?.positionMs ?: 0)
            if (server.finished) return Start(0)
            if (local == null || local.finished || abs(local.positionMs - server.positionMs) <= toleranceMs) {
                return Start(server.positionMs)
            }
            // The server's position is another device's when one of its device entries sits
            // there and is newer than what this phone saved.
            val other = server.devices.firstOrNull {
                !it.isThisDevice && abs(it.positionMs - server.positionMs) <= toleranceMs && it.at.isAfter(local.updatedAt)
            } ?: return Start(if (server.updatedAt.isAfter(local.updatedAt)) server.positionMs else local.positionMs)
            return Ask(localMs = local.positionMs, otherMs = server.positionMs, otherDevice = other.name ?: other.kind)
        }
    }
}
