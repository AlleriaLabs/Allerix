package com.example.myjelly

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class AuthResult(
    @SerialName("AccessToken") val token: String,
    @SerialName("User") val user: UserDto
)
@Serializable data class UserDto(@SerialName("Id") val id: String)
@Serializable data class ItemsResult(@SerialName("Items") val items: List<Item> = emptyList())

@Serializable data class Item(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("IsFolder") val isFolder: Boolean = false,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("CommunityRating") val rating: Double? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("IndexNumber") val index: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndex: Int? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("BackdropImageTags") val backdropTags: List<String>? = null,
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSource>? = null,
    @SerialName("UserData") val userData: UserData? = null
)
@Serializable data class MediaSource(
    @SerialName("Id") val id: String,
    @SerialName("MediaStreams") val streams: List<MediaStream> = emptyList()
)
@Serializable data class MediaStream(
    @SerialName("Index") val index: Int = 0,
    @SerialName("Type") val type: String = "",
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val title: String? = null,
    @SerialName("IsExternal") val isExternal: Boolean = false
)
@Serializable data class UserData(
    @SerialName("PlaybackPositionTicks") val positionTicks: Long = 0
)

@Serializable data class PublicInfo(
    @SerialName("ServerName") val name: String = "",
    @SerialName("Version") val version: String = ""
)
