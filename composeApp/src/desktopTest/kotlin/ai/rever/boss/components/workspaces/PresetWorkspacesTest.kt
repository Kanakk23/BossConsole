package ai.rever.boss.components.workspaces

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PresetWorkspacesTest {

    @Test
    fun `predefined workspaces include student, researcher, and devops persona presets`() {
        val all = PredefinedWorkspaces.allWorkspaces
        val ids = all.map { it.id }.toSet()

        assertTrue(PredefinedWorkspaces.STUDENT_STUDIO_ID in ids)
        assertTrue(PredefinedWorkspaces.RESEARCHER_STUDIO_ID in ids)
        assertTrue(PredefinedWorkspaces.DEVOPS_OPERATOR_ID in ids)

        val studentWorkspace = all.first { it.id == PredefinedWorkspaces.STUDENT_STUDIO_ID }
        assertEquals("Student Studio (Data Science)", studentWorkspace.name)

        val researcherWorkspace = all.first { it.id == PredefinedWorkspaces.RESEARCHER_STUDIO_ID }
        assertEquals("Scientific Researcher", researcherWorkspace.name)

        val devopsWorkspace = all.first { it.id == PredefinedWorkspaces.DEVOPS_OPERATOR_ID }
        assertEquals("DevOps Operator", devopsWorkspace.name)
    }

    @Test
    fun `workspace ids are unique across all predefined workspaces`() {
        val ids = PredefinedWorkspaces.allWorkspaces.map { it.id }
        assertEquals(ids.toSet().size, ids.size, "Every predefined workspace must have a unique ID")
    }
}
