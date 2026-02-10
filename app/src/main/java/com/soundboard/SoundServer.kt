package com.soundboard

import fi.iki.elonen.NanoHTTPD
import java.net.URL

class SoundServer(port: Int, private val soundPlayer: SoundPlayer) : NanoHTTPD(port) {

    companion object {
        private const val BASE_URL = "https://www.myinstants.com/media/sounds/"
    }

    private val TOO_MANY_REQUESTS = object : Response.IStatus {
        override fun getRequestStatus() = 429
        override fun getDescription() = "429 Too Many Requests"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parms
        val clientIp = session.remoteIpAddress

        return when {
            uri == "/play" -> handlePlay(params, clientIp)
            uri == "/play-url" -> handlePlayUrl(params, clientIp)
            uri == "/stop" -> handleStop()
            uri == "/status" -> handleStatus()
            uri == "/recent" -> handleRecent()
            uri == "/rate-limits" -> handleRateLimits()
            uri == "/ui" -> handleUI()
            uri == "/" -> handleRoot()
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                """{"error": "Not found", "endpoints": ["/play?file=<name>.mp3", "/play-url?url=<myinstants-url>", "/stop", "/status", "/recent", "/rate-limits", "/ui"]}"""
            )
        }
    }

    private fun handlePlay(params: Map<String, String>, clientIp: String): Response {
        val filename = params["file"]

        if (filename.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error": "Missing 'file' parameter", "usage": "/play?file=example.mp3"}"""
            )
        }

        val rateLimitResult = RateLimitManager.checkAndRecord(clientIp)
        if (!rateLimitResult.allowed) {
            return newFixedLengthResponse(
                TOO_MANY_REQUESTS,
                "application/json",
                """{"error": "Rate limit exceeded", "used": ${rateLimitResult.used}, "limit": ${rateLimitResult.limit}, "retryAfterSeconds": ${rateLimitResult.remainingSeconds}}"""
            )
        }

        val url = BASE_URL + filename

        return try {
            soundPlayer.play(url)
            RecentSoundsManager.addSound(filename)
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status": "playing", "file": "$filename", "url": "$url"}"""
            )
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error": "Failed to play sound", "message": "${e.message}"}"""
            )
        }
    }

    private fun handlePlayUrl(params: Map<String, String>, clientIp: String): Response {
        val pageUrl = params["url"]

        if (pageUrl.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error": "Missing 'url' parameter", "usage": "/play-url?url=https://www.myinstants.com/en/instant/..."}"""
            )
        }

        val rateLimitResult = RateLimitManager.checkAndRecord(clientIp)
        if (!rateLimitResult.allowed) {
            return newFixedLengthResponse(
                TOO_MANY_REQUESTS,
                "application/json",
                """{"error": "Rate limit exceeded", "used": ${rateLimitResult.used}, "limit": ${rateLimitResult.limit}, "retryAfterSeconds": ${rateLimitResult.remainingSeconds}}"""
            )
        }

        return try {
            // Fetch the myinstants page
            val html = URL(pageUrl).readText()

            // Extract filename from: var preloadAudioUrl = '/media/sounds/xxx.mp3';
            val regex = """var preloadAudioUrl = '/media/sounds/([^']+)';""".toRegex()
            val match = regex.find(html)
            val filename = match?.groupValues?.get(1)

            if (filename == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"error": "Could not find sound on page", "url": "$pageUrl"}"""
                )
            }

            // Play the sound
            val soundUrl = BASE_URL + filename
            soundPlayer.play(soundUrl)
            RecentSoundsManager.addSound(filename)

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status": "playing", "file": "$filename", "url": "$soundUrl"}"""
            )
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error": "Failed to fetch or play sound", "message": "${e.message}"}"""
            )
        }
    }

    private fun handleRecent(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            RecentSoundsManager.toJson()
        )
    }

    private fun handleRateLimits(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            RateLimitManager.toJson()
        )
    }

    private fun handleStop(): Response {
        soundPlayer.stop()
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"status": "stopped"}"""
        )
    }

    private fun handleStatus(): Response {
        val isPlaying = soundPlayer.isPlaying()
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"server": "running", "playing": $isPlaying}"""
        )
    }

    private fun handleRoot(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html",
            """
            <!DOCTYPE html>
            <html>
            <head><title>Soundboard</title></head>
            <body>
                <h1>Soundboard Server</h1>
                <h2>Endpoints:</h2>
                <ul>
                    <li><code>GET /play?file=&lt;filename&gt;.mp3</code> - Play a sound from myinstants.com</li>
                    <li><code>GET /play-url?url=&lt;myinstants-page-url&gt;</code> - Play sound from myinstants page URL</li>
                    <li><code>GET /stop</code> - Stop current playback</li>
                    <li><code>GET /status</code> - Get server status</li>
                    <li><code>GET /recent</code> - Get recently played sounds</li>
                    <li><code>GET /rate-limits</code> - Get rate limit quotas per IP</li>
                    <li><code>GET /ui</code> - Interactive web UI</li>
                </ul>
                <h2>Example:</h2>
                <pre>curl "http://&lt;this-ip&gt;:8080/play?file=mgs-alert.mp3"</pre>
                <pre>curl "http://&lt;this-ip&gt;:8080/play-url?url=https://www.myinstants.com/en/instant/apple-pay-45496/"</pre>
                <p><a href="/ui">Open Interactive UI</a></p>
            </body>
            </html>
            """.trimIndent()
        )
    }

    private fun handleUI(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html",
            generateWebUI()
        )
    }

    private fun generateWebUI(): String = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Soundboard</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: #1a1a2e;
            min-height: 100vh;
            padding: 20px;
        }

        h1 {
            color: #fff;
            text-align: center;
            margin-bottom: 10px;
            font-size: 2em;
        }

        .hint {
            color: #888;
            text-align: center;
            margin-bottom: 15px;
            font-size: 14px;
        }

        .hint a {
            color: #4ECDC4;
        }

        .url-input-container {
            max-width: 800px;
            margin: 0 auto 20px auto;
            display: flex;
            gap: 10px;
        }

        .url-input {
            flex: 1;
            padding: 12px 16px;
            border: none;
            border-radius: 8px;
            font-size: 16px;
            background: #16213e;
            color: #fff;
        }

        .url-input::placeholder {
            color: #666;
        }

        .url-input:focus {
            outline: 2px solid #4ECDC4;
        }

        .play-url-button {
            background: #27ae60;
            color: white;
            border: none;
            padding: 12px 24px;
            border-radius: 8px;
            font-size: 16px;
            cursor: pointer;
            font-weight: 600;
        }

        .play-url-button:hover {
            background: #2ecc71;
        }

        .button-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
            gap: 15px;
            max-width: 1200px;
            margin: 0 auto;
        }

        .sound-button {
            padding: 20px 15px;
            border: none;
            border-radius: 12px;
            cursor: pointer;
            font-size: 14px;
            font-weight: 600;
            color: #fff;
            text-shadow: 1px 1px 2px rgba(0,0,0,0.3);
            box-shadow: 0 4px 6px rgba(0,0,0,0.3),
                        inset 0 1px 0 rgba(255,255,255,0.2);
            transition: transform 0.1s, box-shadow 0.1s;
            word-wrap: break-word;
            text-align: center;
            min-height: 80px;
            display: flex;
            align-items: center;
            justify-content: center;
        }

        .sound-button:hover {
            transform: translateY(-2px);
            box-shadow: 0 6px 12px rgba(0,0,0,0.4),
                        inset 0 1px 0 rgba(255,255,255,0.2);
        }

        .sound-button:active {
            transform: translateY(1px);
            box-shadow: 0 2px 4px rgba(0,0,0,0.3),
                        inset 0 1px 0 rgba(255,255,255,0.2);
        }

        .empty-state {
            color: #888;
            text-align: center;
            padding: 40px;
            font-size: 1.2em;
            grid-column: 1 / -1;
        }

        .controls {
            text-align: center;
            margin-bottom: 20px;
        }

        .stop-button {
            background: #e74c3c;
            color: white;
            border: none;
            padding: 10px 30px;
            border-radius: 8px;
            font-size: 16px;
            cursor: pointer;
            margin-right: 10px;
        }

        .stop-button:hover {
            background: #c0392b;
        }

        .refresh-button {
            background: #3498db;
            color: white;
            border: none;
            padding: 10px 30px;
            border-radius: 8px;
            font-size: 16px;
            cursor: pointer;
        }

        .refresh-button:hover {
            background: #2980b9;
        }

        .error-message {
            color: #e74c3c;
            text-align: center;
            margin-bottom: 10px;
        }
    </style>
