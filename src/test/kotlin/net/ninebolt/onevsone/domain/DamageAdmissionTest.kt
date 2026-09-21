package net.ninebolt.onevsone.domain

import net.ninebolt.onevsone.domain.fixtures.alice
import net.ninebolt.onevsone.domain.fixtures.bob
import net.ninebolt.onevsone.domain.fixtures.carol
import net.ninebolt.onevsone.domain.fixtures.dave
import net.ninebolt.onevsone.domain.fixtures.match
import net.ninebolt.onevsone.domain.fixtures.startedMatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** エンティティ起因ダメージの受理行列。マッチ状態×帰属の組合せを検証する。 */
class DamageAdmissionTest {

    private val ingame = startedMatch()          // alice vs bob、INGAME
    private val waiting = match().join(alice).match // alice のみ、ONEMORE

    @Test
    fun `both unrestricted passes through`() {
        // 参加者同士だが待機中(制限なし)
        assertTrue(DamageAdmission.allows(alice.id, bob.id, waiting, null))
        // 部外者同士
        assertTrue(DamageAdmission.allows(carol.id, carol.id, null, null))
    }

    @Test
    fun `opponent or self allowed while ingame`() {
        assertTrue(DamageAdmission.allows(alice.id, bob.id, ingame, ingame))
        assertTrue(DamageAdmission.allows(alice.id, alice.id, ingame, ingame))
    }

    @Test
    fun `third party and mob damage denied while ingame`() {
        // 第三者のプレイヤー・MOB(加害側がマッチ外)は遮断
        assertFalse(DamageAdmission.allows(alice.id, carol.id, ingame, null))
        assertFalse(DamageAdmission.allows(alice.id, null, ingame, null))
    }

    @Test
    fun `participant cannot damage outsiders or mobs`() {
        assertFalse(DamageAdmission.allows(carol.id, alice.id, null, ingame))
        assertFalse(DamageAdmission.allows(null, alice.id, null, ingame))
    }

    @Test
    fun `everything denied while damage cancelled`() {
        // ラウンドカウントダウン中は対戦相手・自傷も遮断
        val roundCountdown = ingame.recordDefeat(bob.id, DefeatCause.FALL).match
        assertEquals(ArenaState.ROUNDCOUNTDOWN, roundCountdown.state)
        assertFalse(DamageAdmission.allows(alice.id, bob.id, roundCountdown, roundCountdown))
        assertFalse(DamageAdmission.allows(alice.id, alice.id, roundCountdown, roundCountdown))
    }

    @Test
    fun `non player victim does not bypass attacker restriction`() {
        // victim が非プレイヤー(id なし)でも加害者が参加者なら遮断される
        assertFalse(DamageAdmission.allows(null, alice.id, null, ingame))
    }

    @Test
    fun `participants of different matches cannot hurt each other`() {
        // 2 アリーナが同時 INGAME でも、相手マッチの参加者は対戦相手ではない
        val joined = ArenaMatch.new(Arena.Id.new("arena2"), 3).join(carol).match.join(dave).match
        val began = joined.beginMatch()
        check(began.outcome)
        val other = began.match
        assertEquals(ArenaState.INGAME, other.state)
        // 同一マッチ内なら許可
        assertTrue(DamageAdmission.allows(carol.id, dave.id, other, other))
        // 別マッチ間は双方向に遮断
        assertFalse(DamageAdmission.allows(alice.id, carol.id, ingame, other))
        assertFalse(DamageAdmission.allows(carol.id, alice.id, other, ingame))
    }
}
