package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.WorldPosition

/**
 * サーバー共通のロビー座標の永続化。アリーナ単位ではなく 1 つだけ存在する。
 */
interface LobbyRepository {
    fun lobby(): WorldPosition?
    fun setLobby(position: WorldPosition)
}
