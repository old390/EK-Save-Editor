package com.ekeditor.app

/**
 * Conservative stacking rules for the offline item catalog.
 *
 * TSV categories do not directly indicate whether an item can stack:
 * for safety, unknown items and equipment are limited to 1 per slot.
 * Known exceptions:
 * - 5013 Ancient Tolassian Tome and 5035 Tome of Lost Wisdom: one per slot.
 * - 5037 Deepfrost Cognac: reportedly one per slot, according to players.
 * - other special drinks are conservatively treated as non-stackable
 *   until verified against game data and backups.
 *
 * Existing invalid stacks are not rewritten automatically. This rule applies
 * to new insertions and changes without corrupting other slots.
 */
object ItemRules {
    private val stackableIds = setOf(
        1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014,
        1015, 1016, 1017, 1018, 1019, 1020, 1021, 1022, 1023, 1024, 1025, 1026, 2001, 2002,
        2003, 2004, 2012, 2013, 2014, 2015, 2016, 2100, 2101, 2102, 2103, 2104, 2105, 5000,
        5001, 5002, 5003, 5004, 5005, 5006, 5007, 5008, 5009, 5010, 5011, 5012, 5014, 5015,
        5016, 5017, 5020, 5021, 5022, 5023, 5024, 5025, 5027, 5028, 5030, 5031, 5032, 5033,
        5034, 5036, 5050, 5051, 5052, 5053, 5060, 5070, 6001, 6002, 6003, 6004, 6005, 6006,
        6007, 6008, 6009, 6010, 6011, 6012, 6013, 6014, 6015
    )

    fun isStackable(itemId: Int): Boolean = itemId in stackableIds

    fun maxPerSlot(itemId: Int): Int = if (isStackable(itemId)) 999 else 1

    fun ensureSlotQuantity(itemId: Int, quantity: Int) {
        require(quantity in 1..maxPerSlot(itemId)) {
            "This item cannot stack. Each copy requires its own slot (maximum per slot: ${maxPerSlot(itemId)})."
        }
    }
}
