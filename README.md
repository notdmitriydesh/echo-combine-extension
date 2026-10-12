# Echo Combine Extension

An enhanced fork of [Echo Combine Extension](https://github.com/dutixlf/echo-combine-extension) for [Echo](https://github.com/brahmkshatriya/echo).

Combine metadata from one music service with audio streams from another, with prioritized source selection, intelligent track matching, caching, and configurable fallback behavior.

## ✨ Features

### 🎵 Separate Metadata and Audio Sources

- Use one extension for metadata, search, albums, artists, playlists, and library feeds.
- Use another extension to provide audio streams.
- Configure a primary stream provider and up to four alternative providers.

### ⚡ First Available Mode

- Enabled by default.
- Checks stream providers in priority order and uses the first one that returns a playable track.
- Starts trying the next provider if the previous one fails or takes too long to respond.
- Preserves the primary provider's original quality tiers instead of mixing streams from different services.

For example, with Deezer providing `FLAC`, `320 kbps`, and `128 kbps`:

| Network | Echo quality setting | Expected quality |
|---|---|---|
| Wi-Fi | Highest | FLAC |
| Mobile data | Medium | 320 kbps |
| Mobile data | Lowest | 128 kbps |

### 🔄 Smart Fallback Sources

- Configure alternative providers in priority order.
- By default, fallback providers expose only their highest-quality stream.
- This allows a backup provider, such as YouTube Music, to provide its best available quality even when Echo is configured to use Medium or Lowest.
- Optionally include the metadata provider as an additional stream source.

### 🎯 Intelligent Track Matching

Instead of blindly selecting the first search result, the extension scores candidate tracks using:

- **ISRC** — an exact identifier match receives the highest priority.
- **Track title** — normalized to reduce differences caused by punctuation, capitalization, and common suffixes.
- **Artist name** — full and partial matches are considered.
- **Duration** — similar track lengths receive higher scores.
- **Version tags** — differences involving Live, Remix, Acoustic, Instrumental, Karaoke, Cover, Slowed, Sped Up, Nightcore, and other supported tags are penalized.

The extension evaluates the best candidates and selects the first one that provides playable streams.

### 🚀 Caching and Performance

- In-memory LRU cache with a maximum of 200 entries.
- Successful track matches are cached for 30 minutes.
- Unsuccessful lookups are cached for 5 minutes.
- Individual stream-provider lookups have a 12-second timeout.
- Alternative providers can start being queried after 4 seconds if the preferred provider is still responding.
- Timed-out lookups are not cached, allowing them to be retried later.

### 🛠️ Configurable Settings

| Setting | Default | Description |
|---|---|---|
| Metadata Extension | First available | Selects the provider for metadata and library operations. |
| First Available Mode | Enabled | Uses the first provider that finds a playable track instead of combining streams from all providers. |
| Fallback Sources: Best Quality Only | Enabled | Exposes only the best-quality stream from a fallback provider. |
| Include Metadata Extension As Fallback Stream | Disabled | Allows the metadata provider to supply audio streams when enabled. |
| Preferred Stream Extension | First available | Selects the primary audio provider. |
| Alternative Stream Extensions | None | Configures additional providers in priority order. |

## ⚙️ Recommended Setup

For a setup using Spotify for metadata and Deezer for audio:

1. Install the required extensions in Echo.
2. Open **Settings → Extensions → Combine Extension**.
3. Set **Metadata Extension** to Spotify.
4. Set **Preferred Stream Extension** to Deezer.
5. Add YouTube Music or another compatible provider as a fallback if desired.
6. Enable **First Available Mode** and **Fallback Sources: Best Quality Only**.
7. In Echo's player settings, select **Highest** for Wi-Fi and **Medium** for mobile data.

The resulting quality depends on the streams actually provided by the selected extension.

## 📥 Installation

1. Open the [GitHub repository](https://github.com/notdmitriydesh/echo-combine-extension).
2. Check the available releases or project build instructions.
3. Install a compatible extension package using Echo's supported installation method.

If no prebuilt release is available, you may need to build the project yourself.

## 🔧 Technical Details

| Parameter | Value |
|---|---|
| Language | Kotlin |
| Cache capacity | 200 entries |
| Successful match cache | 30 minutes |
| Failed lookup cache | 5 minutes |
| Stream lookup timeout | 12 seconds |
| Fallback start delay | 4 seconds |
| Maximum match candidates evaluated | 3 |

## 🙏 Credits

This project is a fork of [dutixlf/echo-combine-extension](https://github.com/dutixlf/echo-combine-extension).

The original project and its author deserve credit for the initial implementation. This fork builds upon that work with improved stream selection, track matching, caching, and fallback behavior.

## ⚖️ Disclaimer

This is an independent community fork and is not affiliated with or endorsed by the original project author or the developers of Echo.

Available tracks, audio quality, and provider compatibility depend on the installed extensions and their respective services.
