package dev.lifeos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsTest {
    @Test
    fun `the bridge's projects are read`() {
        val list = BridgeJson.decodeFromString(
            ProjectList.serializer(),
            """{"projects": [{"id": "p-alpha", "name": "alpha", "title": "Project Alpha", "status": "active", "summary": "s",
                "state": "st", "folder": "/x/alpha", "folder_exists": true, "memory_file": "projects/alpha.md", "map_file": "map/alpha.md",
                "last_activity": "2026-01-01T08:00:00+00:00", "open_task_count": 3,
                "open_tasks": [{"id": "t-1", "title": "One", "project": "alpha"}],
                "sessions": [{"id": "s-1", "title": "Plan", "last_activity": "2026-01-01T08:00:00+00:00"}]},
               {"id": "f-site", "name": "site", "title": "", "status": null, "summary": null, "state": null,
                "folder": null, "folder_exists": false, "memory_file": null, "map_file": null,
                "last_activity": null, "open_task_count": 0, "open_tasks": [], "sessions": []}]}""",
        )
        val (alpha, site) = list.projects
        assertEquals("Project Alpha", alpha.label)
        assertEquals(3, alpha.openTaskCount)
        assertEquals("One", alpha.openTasks.single().title)
        assertEquals("Plan", alpha.sessions.single().title)
        assertTrue(alpha.canAsk)
        // No title: the name; no folder: no chat.
        assertEquals("site", site.label)
        assertFalse(site.canAsk)
        assertFalse(alpha.copy(folderExists = false).canAsk)
    }

    @Test
    fun `a project's page is asked for by its id, encoded`() {
        assertEquals("/projects/p-alpha", ProjectEndpoints.one("p-alpha").path)
        assertEquals("/projects/f-my%20site", ProjectEndpoints.one("f-my site").path)
    }

    @Test
    fun `a project looks the same as its tasks, always with the folder icon`() {
        val looks = Looks.of(emptyList())
        val look = looks.project("Project Alpha")
        assertEquals(look, looks.forTask(Task("t-1", "One", project = "Project Alpha")))
        assertEquals(Glyph.FOLDER, look.glyph)
    }
}
