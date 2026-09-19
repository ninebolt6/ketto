package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import java.util.UUID

/**
 * アリーナ定義・試合集約・UUID→アリーナ索引の共有レジストリ。
 * ArenaApplicationService と ArenaAdministrationService が共有し、
 * アリーナをまたぐ二重参加禁止は playerArena 索引が担う。
 */
class ArenaRegistry {
    val definitions = LinkedHashMap<ArenaId, ArenaDefinition>()
    val matches = LinkedHashMap<ArenaId, ArenaMatch>()
    val playerArena = mutableMapOf<UUID, ArenaId>()
}
