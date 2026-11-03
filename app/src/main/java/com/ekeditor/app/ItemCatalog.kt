package com.ekeditor.app

import android.content.Context

/** Bundled offline community item list (published September 2021). */
class ItemCatalog(context: Context) {
    data class Item(val id: Int, val name: String, val category: String)
    val items: List<Item> = context.assets.open("ek_items.tsv").bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { row ->
                val columns = row.split('\t')
                if (columns.size < 3) null else columns[0].toIntOrNull()?.let { Item(it, columns[1], columns[2]) }
            }.toList()
    }
    private val indexed = items.associateBy { it.id }
    fun name(id: Int): String = indexed[id]?.name ?: "Item not found in catalog"
    fun potions(): List<Item> = items.filter { it.category.equals("POTION", true) }
    fun find(query: String, potionsOnly: Boolean = false): List<Item> {
        val typed = query.trim()
        return (if (potionsOnly) potions() else items).filter { item ->
            typed.isEmpty() || item.name.contains(typed, ignoreCase = true) || item.id.toString().contains(typed)
        }
    }
}
