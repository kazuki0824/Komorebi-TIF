package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.local.dao.LastChannelDao
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.repository.LastChannelRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LastChannelRegressionTest {
    @Database(entities = [LastChannelEntity::class], version = 1, exportSchema = false)
    abstract class TestDatabase : RoomDatabase() {
        abstract fun lastChannelDao(): LastChannelDao
    }

    private lateinit var db: TestDatabase
    private lateinit var repository: LastChannelRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TestDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = LastChannelRepository(db.lastChannelDao())
    }

    @After
    fun tearDown() = db.close()

    private fun item(n: Int, onid: Long = n.toLong(), sid: Long = n.toLong()) =
        LastChannelEntity(
            channelId = "channel-$n", name = "Channel $n", type = "GR",
            channelNumber = n.toString(), networkId = onid, serviceId = sid,
            updatedAt = n.toLong()
        )

    @Test
    fun filterBeforeTenItemLimitRetainsAllAvailableHistory() = runBlocking {
        // The 15 newest entries are unavailable; the 10 older entries are available.
        (1..25).forEach { db.lastChannelDao().insert(item(it)) }
        val available = (1..10).map { "channel-$it" }
        val found = repository.getRecentForChannels(available)
        assertEquals(10, found.size)
        assertEquals((10 downTo 1).map { "channel-$it" }, found.map { it.channelId })
    }

    @Test
    fun limitIsAppliedAfterSortingForMoreThanTenAvailableChannels() = runBlocking {
        (1..25).forEach { db.lastChannelDao().insert(item(it)) }
        assertEquals(
            (25 downTo 16).map { "channel-$it" },
            repository.getRecentForChannels((1..25).map { "channel-$it" })
                .map { it.channelId }
        )
    }

    @Test
    fun emptyOrUnknownAvailableChannelsReturnNoHistory() = runBlocking {
        db.lastChannelDao().insert(item(1))
        assertTrue(repository.getRecentForChannels(emptyList()).isEmpty())
        assertTrue(repository.getRecentForChannels(listOf("unknown")).isEmpty())
    }

    @Test
    fun rewatchingSameNetworkAndServiceReplacesPriorEntry() = runBlocking {
        repository.saveLastChannel(item(1, onid = 1, sid = 100))
        repository.saveLastChannel(item(2, onid = 1, sid = 100))
        val rows = db.lastChannelDao().getLastChannels().first()
        assertEquals(1, rows.size)
        assertEquals("channel-2", rows.single().channelId)
    }

    @Test
    fun sameServiceIdOnDifferentNetworkDoesNotCollapse() = runBlocking {
        repository.saveLastChannel(item(1, onid = 1, sid = 42))
        repository.saveLastChannel(item(2, onid = 2, sid = 42))
        val rows = db.lastChannelDao().getLastChannels().first()
        assertEquals(2, rows.size)
    }
}
