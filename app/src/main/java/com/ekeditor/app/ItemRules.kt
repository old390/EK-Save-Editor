package com.ekeditor.app

/**
 * Regras conservadoras de empilhamento do catálogo offline v0.5.3.
 *
 * As categorias do TSV não informam diretamente se um item empilha:
 * por segurança, o desconhecido e os equipamentos exigem 1 por slot.
 * Exceções conhecidas:
 * - 5013 Ancient Tolassian Tome e 5035 Tome of Lost Wisdom: tomos unitários.
 * - 5037 Deepfrost Cognac: bebida unitária segundo relatos de jogadores.
 * - outras bebidas especiais são tratadas conservadoramente como unitárias
 *   até serem verificadas em backups/jogo.
 *
 * Uma pilha antiga inválida não é reescrita automaticamente: a regra vale
 * para qualquer nova inserção ou alteração, sem corromper outros slots.
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
            "Este item não é empilhável. Cada cópia deve ocupar um espaço (quantidade máxima por espaço: ${maxPerSlot(itemId)})."
        }
    }
}
