package me.mudkip.moememos.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class HomeLayout {
    LIST,
    CARDS,
}

@Serializable
data class Settings(
    val usersList: List<UserData> = emptyList(),
    val currentUser: String = "",
    val appLockEnabled: Boolean = false,
    val homeLayout: HomeLayout = HomeLayout.LIST,
)
