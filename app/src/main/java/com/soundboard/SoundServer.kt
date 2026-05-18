package com.soundboard

import fi.iki.elonen.NanoHTTPD
import java.net.HttpURLConnection
import java.net.URL

class SoundServer(port: Int, private val soundPlayer: SoundPlayer) : NanoHTTPD(port) {

    companion object {
        private const val BASE_URL = "https://www.myinstants.com/media/sounds/"
    }

    private val TOO_MANY_REQUESTS = object : Response.IStatus {
        override fun getRequestStatus() = 429
        override fun getDescription() = "429 Too Many Requests"
    }

    private val FOCUS_BLOCKED = object : Response.IStatus {
        override fun getRequestStatus() = 403
        override fun getDescription() = "403 Forbidden - Focus Time Active"
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
            uri == "/focus-vote" -> handleFocusVote(params, clientIp)
            uri == "/focus-status" -> handleFocusStatus()
            uri == "/ui" -> handleUI()
            uri == "/" -> handleRoot()
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                """{"error": "Not found", "endpoints": ["/play?file=<name>.mp3", "/play-url?url=<myinstants-url>", "/stop", "/status", "/recent", "/rate-limits", "/focus-vote?vote=for|against", "/focus-status", "/ui"]}"""
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

        val url = BASE_URL + filename

        return try {
            if (!soundExists(url)) {
                RecentSoundsManager.removeSound(filename)
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    """{"error": "Sound not found", "file": "$filename"}"""
                )
            }

            val focusCheck = FocusTimeManager.checkPlay(clientIp)
            if (!focusCheck.allowed) {
                return newFixedLengthResponse(
                    FOCUS_BLOCKED,
                    "application/json",
                    """{"error": "${focusCheck.reason}", "playPolicy": "${focusCheck.policy.name}"}"""
                )
            }

            if (focusCheck.policy == FocusTimeManager.PlayPolicy.NORMAL) {
                val rateLimitResult = RateLimitManager.checkAndRecord(clientIp)
                if (!rateLimitResult.allowed) {
                    return newFixedLengthResponse(
                        TOO_MANY_REQUESTS,
                        "application/json",
                        """{"error": "Rate limit exceeded", "used": ${rateLimitResult.used}, "limit": ${rateLimitResult.limit}, "retryAfterSeconds": ${rateLimitResult.remainingSeconds}}"""
                    )
                }
            }

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
            if (!soundExists(soundUrl)) {
                RecentSoundsManager.removeSound(filename)
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    """{"error": "Sound not found", "file": "$filename"}"""
                )
            }

            val focusCheck = FocusTimeManager.checkPlay(clientIp)
            if (!focusCheck.allowed) {
                return newFixedLengthResponse(
                    FOCUS_BLOCKED,
                    "application/json",
                    """{"error": "${focusCheck.reason}", "playPolicy": "${focusCheck.policy.name}"}"""
                )
            }

            if (focusCheck.policy == FocusTimeManager.PlayPolicy.NORMAL) {
                val rateLimitResult = RateLimitManager.checkAndRecord(clientIp)
                if (!rateLimitResult.allowed) {
                    return newFixedLengthResponse(
                        TOO_MANY_REQUESTS,
                        "application/json",
                        """{"error": "Rate limit exceeded", "used": ${rateLimitResult.used}, "limit": ${rateLimitResult.limit}, "retryAfterSeconds": ${rateLimitResult.remainingSeconds}}"""
                    )
                }
            }

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

