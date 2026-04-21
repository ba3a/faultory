package com.faultory.editor.repository

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

object EditorJson {
    @OptIn(ExperimentalSerializationApi::class)
    val instance: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}
