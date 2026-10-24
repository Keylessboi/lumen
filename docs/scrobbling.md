# Scrobbling

Lumen can send what you listen to — artist, title, album, when you played it — to **Last.fm**, **Libre.fm** or **ListenBrainz**. It is off until you sign in to a service on the *Scrobbling* tab, and it never sends anything about your apps or screen time. Listening history goes only to the services you choose; it is not part of sync.

## Sign-in

No service password is ever typed into Lumen.

| Service | How |
|---|---|
| Last.fm | *Sign in with Last.fm* opens Last.fm's approval page in the browser (`auth.getToken` → `last.fm/api/auth` → `auth.getSession`). Lumen notices the approval by itself. |
| Libre.fm | Same flow on `libre.fm/api/auth`. |
| ListenBrainz | *Get your ListenBrainz token* opens `listenbrainz.org/settings`; paste the user token. Checked with `validate-token`. |

Session keys and tokens are stored in the keyring (Linux Secret Service, private-file fallback; Android Keystore-wrapped storage).

### Last.fm API key

Last.fm issues API credentials per application, and they are not in this repository. To enable Last.fm, create an API account at <https://www.last.fm/api/account/create> and build with:

```properties
# ~/.gradle/gradle.properties or local.properties
lumen.lastfm.apiKey=...
lumen.lastfm.sharedSecret=...
```

or the `LUMEN_LASTFM_API_KEY` / `LUMEN_LASTFM_SHARED_SECRET` environment variables. Without them the Last.fm row says so; Libre.fm (which accepts any client key) and ListenBrainz work regardless.

## What counts as a play

The rule Last.fm and ListenBrainz both publish (`PlayTracker`): a track longer than 30 seconds scrobbles once it has **played** for half its length or 4 minutes, whichever is first. Paused time does not count. Unknown length: 4 minutes. Replaying from the start counts again. Web browsers are skipped unless *Include web browsers* is on — they mostly play video. Plays are queued while offline and sent when possible; plays older than 14 days are dropped (Last.fm refuses them).

## Where "now playing" comes from

- **Linux:** MPRIS over D-Bus, read with `gdbus` (GLib) every 5 s — Spotify, mpv, VLC, Rhythmbox, browsers, mpd via mpDris2. With the background service running, the service scrobbles and the window only configures it.
- **Android:** media sessions, which Android only shares with an enabled *notification listener*. Lumen asks for that access, reads media controls only, and never reads notifications. While enabled, scrobbling works with Lumen closed.
