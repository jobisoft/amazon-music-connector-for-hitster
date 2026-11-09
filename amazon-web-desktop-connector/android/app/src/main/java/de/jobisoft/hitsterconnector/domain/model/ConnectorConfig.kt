package de.jobisoft.hitsterconnector.domain.model

/**
 * Where the helper app of the Firefox add-on listens, and the token it expects.
 * [serverId] and [serverName] come from the helper app. The id lets the app find
 * the same computer again when its address has changed.
 */
data class ConnectorConfig(
    val serverUrl: String,
    val token: String,
    val serverId: String? = null,
    val serverName: String? = null
)
