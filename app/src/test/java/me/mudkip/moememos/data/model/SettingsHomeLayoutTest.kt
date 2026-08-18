package me.mudkip.moememos.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsHomeLayoutTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun missingHomeLayoutDefaultsToList() {
        val decoded = json.decodeFromString<Settings>(
            """{"usersList":[],"currentUser":"","appLockEnabled":false}"""
        )
        assertEquals(HomeLayout.LIST, decoded.homeLayout)
    }

    @Test
    fun cardsLayoutRoundTrips() {
        val encoded = json.encodeToString(Settings(homeLayout = HomeLayout.CARDS))
        val decoded = json.decodeFromString<Settings>(encoded)
        assertEquals(HomeLayout.CARDS, decoded.homeLayout)
    }
}
