package net.ninebolt.ketto.application.port

interface WorldPort {
    fun isLoaded(worldName: String): Boolean
}
