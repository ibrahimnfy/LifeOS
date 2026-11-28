package dev.lifeos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputerResumeTest {
    private val id = "11111111-2222-4333-8444-555555555555"

    @Test
    fun `a plain folder is used as it is`() {
        assertEquals("cd /home/user/project && claude --resume $id", ComputerResume.command("/home/user/project", id))
    }

    @Test
    fun `a folder with spaces or shell characters is quoted`() {
        assertEquals("cd '/home/user/My Courses/week 1' && claude --resume $id", ComputerResume.command("/home/user/My Courses/week 1", id))
        assertEquals("cd '/home/user/it'\\''s; rm -rf \$HOME' && claude --resume $id", ComputerResume.command("/home/user/it's; rm -rf \$HOME", id))
        assertEquals("'a\"b`c'", ComputerResume.quoted("a\"b`c"))
    }

    @Test
    fun `no command without a real id and an absolute folder`() {
        assertNull(ComputerResume.command("/home/user/project", "not-an-id"))
        assertNull(ComputerResume.command("/home/user/project", "$id; ls"))
        assertNull(ComputerResume.command("", id))
        assertNull(ComputerResume.command("relative/folder", id))
        assertNull(ComputerResume.command("/home/user/a\nb", id))
    }
}
