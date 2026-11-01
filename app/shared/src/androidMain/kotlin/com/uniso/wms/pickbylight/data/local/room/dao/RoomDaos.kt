package com.uniso.wms.pickbylight.data.local.room.dao

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import com.uniso.wms.pickbylight.data.local.room.entity.EstanteEntity
import com.uniso.wms.pickbylight.data.local.room.entity.EstoquePosicaoEntity
import com.uniso.wms.pickbylight.data.local.room.entity.ItemEntity
import com.uniso.wms.pickbylight.data.local.room.entity.MovimentacaoEntity
import com.uniso.wms.pickbylight.data.local.room.entity.PosicaoEntity
import com.uniso.wms.pickbylight.data.local.room.entity.PrateleiraEntity
import com.uniso.wms.pickbylight.data.local.room.entity.TagNfcEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EstanteDao {
    @Query("SELECT * FROM estantes ORDER BY codigo ASC")
    fun getAllEstantes(): Flow<List<EstanteEntity>>

    @Query("SELECT * FROM estantes WHERE id = :id LIMIT 1")
    suspend fun getEstanteById(id: String): EstanteEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEstante(estante: EstanteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(estantes: List<EstanteEntity>)

    @Query("DELETE FROM estantes")
    suspend fun deleteAll()
}

@Dao
interface PrateleiraDao {
    @Query("SELECT * FROM prateleiras ORDER BY codigo ASC")
    fun getAllPrateleiras(): Flow<List<PrateleiraEntity>>

    @Query("SELECT * FROM prateleiras WHERE estante_id = :estanteId ORDER BY codigo ASC")
    fun getPrateleirasByEstante(estanteId: String): Flow<List<PrateleiraEntity>>

    @Query("SELECT * FROM prateleiras WHERE id = :id LIMIT 1")
    suspend fun getPrateleiraById(id: String): PrateleiraEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPrateleira(prateleira: PrateleiraEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(prateleiras: List<PrateleiraEntity>)

    @Query("DELETE FROM prateleiras")
    suspend fun deleteAll()
}

@Dao
interface PosicaoDao {
    @Query("SELECT * FROM posicoes ORDER BY codigo ASC")
    fun getAllPosicoes(): Flow<List<PosicaoEntity>>

    @Query("SELECT * FROM posicoes WHERE prateleira_id = :prateleiraId ORDER BY codigo ASC")
    fun getPosicoesByPrateleira(prateleiraId: String): Flow<List<PosicaoEntity>>

    @Query("SELECT * FROM posicoes WHERE id = :id LIMIT 1")
    suspend fun getPosicaoById(id: String): PosicaoEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPosicao(posicao: PosicaoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(posicoes: List<PosicaoEntity>)

    @Query("DELETE FROM posicoes")
    suspend fun deleteAll()
}

@Dao
interface TagNfcDao {
    @Query("SELECT * FROM tags_nfc")
    fun getAllTags(): Flow<List<TagNfcEntity>>

    @Query("SELECT * FROM tags_nfc WHERE uid = :uid LIMIT 1")
    suspend fun getTagByUid(uid: String): TagNfcEntity?

    @Query("SELECT * FROM tags_nfc WHERE posicao_id = :posicaoId LIMIT 1")
    suspend fun getTagByPosicaoId(posicaoId: String): TagNfcEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(tag: TagNfcEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<TagNfcEntity>)

    @Query("DELETE FROM tags_nfc WHERE uid = :uid")
    suspend fun deleteTagByUid(uid: String)

    @Query("DELETE FROM tags_nfc")
    suspend fun deleteAll()
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM itens ORDER BY nome ASC")
    fun getAllItens(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM itens WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: String): ItemEntity?

    @Query("SELECT * FROM itens WHERE sku = :sku LIMIT 1")
    suspend fun getItemBySku(sku: String): ItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(item: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(itens: List<ItemEntity>)

    @Update
    suspend fun update(item: ItemEntity)

    @Query("UPDATE itens SET em_separacao = :emSeparacao WHERE id = :itemId")
    suspend fun setEmSeparacao(itemId: String, emSeparacao: Boolean)

    @Query("DELETE FROM itens")
    suspend fun deleteAll()
}

@Dao
interface EstoquePosicaoDao {
    @Query("SELECT * FROM estoques_posicao")
    fun getAllEstoques(): Flow<List<EstoquePosicaoEntity>>

    @Query("SELECT * FROM estoques_posicao WHERE item_id = :itemId")
    fun getEstoquesByItem(itemId: String): Flow<List<EstoquePosicaoEntity>>

    @Query("SELECT * FROM estoques_posicao WHERE posicao_id = :posicaoId")
    fun getEstoquesByPosicao(posicaoId: String): Flow<List<EstoquePosicaoEntity>>

    @Query("SELECT * FROM estoques_posicao WHERE item_id = :itemId AND posicao_id = :posicaoId LIMIT 1")
    suspend fun getEstoque(itemId: String, posicaoId: String): EstoquePosicaoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(estoque: EstoquePosicaoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(estoques: List<EstoquePosicaoEntity>)

    @Query("UPDATE estoques_posicao SET quantidade = :novaQuantidade WHERE item_id = :itemId AND posicao_id = :posicaoId")
    suspend fun updateQuantidade(itemId: String, posicaoId: String, novaQuantidade: Int)

    @Query("DELETE FROM estoques_posicao WHERE item_id = :itemId AND posicao_id = :posicaoId")
    suspend fun delete(itemId: String, posicaoId: String)

    @Query("DELETE FROM estoques_posicao")
    suspend fun deleteAll()
}

@Dao
interface MovimentacaoDao {
    @Query("SELECT * FROM movimentacoes ORDER BY timestamp DESC")
    fun getAllMovimentacoes(): Flow<List<MovimentacaoEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMovimentacao(movimentacao: MovimentacaoEntity)

    @Query("DELETE FROM movimentacoes")
    suspend fun deleteAll()
}

@Database(
    entities = [
        EstanteEntity::class,
        PrateleiraEntity::class,
        PosicaoEntity::class,
        TagNfcEntity::class,
        ItemEntity::class,
        EstoquePosicaoEntity::class,
        MovimentacaoEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class WmsDatabase : RoomDatabase() {
    abstract fun estanteDao(): EstanteDao
    abstract fun prateleiraDao(): PrateleiraDao
    abstract fun posicaoDao(): PosicaoDao
    abstract fun tagNfcDao(): TagNfcDao
    abstract fun itemDao(): ItemDao
    abstract fun estoquePosicaoDao(): EstoquePosicaoDao
    abstract fun movimentacaoDao(): MovimentacaoDao

    @Transaction
    open suspend fun popularSeed(
        estantes: List<EstanteEntity>,
        prateleiras: List<PrateleiraEntity>,
        posicoes: List<PosicaoEntity>,
        tags: List<TagNfcEntity>,
        itens: List<ItemEntity>,
        estoques: List<EstoquePosicaoEntity>
    ) {
        movimentacaoDao().deleteAll()
        estoquePosicaoDao().deleteAll()
        tagNfcDao().deleteAll()
        itemDao().deleteAll()
        posicaoDao().deleteAll()
        prateleiraDao().deleteAll()
        estanteDao().deleteAll()

        estanteDao().insertAll(estantes)
        prateleiraDao().insertAll(prateleiras)
        posicaoDao().insertAll(posicoes)
        tagNfcDao().insertAll(tags)
        itemDao().insertAll(itens)
        estoquePosicaoDao().insertAll(estoques)
    }
}