</head>
<body>
    <h1>Soundboard</h1>
    <p class="hint">Paste a link copied from <a href="https://www.myinstants.com/en/index/pl/" target="_blank">myinstants.com</a></p>
    <div class="url-input-container">
        <input type="text" id="urlInput" class="url-input" placeholder="https://www.myinstants.com/en/instant/...">
        <button class="play-url-button" onclick="playUrl()">Play</button>
    </div>
    <div id="error" class="error-message"></div>
    <div class="controls">
        <button class="stop-button" onclick="stopSound()">Stop</button>
        <button class="refresh-button" onclick="loadSounds()">Refresh</button>
    </div>
    <div id="rateLimits" style="max-width: 800px; margin: 0 auto 20px auto; background: #16213e; border-radius: 8px; padding: 15px; color: #ccc; font-size: 14px;">
        <h3 style="color: #4ECDC4; margin: 0 0 10px 0; font-size: 16px;">Rate Limits (5 plays / 10 min)</h3>
        <div id="rateLimitList" style="font-family: monospace;"><span style="color: #666;">No activity yet</span></div>
    </div>
    <div id="buttons" class="button-grid"></div>
    <script>
        async function loadSounds() {
            try {
                const response = await fetch('/recent');
                const data = await response.json();
                renderButtons(data.sounds);
            } catch (error) {
                console.error('Failed to load sounds:', error);
            }
        }

        function renderButtons(sounds) {
            const container = document.getElementById('buttons');

            if (!sounds || sounds.length === 0) {
                container.innerHTML = '<div class="empty-state">No sounds played yet.<br>Paste a myinstants.com link above to add sounds.</div>';
                return;
            }

            container.innerHTML = sounds.map(sound =>
                '<button class="sound-button" style="background-color: ' + sound.color + '" onclick="playSound(\'' + sound.filename.replace(/'/g, "\\'") + '\')">' +
                escapeHtml(sound.displayName) +
                '</button>'
            ).join('');
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        async function playSound(filename) {
            document.getElementById('error').textContent = '';
            try {
                const response = await fetch('/play?file=' + encodeURIComponent(filename));
                const data = await response.json();
                if (response.status === 429) {
                    document.getElementById('error').textContent = 'Rate limited! Try again in ' + data.retryAfterSeconds + 's';
                    loadRateLimits();
                    return;
                }
                setTimeout(loadSounds, 100);
                loadRateLimits();
            } catch (error) {
                console.error('Failed to play sound:', error);
            }
        }

        async function playUrl() {
            const url = document.getElementById('urlInput').value.trim();
            if (!url) return;

            document.getElementById('error').textContent = '';

            try {
                const response = await fetch('/play-url?url=' + encodeURIComponent(url));
                const data = await response.json();
                if (response.status === 429) {
                    document.getElementById('error').textContent = 'Rate limited! Try again in ' + data.retryAfterSeconds + 's';
                    loadRateLimits();
                    return;
                }
                if (data.error) {
                    document.getElementById('error').textContent = data.error;
                } else {
                    document.getElementById('urlInput').value = '';
                    setTimeout(loadSounds, 100);
                    loadRateLimits();
                }
            } catch (error) {
                document.getElementById('error').textContent = 'Failed to play URL';
                console.error('Failed to play URL:', error);
            }
        }

        // Allow Enter key to submit URL
        document.getElementById('urlInput').addEventListener('keypress', function(e) {
            if (e.key === 'Enter') {
                playUrl();
            }
        });

        async function stopSound() {
            try {
                await fetch('/stop');
            } catch (error) {
                console.error('Failed to stop sound:', error);
            }
        }

        async function loadRateLimits() {
            try {
                const response = await fetch('/rate-limits');
                const data = await response.json();
                renderRateLimits(data.quotas);
            } catch (error) {
                console.error('Failed to load rate limits:', error);
            }
        }

        function renderRateLimits(quotas) {
            const container = document.getElementById('rateLimitList');
            if (!quotas || quotas.length === 0) {
                container.innerHTML = '<span style="color: #666;">No activity yet</span>';
                return;
            }
            container.innerHTML = quotas.map(function(q) {
                var pct = (q.used / q.limit) * 100;
                var barColor = q.used >= q.limit ? '#e74c3c' : (q.used >= 3 ? '#f39c12' : '#27ae60');
                return '<div style="margin-bottom: 8px;">' +
                    '<span>' + escapeHtml(q.ip) + ': ' + q.used + '/' + q.limit + '</span>' +
                    '<div style="background: #0f3460; border-radius: 4px; height: 6px; margin-top: 4px;">' +
                    '<div style="background: ' + barColor + '; width: ' + pct + '%; height: 100%; border-radius: 4px;"></div>' +
                    '</div></div>';
            }).join('');
        }

        loadSounds();
        loadRateLimits();
        setInterval(loadSounds, 5000);
        setInterval(loadRateLimits, 5000);
    </script>
</body>
</html>
    """.trimIndent()
}
