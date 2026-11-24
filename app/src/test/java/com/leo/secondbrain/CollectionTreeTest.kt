package com.leo.secondbrain

import com.leo.secondbrain.data.CollectionEntity
import com.leo.secondbrain.data.descendantIds
import com.leo.secondbrain.data.pathName
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionTreeTest {
    private val collections = listOf(
        CollectionEntity(id = "work", name = "Work"),
        CollectionEntity(id = "design", name = "Design", parentId = "work"),
        CollectionEntity(id = "research", name = "Research", parentId = "design"),
        CollectionEntity(id = "personal", name = "Personal")
    )

    @Test
    fun descendantsIncludeSelfAndExcludeUnrelatedFolders() {
        assertEquals(setOf("work", "design", "research"), collections.descendantIds("work"))
        assertEquals(setOf("design", "research"), collections.descendantIds("design"))
        assertEquals(setOf("new"), collections.descendantIds("new"))
    }

    @Test
    fun selectorShowsTheWholePath() {
        assertEquals("Work / Design / Research", collections.pathName("research"))
        assertEquals("Personal", collections.pathName("personal"))
    }
}
