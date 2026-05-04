package com.faultory.core.save

import kotlinx.serialization.json.Json

object FaultoryJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
}
