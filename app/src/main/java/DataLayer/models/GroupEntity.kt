// GroupEntity - Room entity capturing group conversation metadata.
// Created by Thanyani Nemukumbini.
// Date: 2025-08-17
package com.asylo.nexa.data

import java.util.UUID

data class GroupEntity
    (val groupID: UUID = UUID.randomUUID(),
    var groupName: String = "",
    var groupMembers: MutableList<UUID> = mutableListOf() //use user bridgefy IDs
    ){
    /*TODO:
       - figure out how we're going to do group joining
       - 1. User scanning group QR (generated from random groupID?)
       - 2. Group member adding user (add via member username)
    * */

    fun addMember(memberID: UUID) {
        groupMembers.add(memberID)
    }
    fun removeMember(memberID: UUID){
        //user chooses to leave
        groupMembers.remove(memberID)
    }
    fun getMembers(): List<UUID> {
        return groupMembers
    }
    fun renameGroup(newName: String){
        groupName = newName
    }



}
