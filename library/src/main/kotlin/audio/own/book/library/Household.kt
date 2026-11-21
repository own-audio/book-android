package audio.own.book.library

import java.math.BigDecimal
import java.time.Instant

// A household on the server: the people in it, and what each shares with the others. Only
// own.audio servers have one; screens ask `source as? HouseholdSource`. The own.audio side is
// built with the family screens (B8).

data class HouseholdInfo(
    val name: String,
    /** For [HouseholdSource.picture]. */
    val picture: String?,
    /** Admins invite people. */
    val isAdmin: Boolean,
    val members: List<HouseholdMember>,
)

data class HouseholdMember(
    val id: String,
    val name: String,
    val isAdmin: Boolean,
    /** Invited, not signed in yet. */
    val isPending: Boolean,
    val isMe: Boolean,
    /** For [HouseholdSource.picture]. */
    val picture: String?,
)

/** A code that lets someone join; [joinUrl] opens the server's own join page with it. */
data class HouseholdInvite(
    val id: String,
    val code: String,
    val joinUrl: String?,
    val usesLeft: Int,
    /** When it stops working; null when the server's date can't be read. */
    val expiresAt: Instant?,
)

/** What the family keeps on the server, in bytes. */
data class HouseholdStorage(val total: Long, val music: Long, val audiobooks: Long, val podcasts: Long)

/** The family's prepaid credit, where the server charges for storage (the hosted service). */
data class HouseholdCredit(
    /** In the currency's units. */
    val balance: BigDecimal,
    val currency: String,
    val monthlyCost: BigDecimal,
    /** How long the balance lasts at today's storage; null when it isn't being drawn on. */
    val daysLeft: Int?,
    val isDepleted: Boolean,
)

interface HouseholdSource : BookSource {
    suspend fun household(): HouseholdInfo

    /** The family's or a member's picture, by what [HouseholdInfo] names it. */
    suspend fun picture(name: String): ByteArray
    suspend fun storage(): HouseholdStorage

    /** Null where the server doesn't charge. */
    suspend fun credit(): HouseholdCredit?
    suspend fun invites(): List<HouseholdInvite>
    suspend fun createInvite(): HouseholdInvite
    suspend fun deleteInvite(id: String)

    /** Moves the account into the household the code belongs to. */
    suspend fun joinHousehold(code: String): HouseholdInfo

    /** One of the listener's own books: shared with the household, or kept to themselves. */
    suspend fun setShared(bookId: String, shared: Boolean)
}
