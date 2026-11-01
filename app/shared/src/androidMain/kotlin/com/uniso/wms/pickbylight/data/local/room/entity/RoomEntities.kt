package com.uniso.wms.pickbylight.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "estantes",
    indices = [
        Index(value = ["codigo"], unique = true),
        Index(value = ["led_id"], unique = true)
    ]
)
data class EstanteEntity(
    @PrimaryKey val id: String,
    val codigo: String,
    val nome: String,
    @ColumnInfo(name = "led_id") val ledId: String,
    val setor: String = "Geral"
)

@Entity(
    tableName = "prateleiras",
    indices = [
        Index(value = ["estante_id", "codigo"], unique = true),
        Index(value = ["led_id_ponta"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = EstanteEntity::class,
            parentColumns = ["id"],
            childColumns = ["estante_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PrateleiraEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "estante_id") val estanteId: String,
    val codigo: String,
    @ColumnInfo(name = "led_id_ponta") val ledIdPonta: String
)

@Entity(
    tableName = "posicoes",
    indices = [
        Index(value = ["prateleira_id", "codigo"], unique = true),
        Index(value = ["led_id"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = PrateleiraEntity::class,
            parentColumns = ["id"],
            childColumns = ["prateleira_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PosicaoEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "prateleira_id") val prateleiraId: String,
    val codigo: String,
    @ColumnInfo(name = "limites_lap") val limitesLap: String = "30x20x40cm",
    @ColumnInfo(name = "led_id") val ledId: String
    // tagUid removido: vínculo resolvido exclusivamente via TagNfcEntity (fonte única de verdade)
)

@Entity(
    tableName = "tags_nfc",
    indices = [
        Index(value = ["posicao_id"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = PosicaoEntity::class,
            parentColumns = ["id"],
            childColumns = ["posicao_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TagNfcEntity(
    @PrimaryKey val uid: String,
    @ColumnInfo(name = "posicao_id") val posicaoId: String,
    @ColumnInfo(name = "data_vinculo") val dataVinculo: Long
)

@Entity(
    tableName = "itens",
    indices = [
        Index(value = ["sku"], unique = true)
    ]
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val sku: String,
    val nome: String,
    val categoria: String,
    val unidade: String,
    @ColumnInfo(name = "estoque_minimo") val estoqueMinimo: Int = 5,
    @ColumnInfo(name = "em_separacao") val emSeparacao: Boolean = false
)

@Entity(
    tableName = "estoques_posicao",
    indices = [
        Index(value = ["item_id", "posicao_id"], unique = true),
        Index(value = ["posicao_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PosicaoEntity::class,
            parentColumns = ["id"],
            childColumns = ["posicao_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class EstoquePosicaoEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "posicao_id") val posicaoId: String,
    val quantidade: Int
)

@Entity(
    tableName = "movimentacoes",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PosicaoEntity::class,
            parentColumns = ["id"],
            childColumns = ["posicao_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class MovimentacaoEntity(
    @PrimaryKey val id: String,
    val tipo: String, // ENTRADA, SAIDA, TRANSFERENCIA
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "posicao_id") val posicaoId: String,
    val quantidade: Int,
    val observacao: String? = null,
    val timestamp: Long,
    val usuario: String = "Operador Padrão",
    @ColumnInfo(name = "modo_manual") val modoManual: Boolean = false
)
