# Privacy and permissions — source preview

This document describes the reviewed source, not a certification of all runtime behavior. Review it against the binary/configuration you distribute.

- Local media, playlists, settings and optional photo processing are handled on the device. Photo enhancement saves a copy; no model service is included. No automatic private-photo upload is part of the enhancement path.
- Optional artwork/metadata, lyrics, weather, news, sport, events and maps features contact third-party services. Requests can expose queries, artist/track names, location/search coordinates and the device's network address to those providers. Do not describe the whole app as offline or promise that providers keep no logs.
- Static endpoints include MusicBrainz, Wikipedia/Wikidata/Wikimedia, TheAudioDB, Deezer, Cover Art Archive, LRCLIB, Google News, ESPN, Open-Meteo, TheSportsDB, Ticketmaster, OpenStreetMap/Nominatim and OSRM. Provider requirements/availability vary; user-supplied API configuration may be necessary.
- Media permissions support the local library. Camera permission supports explicitly initiated capture. Location supports location-dependent features. Notification-listener and usage access support playback/launcher features and require separate user approval where Android demands it. The optional keyboard handles typed text; do not enable it unless you understand and have reviewed its source and capabilities.
- Foreground-service, wake-lock and audio-settings permissions support media behavior. App backup is disabled in the manifest. Installation does not authorize changing launcher defaults, enabling the keyboard/listener, granting sensitive permissions or uploading media automatically.

This source export contains no telemetry/account backend added by the public-release preparation. That statement is narrower than a guarantee that every existing network interaction is anonymous. Keep API keys and personal configuration out of Git and inspect actual network flows before a stable binary release. No privacy contact address or data-retention promise has been invented for this preview.
