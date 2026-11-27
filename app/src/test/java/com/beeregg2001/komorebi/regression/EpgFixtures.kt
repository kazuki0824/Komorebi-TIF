package com.beeregg2001.komorebi.regression

import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import java.time.OffsetDateTime

internal fun epgFixture(
    id: String,
    title: String,
    start: OffsetDateTime,
    channelName: String = id,
    type: String = "GR"
): EpgChannelWrapper {
    val channel = EpgChannel(
        id = id, display_channel_id = id, network_id = 1, service_id = 1,
        transport_stream_id = 1, remocon_id = 1, channel_number = "101",
        type = type, name = channelName, jikkyo_force = null, is_subchannel = false,
        is_radiochannel = false, is_watchable = true
    )
    val program = EpgProgram(
        id = "$id-program", channel_id = id, network_id = 1, service_id = 1, event_id = 1,
        title = title, description = "Fixture program description", detail = null,
        start_time = start.toString(), end_time = start.plusHours(1).toString(),
        duration = 3600, is_free = true, genres = emptyList(), video_type = null,
        audio_type = null, audio_sampling_rate = null
    )
    return EpgChannelWrapper(channel, listOf(program))
}
