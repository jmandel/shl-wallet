package me.fhir.shcwallet.data.repository

import me.fhir.shcwallet.data.db.CombinedShcEntity
import me.fhir.shcwallet.data.db.ShcDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShcRepository(private val shcDao: ShcDao) {

    suspend fun getAllCombinedShcs(): List<CombinedShcEntity> {
        return withContext(Dispatchers.IO) {
            shcDao.getAllCombinedShcs()
        }
    }

    suspend fun getCombinedShcById(id: Long): CombinedShcEntity? {
        return withContext(Dispatchers.IO) {
            shcDao.getCombinedShcById(id)
        }
    }

    suspend fun findByShlPayloadUrl(url: String): CombinedShcEntity? {
        return withContext(Dispatchers.IO) {
            shcDao.findByShlPayloadUrl(url)
        }
    }

    suspend fun insertCombinedShc(shcEntity: CombinedShcEntity): Long {
        return withContext(Dispatchers.IO) {
            shcDao.insertCombinedShc(shcEntity)
        }
    }

    // Add other necessary DAO methods as wrappers if needed, e.g., delete, update, counts
    suspend fun getTotalShcCount(): Int {
        return withContext(Dispatchers.IO) {
            shcDao.getTotalShcCount()
        }
    }

    suspend fun clearAllCombinedShcs(): Int {
        return withContext(Dispatchers.IO) {
            shcDao.clearAll()
        }
    }
} 