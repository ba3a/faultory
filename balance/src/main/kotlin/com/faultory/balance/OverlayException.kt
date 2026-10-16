package com.faultory.balance

class OverlayException(
    val code: String,
    val path: String,
    hint: String
) : RuntimeException(hint)
