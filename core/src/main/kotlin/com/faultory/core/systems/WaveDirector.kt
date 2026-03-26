package com.faultory.core.systems

class WaveDirector(
    private val firstWaveDelaySeconds: Float = 2f
) {
    var elapsedSeconds: Float = 0f
        private set

    val isFirstWaveQueued: Boolean
        get() = elapsedSeconds >= firstWaveDelaySeconds

    fun update(deltaSeconds: Float) {
        elapsedSeconds += deltaSeconds
    }
}
