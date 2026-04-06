package com.faultory.core.content

import kotlinx.serialization.Serializable

@Serializable
enum class FaultyProductStrategy {
    DESTROY,
    PUT_ON_FREE_TILE,
    HAND_TO_PRODUCER
}
