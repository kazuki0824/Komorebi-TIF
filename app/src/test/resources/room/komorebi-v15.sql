-- Schema from the v15 Room entities at 5f1828757e45aa3bcd6f1aeded0b1d7479a58b92.
CREATE TABLE watch_history (
    id INTEGER NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    detailJson TEXT,
    startTime TEXT NOT NULL,
    endTime TEXT NOT NULL,
    duration REAL NOT NULL,
    videoId INTEGER NOT NULL,
    playbackPosition REAL NOT NULL,
    watchedAt INTEGER NOT NULL,
    tileColumns INTEGER NOT NULL,
    tileRows INTEGER NOT NULL,
    tileInterval REAL NOT NULL,
    tileWidth INTEGER NOT NULL,
    tileHeight INTEGER NOT NULL
);
CREATE TABLE last_watched_channel (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    channelId TEXT NOT NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    channelNumber TEXT,
    networkId INTEGER NOT NULL,
    serviceId INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
);
CREATE TABLE epg_cache (
    channelType TEXT NOT NULL PRIMARY KEY,
    dataJson TEXT NOT NULL,
    updatedAt INTEGER NOT NULL
);
CREATE TABLE recorded_programs (
    id INTEGER NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    series_name TEXT NOT NULL,
    is_episodic INTEGER NOT NULL,
    start_time TEXT NOT NULL,
    end_time TEXT NOT NULL,
    video_duration REAL NOT NULL,
    has_key_frames INTEGER NOT NULL,
    is_recording INTEGER NOT NULL,
    playback_position REAL NOT NULL,
    channel_id TEXT,
    channel_type TEXT,
    channel_name TEXT,
    genres TEXT,
    tile_columns INTEGER,
    tile_rows INTEGER,
    tile_interval REAL,
    tile_width INTEGER,
    tile_height INTEGER,
    direct_thumbnail_url TEXT,
    api_thumbnail_url TEXT,
    series_id INTEGER,
    episode_number REAL,
    episode_label TEXT,
    episode_title TEXT,
    air_type TEXT
);
CREATE INDEX index_recorded_programs_channel_id_start_time ON recorded_programs (channel_id, start_time);
CREATE INDEX index_recorded_programs_start_time ON recorded_programs (start_time);
CREATE INDEX index_recorded_programs_title ON recorded_programs (title);
CREATE INDEX index_recorded_programs_series_name ON recorded_programs (series_name);
CREATE TABLE sync_meta (
    id INTEGER NOT NULL PRIMARY KEY,
    last_synced_page INTEGER NOT NULL,
    last_synced_at INTEGER NOT NULL,
    is_initial_build_completed INTEGER NOT NULL
);
CREATE TABLE ai_series_dictionary (
    originalTitle TEXT NOT NULL PRIMARY KEY,
    normalizedSeriesName TEXT NOT NULL,
    updatedAt INTEGER NOT NULL
);
CREATE TABLE epg_channel (
    id TEXT NOT NULL PRIMARY KEY,
    displayChannelId TEXT NOT NULL,
    networkId INTEGER NOT NULL,
    serviceId INTEGER NOT NULL,
    transportStreamId INTEGER NOT NULL,
    remoconId INTEGER NOT NULL,
    channelNumber TEXT NOT NULL,
    type TEXT NOT NULL,
    name TEXT NOT NULL,
    jikkyoForce INTEGER,
    isSubchannel INTEGER NOT NULL,
    isRadiochannel INTEGER NOT NULL,
    isWatchable INTEGER NOT NULL
);
CREATE TABLE epg_program (
    id TEXT NOT NULL PRIMARY KEY,
    channelId TEXT NOT NULL,
    networkId INTEGER NOT NULL,
    serviceId INTEGER NOT NULL,
    eventId INTEGER NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    extended TEXT,
    detailJson TEXT,
    startTime TEXT NOT NULL,
    endTime TEXT NOT NULL,
    startTimeEpoch INTEGER NOT NULL,
    endTimeEpoch INTEGER NOT NULL,
    duration INTEGER NOT NULL,
    isFree INTEGER NOT NULL,
    genresJson TEXT,
    videoType TEXT,
    audioType TEXT,
    audioSamplingRate TEXT,
    FOREIGN KEY (channelId) REFERENCES epg_channel(id) ON DELETE CASCADE
);
CREATE INDEX index_epg_program_channelId ON epg_program (channelId);
CREATE INDEX index_epg_program_startTimeEpoch ON epg_program (startTimeEpoch);
CREATE INDEX index_epg_program_endTimeEpoch ON epg_program (endTimeEpoch);
CREATE INDEX index_epg_program_title ON epg_program (title);
