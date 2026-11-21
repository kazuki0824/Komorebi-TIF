package com.beeregg2001.komorebi.regression

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.dao.RecordedProgramDao
import com.beeregg2001.komorebi.data.local.entity.RecordedProgramEntity
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.ReserveProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.data.repository.epgstation.EpgStationRecordRepository
import com.beeregg2001.komorebi.data.sync.RecordSyncEngine
import com.beeregg2001.komorebi.data.sync.SyncProgress
import com.beeregg2001.komorebi.ui.video.components.RecordCategory
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production RecordViewModel: recording visibility, search restoration and sync delegation. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordViewModelRegressionTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var provider: RecordProvider
    private lateinit var sync: RecordSyncEngine
    private lateinit var viewModel: RecordViewModel

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        provider = Mockito.mock(RecordProvider::class.java)
        sync = Mockito.mock(RecordSyncEngine::class.java)
        Mockito.`when`(sync.recordCacheReady).thenReturn(MutableStateFlow(false))
        Mockito.`when`(sync.recordCacheGeneration).thenReturn(MutableStateFlow(0L))
        Mockito.`when`(sync.syncProgress).thenReturn(MutableStateFlow(SyncProgress()))
        Mockito.`when`(sync.recordPublicationLock).thenReturn(Any())
        Mockito.doReturn(false).`when`(sync).isInitialBuildCompleted()

        val settings = Mockito.mock(SettingsRepository::class.java)
        Mockito.`when`(settings.backendType).thenReturn(flowOf("KONOMITV"))
        Mockito.`when`(settings.defaultRecordListView).thenReturn(flowOf("LIST"))
        val dao = Mockito.mock(RecordedProgramDao::class.java)
        Mockito.`when`(dao.getRecentRecordingsFlow()).thenReturn(flowOf(emptyList<RecordedProgramEntity>()))
        viewModel = RecordViewModel(
            Mockito.mock(LiveProvider::class.java), provider,
            Mockito.mock(EpgStationRecordRepository::class.java),
            Mockito.mock(ReserveProvider::class.java),
            Mockito.mock(WatchHistoryRepository::class.java),
            settings, sync, dao, ApplicationProvider.getApplicationContext<Context>()
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun searchRestoresPreviouslySelectedRecordingCategory() {
        viewModel.updateChannel("channel-1")
        assertEquals(RecordCategory.CHANNEL, viewModel.selectedCategory.value)
        viewModel.searchRecordings("needle")
        assertEquals("needle", viewModel.activeSearchQuery.value)
        assertEquals(RecordCategory.ALL, viewModel.selectedCategory.value)
        viewModel.clearSearch()
        assertEquals(RecordCategory.CHANNEL, viewModel.selectedCategory.value)
        assertEquals("", viewModel.activeSearchQuery.value)
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun unpublishedRecordedCacheBlocksProgramDetailNetworkAccess() = runBlocking {
        viewModel.fetchProgramDetail(42)
        delay(600L) // Production's detail job waits 300 ms before checking ownership gate.
        Mockito.verify(provider, Mockito.never()).getRecordedProgram(42)
        assertNull(viewModel.programDetail.value)
    }

    @Test
    fun forcedRefreshRetainsFullSyncIntent() {
        viewModel.fetchRecentRecordings(forceRefresh = true)
        Mockito.verify(sync).launchSyncAllRecords(forceFullSync = true)
    }
}
