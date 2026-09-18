dependencies {
    // OkHttp is already provided by CloudStream runtime
}

// Use an integer for version numbers
version = 19

cloudstream {
    description = "Open-source multi-device sync plugin for CloudStream. Sync bookmarks, watch progress, search history, repos, and settings across all your devices via Supabase."
    authors = listOf("CloudSync Contributors")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1 // Ok

    tvTypes = listOf("Others")

    iconUrl = ""

    language = "universal"

    requiresResources = false
}
