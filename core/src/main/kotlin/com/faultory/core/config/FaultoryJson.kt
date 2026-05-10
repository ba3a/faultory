package com.faultory.core.config

import kotlinx.serialization.json.Json

object FaultoryJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
}
