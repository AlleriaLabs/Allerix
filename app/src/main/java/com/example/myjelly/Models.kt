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
    @SerialName("ParentId") val parentId: String? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("People") val people: List<Person>? = null,
    @SerialName("Studios") val studios: List<NameOnly>? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("BackdropImageTags") val backdropTags: List<String>? = null,
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSource>? = null,
    @SerialName("UserData") val userData: UserData? = null
)
@Serializable data class MediaSource(
    @SerialName("Id") val id: String,
    @SerialName("Container") val container: String? = null,
    @SerialName("MediaStreams") val streams: List<MediaStream> = emptyList()
)
@Serializable data class MediaStream(
    @SerialName("Index") val index: Int = 0,
    @SerialName("Type") val type: String = "",
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val title: String? = null,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("IsTextSubtitleStream") val isText: Boolean = false,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("Height") val height: Int? = null,
    @SerialName("VideoRange") val videoRange: String? = null
)
@Serializable data class UserData(
    @SerialName("PlaybackPositionTicks") val positionTicks: Long = 0,
    @SerialName("IsFavorite") val isFavorite: Boolean = false
)

@Serializable data class PublicInfo(
    @SerialName("ServerName") val name: String = "",
    @SerialName("Version") val version: String = ""
)

@Serializable data class ForgotResult(
    @SerialName("Action") val action: String = "",
    @SerialName("PinFile") val pinFile: String? = null
)
@Serializable data class PinResult(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("UsersReset") val users: List<String> = emptyList()
)

@Serializable data class PlaybackInfo(
    @SerialName("MediaSources") val sources: List<PlaybackSource> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null
)
@Serializable data class PlaybackSource(
    @SerialName("Id") val id: String = "",
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("SupportsDirectPlay") val directPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val directStream: Boolean = false
)

@Serializable data class Person(
    @SerialName("Name") val name: String = "",
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String = ""
)
@Serializable data class NameOnly(@SerialName("Name") val name: String = "")
