package com.kuikagentbus.ui.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusTaskAreaOrderTest {
    private val rows = busTaskAreaRecentRows(previewBusHomeView().tasks)

    @Test
    fun sameOrderIgnoresContentChanges() {
        assertTrue(sameTaskAreaRowOrder(rows, rows))
        val changed = rows.map { row ->
            if (row is BusTaskAreaRow.Task) {
                row.copy(task = row.task.copy(status = "completed", updatedAt = "2026-11-13T00:00:00Z"))
            } else {
                row
            }
        }
        assertTrue(sameTaskAreaRowOrder(rows, changed))
    }

    @Test
    fun insertingOrRemovingATaskIsStructural() {
        val first = rows.first() as BusTaskAreaRow.Task
        val inserted = BusTaskAreaRow.Task(task = first.task.copy(id = "new-task", title = "New task"))
        assertFalse(sameTaskAreaRowOrder(rows, listOf(inserted) + rows))
        assertFalse(sameTaskAreaRowOrder(rows, rows.drop(1)))
    }

    @Test
    fun reorderingIsStructural() {
        assertFalse(sameTaskAreaRowOrder(rows, rows.reversed()))
    }

    @Test
    fun groupHeadersUseProjectKey() {
        val grouped = busTaskAreaProjectRows(
            previewBusHomeView().projectGroups(emptySet()),
            collapsed = emptySet(),
        )
        assertTrue(sameTaskAreaRowOrder(grouped, grouped))
        assertFalse(sameTaskAreaRowOrder(grouped, grouped.drop(1)))
    }
}
