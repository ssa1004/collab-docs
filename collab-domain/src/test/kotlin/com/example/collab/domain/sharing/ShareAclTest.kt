package com.example.collab.domain.sharing

import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareAclTest {

    private val docId = DocumentId("d1")
    private val owner = UserId("owner")
    private val editor = UserId("editor")
    private val viewer = UserId("viewer")
    private val stranger = UserId("stranger")

    private fun acl() = ShareAcl.forOwner(docId, owner)
        .grant(editor, Role.EDITOR)
        .grant(viewer, Role.VIEWER)

    @Test
    fun `role policy edit and view`() {
        assertTrue(Role.OWNER.canEdit() && Role.OWNER.canView() && Role.OWNER.canManageSharing())
        assertTrue(Role.EDITOR.canEdit() && Role.EDITOR.canView())
        assertFalse(Role.EDITOR.canManageSharing())
        assertFalse(Role.VIEWER.canEdit())
        assertTrue(Role.VIEWER.canView())
    }

    @Test
    fun `owner has implicit owner role`() {
        assertEquals(Role.OWNER, acl().roleOf(owner))
        assertTrue(acl().canEdit(owner))
        assertTrue(acl().canManageSharing(owner))
    }

    @Test
    fun `editor can edit and view but not manage sharing`() {
        val a = acl()
        assertTrue(a.canEdit(editor))
        assertTrue(a.canView(editor))
        assertFalse(a.canManageSharing(editor))
    }

    @Test
    fun `viewer can view only`() {
        val a = acl()
        assertTrue(a.canView(viewer))
        assertFalse(a.canEdit(viewer))
    }

    @Test
    fun `stranger has no access`() {
        val a = acl()
        assertNull(a.roleOf(stranger))
        assertFalse(a.canView(stranger))
        assertFalse(a.canEdit(stranger))
    }

    @Test
    fun `requireEdit throws AccessDeniedException for viewer`() {
        val ex = assertFailsWith<AccessDeniedException> { acl().requireEdit(viewer) }
        assertEquals(viewer, ex.userId)
        assertEquals("edit", ex.action)
    }

    @Test
    fun `requireView throws for stranger`() {
        assertFailsWith<AccessDeniedException> { acl().requireView(stranger) }
    }

    @Test
    fun `grant replaces existing entry`() {
        val a = acl().grant(viewer, Role.EDITOR)
        assertEquals(Role.EDITOR, a.roleOf(viewer))
        assertTrue(a.canEdit(viewer))
    }

    @Test
    fun `revoke removes access`() {
        val a = acl().revoke(editor)
        assertNull(a.roleOf(editor))
    }

    @Test
    fun `cannot change or revoke owner`() {
        assertFailsWith<IllegalArgumentException> { acl().grant(owner, Role.VIEWER) }
        assertFailsWith<IllegalArgumentException> { acl().revoke(owner) }
    }
}
