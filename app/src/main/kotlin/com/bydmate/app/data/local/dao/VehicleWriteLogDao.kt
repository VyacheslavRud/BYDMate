package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity

@Dao
interface VehicleWriteLogDao {
    @Insert suspend fun insert(entity: VehicleWriteLogEntity)

    @Query("SELECT * FROM vehicle_write_log ORDER BY ts DESC LIMIT 200")
    suspend fun getLast200(): List<VehicleWriteLogEntity>

    /** Successful high-level write result; pending/failed attempts are excluded in SQL so an old
     * success cannot disappear merely because more than 200 later audit rows were recorded. */
    @Query("SELECT * FROM vehicle_write_log WHERE status = 0 ORDER BY ts DESC LIMIT 1")
    suspend fun getLastSuccessful(): VehicleWriteLogEntity?
}
