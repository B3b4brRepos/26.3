# In-Game Music Player (Minecraft 26.3, Fabric)

Open the player with **M** or by typing **/music** in chat.

## Lyrics
- Lyrics come from **NetEase** for NetEase songs (LRCLIB is the fallback; local files can have a `.lrc` next to them).
- **On-screen lyrics** (Opts tab): the current line is drawn big above the hotbar while a song with synced lyrics plays; size is adjustable.
- The Lyrics tab and the top HUD pill still show them too. (3D in-world lyrics are not included.)

## NetEase login
- **QR login**: Opts tab > QR login, then scan with the NetEase Cloud Music app (also saves `ingamemusic/netease_login_qr.png` so you can pick it from the scan screen's gallery button).
- **Paste a cookie**: paste your `MUSIC_U` value in the Opts box and press Save. The cookie is stored in `<game dir>/ingamemusic/netease_cookie.txt`.
- **Built-in account (optional)**: add a GitHub repository secret named `NETEASE_COOKIE` (the contents of that cookie file). The build bakes it into the jar. Anyone who has the jar can read it, so use a throw-away account and keep the repo private.

# In-Game Music Player (Fabric, Minecraft 1.21)

Client-side only. Minecraft 1.21 (exact), Fabric Loader >= 0.19.5, Fabric API 0.102.0+1.21, Java 21, Yarn 1.21+build.9.

## Build
    ./gradlew build        # jar: build/libs/ingamemusic-1.0.0.jar   (needs internet for maven.fabricmc.net)

## Use
- Press **M** (or type **/music** in chat on touch devices) to open the player (rebind under Controls > In-Game Music). Space = play/pause, Left/Right = seek 5s.
- **Streaming**: NetEase Cloud Music (search, full songs for the account you log in with). MP3 is decoded in pure Java and played through Minecraft's own OpenAL, so no native libraries are needed (works on Android launchers).
- **Local**: put MP3/OGG/WAV into `.minecraft/ingamemusic/music/` (name "Artist - Title.mp3"), Local tab > Refresh.
- **Lyrics**: LRCLIB (no key). Own lyrics: `song.lrc` next to a local file, or `ingamemusic/lyrics/<trackid>.lrc`.
- Config: `config/ingamemusic.json`; playlists/recents: `ingamemusic/library.json`.

## Build on GitHub
Push to GitHub; the workflow in `.github/workflows/build.yml` builds on every push.
Download the jar from the run's **Artifacts** (Actions tab > latest run > `ingamemusic-jar`).
Push a tag like `v1.0.0` to also attach the jar to a GitHub Release.

## 1.1.x notes
- Soft liquid-glass UI (real background blur uses the vanilla *Menu Background Blur* setting; toggle in Opts).
- Panel size and horizontal position are adjustable in Opts (useful on phones with on-screen launcher buttons).
- Touch: tap to press on release, drag lists/lyrics to scroll.
