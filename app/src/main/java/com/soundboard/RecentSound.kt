package com.soundboard

data class RecentSound(
    val filename: String,
    val displayName: String,
    val color: String,
    val playedAt: Long
)
