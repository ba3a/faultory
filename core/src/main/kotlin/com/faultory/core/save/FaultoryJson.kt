package com.faultory.core.save

import kotlinx.serialization.json.Json

object FaultoryJson {
    val instance: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }
}
