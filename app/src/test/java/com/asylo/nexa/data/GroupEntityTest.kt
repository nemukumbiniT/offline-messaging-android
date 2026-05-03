package com.asylo.nexa.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class GroupEntityTest {

    @Test
    fun addAndGetMembersMaintainsInsertion() {
        val group = GroupEntity()
        val member = UUID.randomUUID()
        group.addMember(member)
        assertTrue(group.getMembers().contains(member))
    }

    @Test
    fun removeMemberRemovesEntry() {
        val group = GroupEntity()
        val member = UUID.randomUUID()
        group.addMember(member)
        group.removeMember(member)
        assertFalse(group.getMembers().contains(member))
    }

    @Test
    fun renameGroupUpdatesName() {
        val group = GroupEntity()
        val newName = "Outdoor crew"
        group.renameGroup(newName)
        assertEquals(newName, group.groupName)
    }
}
