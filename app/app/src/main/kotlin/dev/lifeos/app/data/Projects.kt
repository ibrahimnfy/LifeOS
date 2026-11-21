package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A recent session in a project's folder. */
@Serializable
data class ProjectSession(
    val id: String,
    val title: String = "",
    @SerialName("last_activity") val lastActivity: String? = null,
)

/**
 * A project (#152): a memory project file and/or an allowed folder with its context-map file.
 * Built on the computer without a model run.
 */
@Serializable
data class Project(
    val id: String,
    val name: String = "",
    val title: String = "",
    /** `active`, `paused`, `done` or null (a folder without a project file). */
    val status: String? = null,
    val summary: String? = null,
    val state: String? = null,
    val folder: String? = null,
    @SerialName("folder_exists") val folderExists: Boolean = false,
    @SerialName("memory_file") val memoryFile: String? = null,
    @SerialName("map_file") val mapFile: String? = null,
    @SerialName("last_activity") val lastActivity: String? = null,
    @SerialName("open_task_count") val openTaskCount: Int = 0,
    @SerialName("open_tasks") val openTasks: List<Task> = emptyList(),
    val sessions: List<ProjectSession> = emptyList(),
) {
    /** The name shown: the title, else the name. */
    val label: String get() = title.ifBlank { name }

    /** "Ask about this project" needs an existing folder (the bridge lists allowed folders only). */
    val canAsk: Boolean get() = !folder.isNullOrBlank() && folderExists
}

@Serializable
data class ProjectList(val projects: List<Project> = emptyList())

object ProjectEndpoints {
    val list = Endpoint("projects", "/projects", serializer = ProjectList.serializer())

    /** Project ids are `p-<name>` or `f-<slug>`; encoded all the same. */
    fun one(id: String) = Endpoint("project-$id", "/projects/${java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")}", serializer = Project.serializer())
}
