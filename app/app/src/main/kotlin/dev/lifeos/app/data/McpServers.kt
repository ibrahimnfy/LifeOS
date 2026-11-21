package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- a session's MCP servers (#153, #162; docs/bridge.md)

/**
 * One MCP server a session can be given, as the computer shows it: one the session's folder
 * declares (its `.mcp.json`, [scope] "folder") or one of the owner's personal (user-scope)
 * servers ([scope] "personal", #162). What would start ([command] with [args], or the address
 * [url]) and only the *names* of its environment variables and headers, never their values.
 * Nothing is started by listing it.
 *
 * **An approved server runs outside the sandbox** ([outsideSandbox]): it is an ordinary
 * program with the network and the whole home folder. The approval is for exactly this
 * definition ([hash]); after any change it is [State.CHANGED] and no longer used. A folder's
 * server is approved for that folder; a personal one once, for every folder.
 */
@Serializable
data class McpServer(
    val name: String = "",
    /** "folder" or "personal"; one name can appear once per scope (two servers, two approvals). */
    val scope: String = FOLDER,
    val type: String = "",
    val command: String? = null,
    val args: List<String> = emptyList(),
    val url: String? = null,
    @SerialName("env_names") val envNames: List<String> = emptyList(),
    @SerialName("header_names") val headerNames: List<String> = emptyList(),
    val hash: String = "",
    @SerialName("outside_sandbox") val outsideSandbox: Boolean = true,
    val state: String = "",
    @SerialName("approved_at") val approvedAt: String? = null,
) {
    /**
     * [UNSUPPORTED]: a personal server whose definition holds more than a plain program or
     * address (for example a helper command for headers): it can never be approved.
     */
    enum class State { NOT_APPROVED, APPROVED, CHANGED, UNSUPPORTED }

    /** Anything the app does not know counts as not approved. */
    val approval: State
        get() = when (state) {
            "approved" -> State.APPROVED
            "changed" -> State.CHANGED
            "unsupported" -> State.UNSUPPORTED
            else -> State.NOT_APPROVED
        }

    /** One of the owner's personal servers: its approval covers every folder. */
    val personal: Boolean get() = scope == PERSONAL

    /** The scope as a request names it; anything the app does not know is taken as the folder's. */
    val scopeParam: String get() = if (personal) PERSONAL else FOLDER

    /** It can be reviewed for approval (not when approved already, nor when it never can be). */
    val approvable: Boolean get() = approval != State.APPROVED && approval != State.UNSUPPORTED

    /** It has an approval that can be taken back. */
    val revocable: Boolean get() = approval == State.APPROVED || approval == State.CHANGED

    /** The program with its arguments, as one line; empty for a server that is an address. */
    val commandLine: String get() = (listOfNotNull(command?.takeIf { it.isNotBlank() }) + args).joinToString(" ")

    /** Its name can be put into a request path (the computer accepts letters, digits, "-" and "_"). */
    val usable: Boolean get() = Chat.validId(name)

    companion object {
        const val FOLDER = "folder"
        const val PERSONAL = "personal"
    }
}

/** `GET /sessions/{id}/mcp-servers`. */
@Serializable
data class McpServerList(val servers: List<McpServer> = emptyList())

/** `DELETE /sessions/{id}/mcp-servers/{name}`. */
@Serializable
data class McpRevoked(val revoked: Boolean = false)

/** An MCP server a capable answer used; it ran outside the sandbox. [scope] as in [McpServer]. */
@Serializable
data class UsedServer(val name: String = "", val scope: String = McpServer.FOLDER)

/** Requests about a session's MCP servers that go through the session's sheet, not a conversation. */
object McpApprovals {
    /**
     * What became of the owner's yes or no: [status] when the card is settled, [refusal] when
     * the computer did not act on a yes and the card still waits there.
     */
    data class Answer(val status: Interaction.Status? = null, val refusal: Interaction.Refusal? = null)

    /**
     * The card that asks for exactly [server]. The computer keeps one card per definition
     * (#157): asked for again while it waits, the same card comes back and no phone is woken.
     */
    suspend fun card(api: Api, sessionId: String, server: McpServer): InteractionData = api.requestMcpApproval(sessionId, server.name, server.scopeParam)

    /** Take [server]'s approval back (of its own scope only); false when it had none. */
    suspend fun revoke(api: Api, sessionId: String, server: McpServer): Boolean = api.revokeMcp(sessionId, server.name, server.scopeParam)

    /**
     * The owner's yes or no to [cardId] ([Interaction.settled], [Interaction.gone],
     * [Interaction.refusal]). Throws when the answer did not get through: it can then be
     * given again.
     */
    suspend fun answer(api: Api, sessionId: String, cardId: String, approve: Boolean): Answer = try {
        Answer(status = Interaction.settled(approve, api.answerInteraction(sessionId, cardId, Interaction.approvalBody(approve))))
    } catch (error: ApiException) {
        if (Interaction.gone(error)) Answer(status = Interaction.Status.GONE) else Answer(refusal = Interaction.refusal(error) ?: throw error)
    }
}
