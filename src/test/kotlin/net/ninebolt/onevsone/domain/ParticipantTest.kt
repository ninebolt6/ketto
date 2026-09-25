package net.ninebolt.onevsone.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class ParticipantTest {

    @Test
    fun `new rejects a blank name`() {
        assertFailsWith<IllegalArgumentException> { Participant.new(Uuid.random(), " ") }
    }
}