    private fun handleFocusVote(params: Map<String, String>, clientIp: String): Response {
        val voteParam = params["vote"]
        if (voteParam == null || voteParam !in listOf("for", "against")) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error": "Missing or invalid 'vote' parameter. Use vote=for or vote=against"}"""
            )
        }
        val status = FocusTimeManager.vote(clientIp, voteParam == "for")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"state": "${status.state.name}", "votesFor": ${status.votesFor}, "votesAgainst": ${status.votesAgainst}, "remainingSeconds": ${status.remainingSeconds}, "playPolicy": "${status.playPolicy.name}", "focusPlaysUsed": ${status.focusPlaysUsed}, "focusPlaysLimit": ${status.focusPlaysLimit}, "yourVote": "$voteParam"}"""
        )
    }

    private fun handleFocusStatus(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            FocusTimeManager.toJson()
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

    private fun soundExists(url: String): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return try {
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
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

        .sound-card {
            position: relative;
            border-radius: 12px;
            box-shadow: 0 4px 6px rgba(0,0,0,0.3),
                        inset 0 1px 0 rgba(255,255,255,0.2);
            transition: transform 0.1s, box-shadow 0.1s;
            min-height: 80px;
        }

        .sound-card:hover {
            transform: translateY(-2px);
            box-shadow: 0 6px 12px rgba(0,0,0,0.4),
                        inset 0 1px 0 rgba(255,255,255,0.2);
        }

        .sound-card:active {
            transform: translateY(1px);
            box-shadow: 0 2px 4px rgba(0,0,0,0.3),
                        inset 0 1px 0 rgba(255,255,255,0.2);
        }

        .sound-button {
            display: flex;
            align-items: center;
            justify-content: center;
            width: 100%;
            height: 100%;
            min-height: 80px;
            padding: 20px 36px 20px 15px;
            border-radius: 12px;
            cursor: pointer;
            font-size: 14px;
            font-weight: 600;
            color: #fff;
            text-decoration: none;
            text-shadow: 1px 1px 2px rgba(0,0,0,0.3);
            word-wrap: break-word;
            word-break: break-word;
            text-align: center;
            box-sizing: border-box;
        }

        .copy-link-btn {
            position: absolute;
            top: 6px;
            right: 6px;
            width: 28px;
            height: 28px;
            padding: 0;
            border: none;
            border-radius: 50%;
            background: rgba(0,0,0,0.30);
            color: #fff;
            font-size: 14px;
            line-height: 1;
            cursor: pointer;
            display: flex;
            align-items: center;
            justify-content: center;
            opacity: 0.75;
            transition: opacity 0.15s, background 0.15s;
            z-index: 1;
        }

        .copy-link-btn:hover, .copy-link-btn:focus {
            opacity: 1;
            background: rgba(0,0,0,0.50);
            outline: none;
        }

        @media (max-width: 600px) {
            .copy-link-btn {
                width: 36px;
                height: 36px;
                opacity: 1;
                font-size: 16px;
            }
        }

        .toast {
            position: fixed;
            bottom: 32px;
            left: 50%;
            transform: translateX(-50%) translateY(20px);
            background: rgba(0,0,0,0.85);
            color: #fff;
            padding: 10px 20px;
            border-radius: 24px;
            font-size: 14px;
            opacity: 0;
            pointer-events: none;
            transition: opacity 0.2s, transform 0.2s;
            z-index: 1000;
        }

        .toast.visible {
            opacity: 1;
            transform: translateX(-50%) translateY(0);
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

        .focus-section {
            max-width: 800px;
            margin: 0 auto 20px auto;
            background: #16213e;
            border-radius: 8px;
            padding: 15px;
            color: #ccc;
            font-size: 14px;
        }

        .focus-section h3 {
            color: #f39c12;
            margin: 0 0 12px 0;
            font-size: 16px;
        }

        .focus-buttons {
            display: flex;
            gap: 10px;
            margin-bottom: 12px;
        }

        .vote-btn {
            color: white;
            border: none;
            padding: 10px 20px;
            border-radius: 8px;
            font-size: 14px;
            cursor: pointer;
            font-weight: 600;
            flex: 1;
            outline: 3px solid transparent;
            outline-offset: -3px;
            transition: outline-color 0.15s;
        }

        .vote-btn.for { background: #e74c3c; }
        .vote-btn.for:hover { background: #c0392b; }
        .vote-btn.against { background: #27ae60; }
        .vote-btn.against:hover { background: #2ecc71; }
        .vote-btn.active { outline-color: #fff; }

        .focus-timer {
            font-family: monospace;
            font-size: 28px;
            color: #f39c12;
            text-align: center;
            margin: 8px 0;
        }

        .focus-vote-counts {
            display: flex;
            justify-content: center;
            gap: 30px;
            margin: 8px 0;
            font-size: 16px;
        }

        .focus-vote-counts .for-count { color: #e74c3c; }
        .focus-vote-counts .against-count { color: #27ae60; }

        .focus-policy {
            text-align: center;
            font-size: 13px;
            color: #888;
            margin-top: 8px;
        }
        .focus-policy.blocked { color: #e74c3c; font-weight: bold; }
        .focus-policy.unlimited { color: #27ae60; font-weight: bold; }
        .focus-policy.limited { color: #f39c12; font-weight: bold; }
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
    <div id="focusSection" class="focus-section">
        <h3>Focus Time</h3>
        <div class="focus-buttons">
            <button id="voteForBtn" class="vote-btn for" onclick="castVote('for')">Vote for Focus Time</button>
            <button id="voteAgainstBtn" class="vote-btn against" onclick="castVote('against')" style="display:none;">Vote Against</button>
        </div>
        <div id="focusInfo" style="display:none;">
            <div class="focus-vote-counts">
                <span class="for-count">For: <span id="votesForCount">0</span></span>
                <span class="against-count">Against: <span id="votesAgainstCount">0</span></span>
            </div>
            <div class="focus-timer" id="focusTimer">10:00</div>
            <div class="focus-policy" id="focusPolicy"></div>
        </div>
    </div>
    <div id="rateLimits" style="max-width: 800px; margin: 0 auto 20px auto; background: #16213e; border-radius: 8px; padding: 15px; color: #ccc; font-size: 14px; display:none;">
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

            container.innerHTML = sounds.map(function(sound) {
                var safeFilename = sound.filename.replace(/'/g, "\\'");
                var href = '/play?file=' + encodeURIComponent(sound.filename);
                return '<div class="sound-card" style="background-color: ' + sound.color + '">' +
                    '<a class="sound-button" href="' + href + '" onclick="return handlePlayClick(event, \'' + safeFilename + '\')">' +
                    escapeHtml(sound.displayName) +
                    '</a>' +
                    '<button class="copy-link-btn" type="button" title="Copy link to bookmark" aria-label="Copy link to bookmark" onclick="copyLink(event, \'' + safeFilename + '\')">🔗</button>' +
                    '</div>';
            }).join('');
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        function handlePlayClick(event, filename) {
            if (event.metaKey || event.ctrlKey || event.shiftKey || event.button === 1) {
                return true;
            }
            event.preventDefault();
            playSound(filename);
            return false;
        }

        async function copyLink(event, filename) {
            event.preventDefault();
            event.stopPropagation();
            var url = window.location.origin + '/play?file=' + encodeURIComponent(filename);
            try {
                await navigator.clipboard.writeText(url);
                showToast('Link copied!');
                return;
            } catch (err) {
                // navigator.clipboard requires HTTPS or localhost; on
                // http://192.168.x.x it throws. Fall through to the
                // execCommand path below.
            }
            var ta = document.createElement('textarea');
            ta.value = url;
            ta.setAttribute('readonly', '');
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            ta.style.left = '-9999px';
            document.body.appendChild(ta);
            ta.select();
            ta.setSelectionRange(0, url.length);
            var copied = false;
            try { copied = document.execCommand('copy'); } catch (e) {}
            document.body.removeChild(ta);
            if (copied) {
                showToast('Link copied!');
            } else {
                prompt('Copy this URL:', url);
            }
        }

        function showToast(message) {
            var toast = document.getElementById('toast');
            if (!toast) {
                toast = document.createElement('div');
                toast.id = 'toast';
                toast.className = 'toast';
                document.body.appendChild(toast);
            }
            toast.textContent = message;
            // Force reflow so the transition replays if shown back-to-back
            void toast.offsetWidth;
            toast.classList.add('visible');
            clearTimeout(toast._hideTimer);
            toast._hideTimer = setTimeout(function() {
                toast.classList.remove('visible');
            }, 1800);
        }

        async function playSound(filename) {
            document.getElementById('error').textContent = '';
            try {
                const response = await fetch('/play?file=' + encodeURIComponent(filename));
                const data = await response.json();
                if (response.status === 403) {
                    document.getElementById('error').textContent = data.error;
                    loadFocusStatus();
                    return;
                }
                if (response.status === 429) {
                    document.getElementById('error').textContent = 'Rate limited! Try again in ' + data.retryAfterSeconds + 's';
                    return;
                }
                if (response.status === 404) {
                    document.getElementById('error').textContent = 'Sound not found';
                }
                setTimeout(loadSounds, 100);
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
                if (response.status === 403) {
                    document.getElementById('error').textContent = data.error;
                    loadFocusStatus();
                    return;
                }
                if (response.status === 429) {
                    document.getElementById('error').textContent = 'Rate limited! Try again in ' + data.retryAfterSeconds + 's';
                    return;
                }
                if (data.error) {
                    document.getElementById('error').textContent = data.error;
                } else {
                    document.getElementById('urlInput').value = '';
                    setTimeout(loadSounds, 100);
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

        var myVote = null;
        var focusRemainingSeconds = 0;
        var focusTimerInterval = null;

        async function castVote(vote) {
            try {
                var response = await fetch('/focus-vote?vote=' + vote);
                var data = await response.json();
                myVote = vote;
                updateFocusUI(data);
            } catch (error) {
                console.error('Failed to cast vote:', error);
            }
        }

        async function loadFocusStatus() {
            try {
                var response = await fetch('/focus-status');
                var data = await response.json();
                updateFocusUI(data);
            } catch (error) {
                console.error('Failed to load focus status:', error);
            }
        }

        function updateFocusUI(data) {
            var focusInfo = document.getElementById('focusInfo');
            var voteForBtn = document.getElementById('voteForBtn');
            var voteAgainstBtn = document.getElementById('voteAgainstBtn');
            var votesForCount = document.getElementById('votesForCount');
            var votesAgainstCount = document.getElementById('votesAgainstCount');
            var focusTimer = document.getElementById('focusTimer');
            var focusPolicy = document.getElementById('focusPolicy');

            if (data.state === 'DEFAULT') {
                focusInfo.style.display = 'none';
                voteForBtn.style.display = '';
                voteAgainstBtn.style.display = 'none';
                voteForBtn.classList.remove('active');
                voteAgainstBtn.classList.remove('active');
                myVote = null;
                if (focusTimerInterval) {
                    clearInterval(focusTimerInterval);
                    focusTimerInterval = null;
                }
            } else {
                focusInfo.style.display = '';
                voteForBtn.style.display = '';
                voteAgainstBtn.style.display = '';

                votesForCount.textContent = data.votesFor;
                votesAgainstCount.textContent = data.votesAgainst;

                voteForBtn.classList.toggle('active', myVote === 'for');
                voteAgainstBtn.classList.toggle('active', myVote === 'against');

                focusRemainingSeconds = data.remainingSeconds;
                formatTimer(focusTimer, focusRemainingSeconds);

                if (focusTimerInterval) clearInterval(focusTimerInterval);
                focusTimerInterval = setInterval(function() {
                    if (focusRemainingSeconds > 0) {
                        focusRemainingSeconds--;
                        formatTimer(focusTimer, focusRemainingSeconds);
                    } else {
                        clearInterval(focusTimerInterval);
                        focusTimerInterval = null;
                    }
                }, 1000);

                focusPolicy.className = 'focus-policy';
                if (data.playPolicy === 'BLOCKED') {
                    focusPolicy.textContent = 'Sounds are BLOCKED (majority wants focus)';
                    focusPolicy.classList.add('blocked');
                } else if (data.playPolicy === 'UNLIMITED') {
                    focusPolicy.textContent = 'Sounds are UNLIMITED (majority against focus)';
                    focusPolicy.classList.add('unlimited');
                } else if (data.playPolicy === 'FOCUS_LIMITED') {
                    focusPolicy.textContent = 'Sounds limited: ' + data.focusPlaysLimit + ' plays per user (tie vote)';
                    focusPolicy.classList.add('limited');
                }
            }
        }

        function formatTimer(element, seconds) {
            var min = Math.floor(seconds / 60);
            var sec = seconds % 60;
            element.textContent = min + ':' + (sec < 10 ? '0' : '') + sec;
        }

        loadSounds();
        loadRateLimits();
        loadFocusStatus();
        setInterval(loadSounds, 5000);
        setInterval(loadRateLimits, 5000);
        setInterval(loadFocusStatus, 2000);
    </script>
</body>
</html>
    """.trimIndent()
}
